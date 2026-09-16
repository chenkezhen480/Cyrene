package com.harness.input.memory;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.model.MemoryMessage;
import com.harness.core.model.MessageBlock;
import com.harness.input.multimodal.TextChunker;
import com.harness.provider.ChatModelProvider;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Replaces oldest completed Turns with stable, structured Turn summaries. */
public class MemoryCompressor {

    private static final Logger log = LoggerFactory.getLogger(MemoryCompressor.class);

    private final MessageStore messageStore;
    private final TurnCompressibleMessageStore turnStore;
    private final SessionStore sessionStore;
    private final ChatModelProvider chatModel;
    private final int majorThreshold;
    private final int majorTargetPercent;
    private final int keepRecentTurns;

    public MemoryCompressor(
            MessageStore messageStore,
            SessionStore sessionStore,
            ChatModelProvider chatModel
    ) {
        this.messageStore = Objects.requireNonNull(messageStore, "messageStore");
        this.turnStore = messageStore instanceof TurnCompressibleMessageStore capable
                ? capable
                : null;
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        EnvConfig config = EnvConfig.get();
        this.majorThreshold = config.getInt(EnvKey.CTX_COMPRESS_MAJOR, 85);
        this.majorTargetPercent = config.getInt(EnvKey.CTX_COMPRESS_MAJOR_TARGET, 30);
        this.keepRecentTurns = Math.max(0, config.getInt(
                EnvKey.CTX_COMPRESS_KEEP_RECENT_TURNS, 1));
        if (turnStore == null) {
            log.warn("Turn compression disabled: {} does not implement {}",
                    messageStore.getClass().getName(),
                    TurnCompressibleMessageStore.class.getSimpleName());
        }
    }

    public record CompressionResult(
            CompressionType type,
            int messagesBefore,
            int messagesAfter
    ) {
        public enum CompressionType {
            NONE, MAJOR
        }
    }

    public CompressionResult compressIfNeeded(
            String sessionId,
            List<MemoryMessage> messages,
            int shorttermTokens,
            int totalUsedTokens,
            int totalBudget
    ) {
        int totalUsagePercent = (int) (totalUsedTokens * 100.0 / totalBudget);
        if (totalUsagePercent < majorThreshold) {
            return none(messages.size());
        }
        if (turnStore == null) {
            return none(messages.size());
        }

        int messagesBefore = messages.size();
        int fixedTokens = Math.max(0, totalUsedTokens - shorttermTokens);
        int targetTokens = (int) (totalBudget * majorTargetPercent / 100.0);
        List<MemoryMessage> current = List.copyOf(messages);
        int compressedTurns = 0;

        while (fixedTokens + estimateTokens(current) > targetTokens) {
            CompletedTurn turn = oldestCompressibleTurn(current);
            if (turn == null) {
                break;
            }
            MessageWrite summary = summarize(sessionId, turn, targetTokens);
            turnStore.replaceTurnWithSummary(
                    sessionId,
                    turn.messages().stream().map(MemoryMessage::id).toList(),
                    summary);
            compressedTurns++;
            current = messageStore.loadForContext(sessionId);
        }

        if (compressedTurns == 0) {
            return none(messagesBefore);
        }
        sessionStore.updateLastActive(sessionId);
        log.info("Turn compression: compressed {} completed turn(s), messages {} -> {}",
                compressedTurns, messagesBefore, current.size());
        return new CompressionResult(
                CompressionResult.CompressionType.MAJOR,
                messagesBefore,
                current.size());
    }

    private CompressionResult none(int messageCount) {
        return new CompressionResult(
                CompressionResult.CompressionType.NONE, messageCount, messageCount);
    }

    private CompletedTurn oldestCompressibleTurn(List<MemoryMessage> messages) {
        LinkedHashMap<String, TurnBuilder> turns = new LinkedHashMap<>();
        String legacyTurnId = null;
        int legacyIndex = 0;
        for (MemoryMessage message : messages) {
            if (message.isSummary()) {
                continue;
            }
            String turnId = message.traceId();
            if (turnId == null || turnId.isBlank()) {
                if ("user".equals(message.role())) {
                    legacyTurnId = "legacy-" + legacyIndex++;
                }
                turnId = legacyTurnId;
            }
            if (turnId != null) {
                turns.computeIfAbsent(turnId, TurnBuilder::new).add(message);
            }
        }
        List<CompletedTurn> completed = turns.values().stream()
                .filter(TurnBuilder::completed)
                .map(TurnBuilder::build)
                .toList();
        return completed.size() > keepRecentTurns
                ? completed.getFirst()
                : null;
    }

    private MessageWrite summarize(
            String sessionId,
            CompletedTurn turn,
            int targetTokens
    ) {
        String userIntent = turn.messages().stream()
                .filter(message -> "user".equals(message.role()))
                .map(MemoryMessage::modelText)
                .reduce((left, right) -> left + "\n" + right)
                .map(text -> truncate(text, 1_000))
                .orElse("");
        String finalAnswer = turn.messages().stream()
                .filter(message -> "assistant".equals(message.role()))
                .reduce((left, right) -> right)
                .map(MemoryMessage::modelText)
                .map(text -> truncate(text, 1_000))
                .orElse("");
        List<ToolSummary> tools = extractTools(turn.messages());
        String outcome = tools.stream().anyMatch(ToolSummary::failed)
                ? "completed_with_tool_failure"
                : "completed";
        String semanticSummary = semanticSummary(
                userIntent, tools, outcome, finalAnswer, targetTokens);

        List<Map<String, Object>> toolMetadata = tools.stream()
                .map(ToolSummary::metadata)
                .toList();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("summaryType", "turn");
        metadata.put("turnId", turn.turnId());
        metadata.put("userIntent", userIntent);
        metadata.put("tools", toolMetadata);
        metadata.put("keyResults", semanticSummary);
        metadata.put("outcome", outcome);
        metadata.put("finalAnswerSummary", finalAnswer);

        StringBuilder text = new StringBuilder("[Completed turn summary]\n")
                .append("User intent: ").append(userIntent).append('\n');
        if (!tools.isEmpty()) {
            text.append("Tools:\n");
            tools.forEach(tool -> text.append("- ")
                    .append(tool.toolName()).append(" [").append(tool.status()).append("] ")
                    .append(tool.keyResult()).append('\n'));
        }
        text.append("Outcome: ").append(outcome).append('\n')
                .append("Key results: ").append(semanticSummary).append('\n')
                .append("Final answer: ").append(finalAnswer);
        MessageBlock block = new MessageBlock(
                MessageBlock.BlockType.TEXT,
                text.toString(),
                null,
                Map.copyOf(metadata));
        return new MessageWrite(
                sessionId, turn.turnId(), "system", List.of(block), true);
    }

    private String semanticSummary(
            String userIntent,
            List<ToolSummary> tools,
            String outcome,
            String finalAnswer,
            int targetTokens
    ) {
        ChatModel model = chatModel.chatModel();
        if (model == null) {
            return deterministicSummary(tools, finalAnswer);
        }
        int targetChars = Math.max(200, Math.min(1_200, targetTokens));
        String toolFacts = tools.stream()
                .map(tool -> tool.toolName() + " [" + tool.status() + "]: "
                        + tool.keyResult()
                        + (tool.artifactIds().isEmpty() ? ""
                                : " artifacts=" + String.join(",", tool.artifactIds())))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("none");
        String prompt = """
                Summarize one completed conversation turn in at most %d characters.
                Preserve the user's intent, important results, failures, artifact identities,
                the outcome, and the final answer. Do not invent facts.

                User intent:
                %s

                Deterministic tool facts:
                %s

                Outcome: %s
                Final answer:
                %s
                """.formatted(targetChars, userIntent, toolFacts, outcome, finalAnswer);
        try {
            String summary = model.chat(UserMessage.from(prompt)).aiMessage().text();
            return truncate(summary, targetChars);
        } catch (RuntimeException e) {
            log.warn("Turn summary model call failed, using deterministic summary: {}",
                    e.getMessage());
            return deterministicSummary(tools, finalAnswer);
        }
    }

    private static String deterministicSummary(
            List<ToolSummary> tools,
            String finalAnswer
    ) {
        String toolResults = tools.stream()
                .map(tool -> tool.toolName() + "=" + tool.status() + ":" + tool.keyResult())
                .reduce((left, right) -> left + "; " + right)
                .orElse("No tools used");
        return truncate(toolResults + "; final=" + finalAnswer, 1_200);
    }

    private static List<ToolSummary> extractTools(List<MemoryMessage> messages) {
        LinkedHashMap<String, MutableToolSummary> tools = new LinkedHashMap<>();
        int anonymous = 0;
        for (MemoryMessage message : messages) {
            Map<String, Object> metadata = firstMetadata(message);
            if ("assistant_tool_call".equals(message.role())) {
                Object rawCalls = metadata.get("toolCalls");
                if (rawCalls instanceof List<?> calls) {
                    for (Object rawCall : calls) {
                        if (rawCall instanceof Map<?, ?> call) {
                            String id = stringValue(call.get("id"));
                            String name = stringValue(call.get("name"));
                            tools.putIfAbsent(id != null ? id : "call-" + anonymous++,
                                    new MutableToolSummary(name));
                        }
                    }
                }
                continue;
            }
            if (!"tool".equals(message.role())
                    && !"subagent_event".equals(message.role())) {
                continue;
            }
            String callId = stringValue(metadata.get("toolCallId"));
            String toolName = stringValue(metadata.get("toolName"));
            String key = callId != null ? callId : "result-" + anonymous++;
            MutableToolSummary tool = tools.computeIfAbsent(
                    key, ignored -> new MutableToolSummary(toolName));
            if (toolName != null) tool.toolName = toolName;
            tool.status = stringValue(metadata.get("status"));
            String result = message.modelText();
            if (tool.status == null) {
                tool.status = result.startsWith("ERROR:") ? "FAILED" : "SUCCEEDED";
            }
            tool.keyResult = truncate(result, 500);
            for (MessageBlock block : message.content()) {
                if (block.type() == MessageBlock.BlockType.ARTIFACT
                        && block.artifactId() != null) {
                    tool.artifactIds.add(block.artifactId());
                }
            }
            tool.error = stringValue(metadata.get("error"));
            if (tool.error == null && "FAILED".equals(tool.status)) {
                tool.error = tool.keyResult;
            }
        }
        return tools.values().stream().map(MutableToolSummary::freeze).toList();
    }

    private static Map<String, Object> firstMetadata(MemoryMessage message) {
        return message.content().isEmpty() || message.content().getFirst().metadata() == null
                ? Map.of()
                : message.content().getFirst().metadata();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int estimateTokens(List<MemoryMessage> messages) {
        return messages.stream()
                .mapToInt(message -> TextChunker.estimateTokens(message.modelText()))
                .sum();
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) return "";
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "...";
    }

    private record CompletedTurn(String turnId, List<MemoryMessage> messages) { }

    private static final class TurnBuilder {
        private final String turnId;
        private final List<MemoryMessage> messages = new ArrayList<>();
        private boolean hasUser;
        private boolean hasFinalAssistant;

        private TurnBuilder(String turnId) {
            this.turnId = turnId;
        }

        private void add(MemoryMessage message) {
            messages.add(message);
            hasUser |= "user".equals(message.role());
            hasFinalAssistant |= "assistant".equals(message.role());
        }

        private boolean completed() {
            return hasUser && hasFinalAssistant
                    && messages.stream().allMatch(message -> message.id() > 0);
        }

        private CompletedTurn build() {
            return new CompletedTurn(turnId, List.copyOf(messages));
        }
    }

    private record ToolSummary(
            String toolName,
            String status,
            String keyResult,
            List<String> artifactIds,
            String error
    ) {
        private boolean failed() {
            return "FAILED".equals(status)
                    || "CANCELLED".equals(status)
                    || "TIMED_OUT".equals(status);
        }

        private Map<String, Object> metadata() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("toolName", toolName);
            values.put("status", status);
            values.put("keyResult", keyResult);
            values.put("artifactIds", artifactIds);
            if (error != null && !error.isBlank()) values.put("error", error);
            return Map.copyOf(values);
        }
    }

    private static final class MutableToolSummary {
        private String toolName;
        private String status = "UNKNOWN";
        private String keyResult = "";
        private final LinkedHashSet<String> artifactIds = new LinkedHashSet<>();
        private String error;

        private MutableToolSummary(String toolName) {
            this.toolName = toolName != null ? toolName : "unknown";
        }

        private ToolSummary freeze() {
            return new ToolSummary(
                    toolName,
                    status,
                    keyResult,
                    List.copyOf(artifactIds),
                    error);
        }
    }
}
