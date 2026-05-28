package com.harness.ai.react;

import com.harness.ai.model.ChatModelProvider;
import com.harness.ai.model.VisionModelProvider;
import com.harness.ai.model.VoiceModelProvider;
import com.harness.ai.model.impl.FallbackChatModel;
import com.harness.core.model.*;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.tool.ToolExecutor;
import com.harness.tool.ToolRegistry;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer 3+4: ReAct loop engine, powered by LangChain4j 1.15.
 * Uses FallbackChatModel for transparent multimodal degradation.
 */
public class ReActEngine {

    private static final Logger log = LoggerFactory.getLogger(ReActEngine.class);

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final int maxIterations;
    private final boolean stopOnToolError;

    public ReActEngine(ChatModelProvider chatModelProvider, ToolRegistry toolRegistry, ToolExecutor toolExecutor) {
        this(chatModelProvider, toolRegistry, toolExecutor, null, null);
    }

    public ReActEngine(ChatModelProvider chatModelProvider, ToolRegistry toolRegistry, ToolExecutor toolExecutor,
                       VisionModelProvider visionProvider, VoiceModelProvider voiceProvider) {
        ChatModel rawModel = chatModelProvider.chatModel();
        if (visionProvider != null || voiceProvider != null) {
            this.chatModel = new FallbackChatModel(rawModel, visionProvider, voiceProvider, chatModelProvider.modelName());
        } else {
            this.chatModel = rawModel;
        }
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        EnvConfig cfg = EnvConfig.get();
        this.maxIterations = cfg.getInt(EnvKey.REACT_MAX_ITERATIONS, 10);
        this.stopOnToolError = cfg.getBool(EnvKey.REACT_STOP_ON_TOOL_ERROR, false);
    }

    public ReActResult execute(String systemPrompt, String userMessage, AgentTrace.Builder traceBuilder) {
        return execute(systemPrompt, userMessage, List.of(), traceBuilder);
    }

    /**
     * Execute ReAct loop with optional history messages injected between system prompt and user message.
     *
     * @param systemPrompt system prompt
     * @param userMessage current user input
     * @param historyMessages prior conversation messages (as ChatMessage list)
     * @param traceBuilder trace collector
     */
    public ReActResult execute(String systemPrompt, String userMessage, List<ChatMessage> historyMessages, AgentTrace.Builder traceBuilder) {
        long loopStart = System.currentTimeMillis();
        log.info("[L3-ReAct] Starting ReAct loop: maxIterations={}, historyMessages={}, tools={}",
                maxIterations, historyMessages.size(), toolRegistry.size());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.addAll(historyMessages);
        messages.add(UserMessage.from(userMessage));

        List<ToolSpecification> toolSpecs = toToolSpecifications(toolRegistry.getAll());
        List<ReActStep> allSteps = new ArrayList<>();

        for (int i = 1; i <= maxIterations; i++) {
            log.info("[L3-ReAct] Iteration {}/{}", i, maxIterations);

            // Lightweight context cleanup: remove tool blocks if context is getting large
            if (i > 2) {
                stripToolMessages(messages);
            }

            ChatRequest.Builder reqBuilder = ChatRequest.builder().messages(messages);
            if (!toolSpecs.isEmpty()) {
                reqBuilder.toolSpecifications(toolSpecs);
            }

            long llmStart = System.currentTimeMillis();
            ChatResponse response = chatModel.chat(reqBuilder.build());
            long llmMs = System.currentTimeMillis() - llmStart;
            AiMessage aiMessage = response.aiMessage();

            if (response.metadata() != null && response.metadata().tokenUsage() != null) {
                var usage = response.metadata().tokenUsage();
                log.info("[L3-ReAct] LLM call in {}ms, tokens: in={}, out={}",
                        llmMs, usage.inputTokenCount(), usage.outputTokenCount());
                traceBuilder.totalTokens(
                        (traceBuilder.build().totalTokens())
                                + usage.inputTokenCount()
                                + usage.outputTokenCount()
                );
            } else {
                log.debug("[L3-ReAct] LLM call in {}ms (no token usage metadata)", llmMs);
            }

            if (aiMessage.toolExecutionRequests() == null || aiMessage.toolExecutionRequests().isEmpty()) {
                String answer = aiMessage.text();
                long totalMs = System.currentTimeMillis() - loopStart;
                log.info("[L3-ReAct] Complete at iteration {}, answer: {}", i, truncate(answer));
                log.info("[L3-ReAct] Finished in {}ms, steps={}, outputLen={}", totalMs, allSteps.size(), answer != null ? answer.length() : 0);
                allSteps.add(new ReActStep(i, answer, "final_answer",
                        List.of(), List.of(), answer,
                        new ReActStep.InspectionResult(ReActStep.InspectionResult.InspectionStatus.PASS, "complete")));
                messages.add(aiMessage);
                return new ReActResult(answer, allSteps);
            }

            messages.add(aiMessage);
            List<ToolExecutionRequest> toolReqs = aiMessage.toolExecutionRequests();
            log.info("[L3-ReAct] LLM requested {} tool calls", toolReqs.size());
            if (aiMessage.text() != null && !aiMessage.text().isBlank()) {
                log.debug("[L3-ReAct] LLM reasoning: {}", truncate(aiMessage.text()));
            }

            List<ToolCall> toolCalls = new ArrayList<>();
            List<ToolResult> toolResults = new ArrayList<>();

            for (ToolExecutionRequest toolReq : toolReqs) {
                JsonNode argsNode = parseArgs(toolReq.arguments());
                ToolCall tc = ToolCall.of(toolReq.name(), argsNode);
                toolCalls.add(tc);

                log.info("[L3-ReAct] Executing tool: {}", tc.toolName());
                log.debug("[L3-ReAct] Tool args: {}", truncate(tc.arguments().toString()));
                ToolResult result = toolExecutor.execute(tc);
                log.debug("[L3-ReAct] Tool [{}] result: success={}, output={}",
                        tc.toolName(), result.success(), truncate(result.success() ? result.output() : result.error()));
                toolResults.add(result);

                messages.add(ToolExecutionResultMessage.from(toolReq,
                        result.success() ? result.output() : "ERROR: " + result.error()));
            }

            ReActStep.InspectionResult inspection = inspect(toolCalls, toolResults);
            allSteps.add(new ReActStep(i,
                    aiMessage.text(),
                    toolReqs.stream().map(ToolExecutionRequest::name).reduce((a, b) -> a + "," + b).orElse(""),
                    toolCalls, toolResults,
                    toolResults.stream().map(ToolResult::output).reduce((a, b) -> a + "\n" + b).orElse(""),
                    inspection));

            if (inspection.status() == ReActStep.InspectionResult.InspectionStatus.TOOL_ERROR && stopOnToolError) {
                log.warn("[L3-ReAct] Tool error at iteration {}, stopping", i);
                break;
            }
        }

        String lastOutput = allSteps.isEmpty() ? "Max iterations reached" :
                allSteps.get(allSteps.size() - 1).observation();
        long totalMs = System.currentTimeMillis() - loopStart;
        log.warn("[L3-ReAct] Reached max iterations ({}), returning last output", maxIterations);
        log.info("[L3-ReAct] Finished in {}ms, steps={}", totalMs, allSteps.size());
        return new ReActResult(lastOutput, allSteps);
    }

    /**
     * Strip tool call/result messages from the conversation to free context space.
     * Keeps SystemMessage, UserMessage, and text-only AiMessages.
     * Removes: ToolExecutionResultMessage, AiMessage with toolExecutionRequests.
     */
    private void stripToolMessages(List<ChatMessage> messages) {
        int before = messages.size();
        messages.removeIf(msg -> {
            if (msg instanceof ToolExecutionResultMessage) return true;
            if (msg instanceof AiMessage ai && ai.toolExecutionRequests() != null && !ai.toolExecutionRequests().isEmpty()) return true;
            return false;
        });
        int removed = before - messages.size();
        if (removed > 0) {
            log.info("Stripped {} tool messages from context ({} → {})", removed, before, messages.size());
        }
    }

    private ReActStep.InspectionResult inspect(List<ToolCall> toolCalls, List<ToolResult> toolResults) {
        for (ToolResult result : toolResults) {
            if (!result.success()) {
                return new ReActStep.InspectionResult(
                        ReActStep.InspectionResult.InspectionStatus.TOOL_ERROR,
                        "Tool " + result.toolName() + " failed: " + result.error());
            }
        }
        return new ReActStep.InspectionResult(
                ReActStep.InspectionResult.InspectionStatus.PASS, "All tools executed successfully");
    }

    private List<ToolSpecification> toToolSpecifications(List<ToolSpec> specs) {
        return specs.stream().map(s -> {
            JsonObjectSchema params = buildJsonSchema(s.parameters());
            return ToolSpecification.builder()
                    .name(s.name())
                    .description(s.description())
                    .parameters(params)
                    .build();
        }).toList();
    }

    /**
     * Convert OpenAI-format JSON schema (JsonNode) to LangChain4j JsonObjectSchema.
     */
    private JsonObjectSchema buildJsonSchema(JsonNode schemaNode) {
        if (schemaNode == null || schemaNode.isNull()) {
            return null;
        }
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();

        JsonNode properties = schemaNode.get("properties");
        if (properties != null && properties.isObject()) {
            properties.fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                JsonNode prop = entry.getValue();
                String type = prop.has("type") ? prop.get("type").asText() : "string";
                String desc = prop.has("description") ? prop.get("description").asText() : null;

                switch (type) {
                    case "string" -> {
                        if (prop.has("enum") && prop.get("enum").isArray()) {
                            List<String> enumValues = new ArrayList<>();
                            prop.get("enum").forEach(e -> enumValues.add(e.asText()));
                            builder.addEnumProperty(name, enumValues, desc);
                        } else if (desc != null) {
                            builder.addStringProperty(name, desc);
                        } else {
                            builder.addStringProperty(name);
                        }
                    }
                    case "integer" -> {
                        if (desc != null) builder.addIntegerProperty(name, desc);
                        else builder.addIntegerProperty(name);
                    }
                    case "number" -> {
                        if (desc != null) builder.addNumberProperty(name, desc);
                        else builder.addNumberProperty(name);
                    }
                    case "boolean" -> {
                        if (desc != null) builder.addBooleanProperty(name, desc);
                        else builder.addBooleanProperty(name);
                    }
                    default -> {
                        if (desc != null) builder.addStringProperty(name, desc);
                        else builder.addStringProperty(name);
                    }
                }
            });
        }

        JsonNode required = schemaNode.get("required");
        if (required != null && required.isArray()) {
            List<String> reqList = new ArrayList<>();
            required.forEach(r -> reqList.add(r.asText()));
            builder.required(reqList);
        }

        return builder.build();
    }

    private JsonNode parseArgs(String arguments) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(arguments);
        } catch (Exception e) {
            log.warn("[L3-ReAct] Failed to parse tool args: {}", e.getMessage());
            return com.fasterxml.jackson.databind.node.NullNode.getInstance();
        }
    }

    private String truncate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    public record ReActResult(String output, List<ReActStep> steps) {}
}
