package com.harness.agent.runtime;

import com.harness.agent.AgentRunContext;
import com.harness.agent.KnowledgeGraphTool;
import com.harness.agent.SpawnSubAgentTool;
import com.harness.agent.SubAgentManager;
import com.harness.agent.context.KnowledgeAccessService;
import com.harness.agent.memory.AgentMemoryRuntime;
import com.harness.agent.memory.AgentMemoryRuntime.CompressionOutcome;
import com.harness.agent.runtime.AgentRunPreparer.AgentRunRequest;
import com.harness.agent.runtime.AgentRunPreparer.PreparedAgentRun;
import com.harness.agent.voice.VoiceOutputCoordinator;
import com.harness.agent.voice.VoiceOutputSettings;
import com.harness.core.concurrent.BlockingTaskExecutor;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.model.AgentContext;
import com.harness.core.model.AgentResult;
import com.harness.core.model.AgentTrace;
import com.harness.core.model.Artifact;
import com.harness.core.model.CancellationToken;
import com.harness.core.model.FinalOutputContract;
import com.harness.core.model.MessageBlock;
import com.harness.core.model.ReActStep;
import com.harness.core.model.RiskLevel;
import com.harness.core.model.StreamCallback;
import com.harness.core.model.StreamEvent;
import com.harness.core.model.ToolCallStatus;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;
import com.harness.core.runtime.RunTrace;
import com.harness.input.multimodal.MultimodalParser;
import com.harness.react.ReActListener;
import com.harness.react.ReActLoop;
import com.harness.react.ReActLoopStats;
import com.harness.react.ReActRequest;
import com.harness.react.ReActResult;
import com.harness.tool.RunToolCatalog;
import com.harness.tool.ToolExecutor;
import com.harness.tool.ToolRegistry;
import com.harness.tool.builtin.UpdateMemoryTool;
import com.harness.tool.builtin.StructuredOutputTool;
import com.harness.tool.confirmation.ConfirmationDecision;
import com.harness.tool.confirmation.ConfirmationExecutionContext;
import com.harness.tool.confirmation.ConfirmationRequest;
import com.harness.tool.skill.LoadSkillTool;
import com.harness.tool.web.AuthorizedUrlContext;
import com.harness.trace.ReplyAuditor;
import dev.langchain4j.data.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** Executes one prepared request through the ReAct loop and records its trace. */
public final class AgentRunCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AgentRunCoordinator.class);

    private final AgentRuntime runtime;
    private final AgentRunPreparer runPreparer;
    private final AgentMemoryRuntime memoryRuntime;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final SubAgentManager subAgentManager;
    private final ReplyAuditor replyAuditor;

    public AgentRunCoordinator(
            AgentRuntime runtime,
            AgentRunPreparer runPreparer,
            AgentMemoryRuntime memoryRuntime,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            SubAgentManager subAgentManager,
            ReplyAuditor replyAuditor
    ) {
        this.runtime = runtime;
        this.runPreparer = runPreparer;
        this.memoryRuntime = memoryRuntime;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.subAgentManager = subAgentManager;
        this.replyAuditor = replyAuditor;
    }

    public AgentResult run(AgentRunCommand command) {
        return run(command, command.finalOutputContract());
    }

    private AgentResult run(
            AgentRunCommand command,
            FinalOutputContract finalOutputContract
    ) {
        long startedAt = System.currentTimeMillis();
        RunTrace trace = runtime.startTrace();
        String runId = null;
        try {
            recordFinalOutputContract(trace, finalOutputContract);
            PreparedAgentRun prepared = runPreparer.prepare(toRequest(command, true), trace);
            RunToolCatalog toolCatalog = createToolCatalog(
                    prepared.unavailableTools(), finalOutputContract);
            runId = openRunScope(
                    prepared.sessionId(), command.cancellationToken(), toolCatalog, trace);

            List<MessageBlock> blocks = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            ReActListener listener = blockingListener(blocks, text);
            ReActResult result = createLoop(toolCatalog).execute(new ReActRequest(
                    prepared.systemPrompt(),
                    prepared.enhancedText(),
                    memoryRuntime.toChatMessages(prepared.shorttermMessages()),
                    trace,
                    listener,
                    command.cancellationToken(),
                    effectiveThinking(command, prepared),
                    null,
                    finalOutputContract));
            result.steps().forEach(trace::addStep);
            recordReactStats(trace, result);

            List<MessageBlock> assistantBlocks = finishAssistantBlocks(
                    blocks,
                    text,
                    result.output(),
                    !(finalOutputContract instanceof FinalOutputContract.JsonSchema));
            memoryRuntime.persistToolMessages(
                    result, prepared.sessionId(), prepared.userId());
            boolean confirmationRequired = requiresConfirmation(result);
            RiskLevel risk = determineRisk(result);
            trace.recordOutput(result.output(), risk, !confirmationRequired);
            scheduleReplyAudit(trace, result.output(), true);
            memoryRuntime.persistAssistantMessage(
                    prepared.sessionId(), prepared.userId(), assistantBlocks, true);

            AgentTrace agentTrace = trace.finish();
            log.info("Run complete: sessionId={}, steps={}, duration={}ms",
                    prepared.sessionId(), result.steps().size(),
                    System.currentTimeMillis() - startedAt);
            if (confirmationRequired) {
                return AgentResult.needConfirmation(
                        result.output(), risk, agentTrace, result.steps(),
                        result.artifacts(), assistantBlocks);
            }
            return AgentResult.success(
                    result.output(), agentTrace, result.steps(),
                    result.artifacts(), assistantBlocks);
        } catch (Exception e) {
            trace.recordOutput("Error: " + e.getMessage(), RiskLevel.HIGH, false);
            trace.finish();
            throw e;
        } finally {
            closeRunScope(runId);
        }
    }

    public void stream(AgentRunCommand command, StreamCallback callback) {
        long startedAt = System.currentTimeMillis();
        RunTrace trace = runtime.startTrace();
        String runId = null;
        try {
            PreparedAgentRun prepared = runPreparer.prepare(toRequest(command, false), trace);
            CompressionOutcome compression = prepared.compressionOutcome();
            emitCompressionEvents(compression, callback);
            callback.onEvent(StreamEvent.start(prepared.sessionId()));

            boolean voiceOutput = command.agentContext() != null
                    && command.agentContext().isVoiceOutput();
            VoiceOutputCoordinator voiceCoordinator = voiceOutput
                    ? new VoiceOutputCoordinator(
                            runtime.providers().voice(),
                            callback,
                            command.cancellationToken(),
                            VoiceOutputSettings.fromEnvironment())
                    : null;

            RunToolCatalog toolCatalog = createToolCatalog(prepared.unavailableTools());
            runId = openRunScope(
                    prepared.sessionId(), command.cancellationToken(), toolCatalog, trace);
            List<MessageBlock> blocks = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            AtomicReference<ConfirmationDecision> confirmationDecision = new AtomicReference<>();
            ReActListener listener = streamingListener(
                    callback, trace, blocks, text, voiceCoordinator, confirmationDecision);
            ConfirmationExecutionContext confirmationContext = new ConfirmationExecutionContext(
                    prepared.userId(),
                    prepared.sessionId(),
                    command.cancellationToken(),
                    listener::onConfirmationRequired,
                    listener::onConfirmationResolved,
                    toolCall -> listener.onToolCallStart(
                            toolCall.id(),
                            toolCall.toolName(),
                            toolCall.arguments() != null
                                    ? toolCall.arguments().toString()
                                    : "null"));

            ReActResult result = createLoop(toolCatalog).streamExecute(new ReActRequest(
                    prepared.systemPrompt(),
                    prepared.enhancedText(),
                    memoryRuntime.toChatMessages(prepared.shorttermMessages()),
                    trace,
                    listener,
                    command.cancellationToken(),
                    effectiveThinking(command, prepared),
                    confirmationContext));
            result.steps().forEach(trace::addStep);
            finishVoice(voiceCoordinator);
            recordReactStats(trace, result);

            List<MessageBlock> assistantBlocks = finishAssistantBlocks(
                    blocks, text, result.output(), false);
            memoryRuntime.persistToolMessages(
                    result, prepared.sessionId(), prepared.userId());
            boolean confirmationRequired = requiresConfirmation(result);
            ConfirmationDecision decision = confirmationDecision.get();
            RiskLevel risk = decision != null ? RiskLevel.HIGH : determineRisk(result);
            boolean userConfirmed = decision != null
                    ? decision == ConfirmationDecision.APPROVED
                    : !confirmationRequired;
            trace.recordOutput(result.output(), risk, userConfirmed);
            scheduleReplyAudit(trace, result.output(), false);
            memoryRuntime.persistAssistantMessage(
                    prepared.sessionId(), prepared.userId(), assistantBlocks, false);
            finishTraceAsync(trace);
            memoryRuntime.updateActivityAsync(prepared.sessionId());

            callback.onEvent(StreamEvent.done(
                    result.output(),
                    trace.traceId(),
                    prepared.sessionId(),
                    result.steps().size(),
                    result.artifacts(),
                    confirmationRequired));
            log.info("Stream run complete: sessionId={}, steps={}, duration={}ms",
                    prepared.sessionId(), result.steps().size(),
                    System.currentTimeMillis() - startedAt);
        } catch (CancellationException e) {
            finishTraceAsync(trace);
            callback.onEvent(StreamEvent.cancelled());
        } catch (Exception e) {
            trace.recordOutput("Error: " + e.getMessage(), RiskLevel.HIGH, false);
            finishTraceAsync(trace);
            callback.onEvent(StreamEvent.error(friendlyErrorMessage(e)));
        } finally {
            closeRunScope(runId);
        }
    }

    private static AgentRunRequest toRequest(AgentRunCommand command, boolean updateActivity) {
        return new AgentRunRequest(
                command.token(),
                command.text(),
                command.attachments(),
                command.requestedSessionId(),
                command.systemPromptOverride(),
                command.contextUserId(),
                command.agentContext(),
                updateActivity);
    }

    private ReActListener blockingListener(
            List<MessageBlock> blocks,
            StringBuilder text
    ) {
        return new ReActListener() {
            @Override
            public void onToken(String token) {
            }

            @Override
            public void onToolCallStart(String toolName, String arguments) {
            }

            @Override
            public void onStep(ReActStep step) {
                if (step.toolCalls() == null || step.toolCalls().isEmpty()) {
                    appendIfPresent(text, step.observation());
                }
            }

            @Override
            public void onArtifact(List<Artifact> artifacts) {
                flushText(blocks, text);
                artifacts.stream().map(AgentRunCoordinator::toArtifactBlock).forEach(blocks::add);
            }

            @Override
            public void onStructuredOutput(com.fasterxml.jackson.databind.JsonNode data) {
                flushText(blocks, text);
                blocks.add(toStructuredDataBlock(data));
            }
        };
    }

    private ReActListener streamingListener(
            StreamCallback callback,
            RunTrace trace,
            List<MessageBlock> blocks,
            StringBuilder text,
            VoiceOutputCoordinator voiceCoordinator,
            AtomicReference<ConfirmationDecision> confirmationDecision
    ) {
        return new ReActListener() {
            @Override
            public void onStep(ReActStep step) {
                callback.onEvent(StreamEvent.step(step));
            }

            @Override
            public void onToken(String token) {
                text.append(token);
                callback.onEvent(StreamEvent.token(token));
                if (voiceCoordinator != null) {
                    voiceCoordinator.accept(token);
                }
            }

            @Override
            public void onToolCallCreated(
                    String toolCallId, String toolName, String arguments) {
                callback.onEvent(StreamEvent.toolCallCreated(
                        toolCallId, toolName, arguments));
            }

            @Override
            public void onToolCallStart(
                    String toolCallId, String toolName, String arguments) {
                callback.onEvent(StreamEvent.toolCallStart(
                        toolCallId, toolName, arguments));
            }

            @Override
            public void onToolCallDone(
                    String toolCallId,
                    String toolName,
                    ToolCallStatus status,
                    long durationMs,
                    String errorSummary) {
                callback.onEvent(StreamEvent.toolCallDone(
                        toolCallId, toolName, status, durationMs, errorSummary));
            }

            @Override
            public void onConfirmationRequired(ConfirmationRequest request) {
                callback.onEvent(StreamEvent.confirmationRequired(
                        request.toolCallId(),
                        request.requestId(),
                        request.toolName(),
                        request.arguments(),
                        request.argumentsHash(),
                        request.summary(),
                        request.riskLevel().name(),
                        request.expiresAt().toString()));
            }

            @Override
            public void onConfirmationResolved(
                    ConfirmationRequest request,
                    ConfirmationDecision decision
            ) {
                confirmationDecision.set(decision);
                trace.recordConfirmation(
                        request.requestId(),
                        request.toolName(),
                        request.argumentsHash(),
                        decision.name());
                callback.onEvent(StreamEvent.confirmationResolved(
                        request.toolCallId(),
                        request.requestId(),
                        request.toolName(),
                        decision.name(),
                        confirmationStatus(decision)));
            }

            @Override
            public void onArtifact(List<Artifact> artifacts) {
                flushText(blocks, text);
                for (Artifact artifact : artifacts) {
                    blocks.add(toArtifactBlock(artifact));
                    callback.onEvent(StreamEvent.artifact(artifact));
                }
            }

            @Override
            public void onStructuredOutput(com.fasterxml.jackson.databind.JsonNode data) {
                flushText(blocks, text);
                blocks.add(toStructuredDataBlock(data));
                callback.onEvent(StreamEvent.structuredData(data));
            }
        };
    }

    private static ToolCallStatus confirmationStatus(ConfirmationDecision decision) {
        return switch (decision) {
            case APPROVED -> ToolCallStatus.RUNNING;
            case CANCELLED -> ToolCallStatus.CANCELLED;
            case REJECTED, EXPIRED -> ToolCallStatus.FAILED;
        };
    }

    private String openRunScope(
            String sessionId,
            CancellationToken cancellationToken,
            RunToolCatalog toolCatalog,
            RunTrace trace
    ) {
        String runId = UUID.randomUUID().toString();
        subAgentManager.openScope(runId);
        SpawnSubAgentTool.setCurrentRunContext(new AgentRunContext(
                runId, sessionId, cancellationToken, trace.traceId(), toolCatalog));
        Map<String, String> metadata = new HashMap<>(trace.snapshot().metadata());
        metadata.put("tool_catalog_version", String.valueOf(toolCatalog.version()));
        metadata.put("tool_count", String.valueOf(toolCatalog.size()));
        metadata.put("authorized_tools", toolCatalog.getAll().stream()
                .map(ToolSpec::name)
                .collect(Collectors.joining(",")));
        trace.putMetadata(metadata);
        return runId;
    }

    private void closeRunScope(String runId) {
        SpawnSubAgentTool.clearCurrentRunContext();
        if (runId != null) {
            subAgentManager.finishRun(runId);
        }
        LoadSkillTool.clearCurrentSession();
        UpdateMemoryTool.clearContext();
        KnowledgeGraphTool.clearCurrentContext();
        KnowledgeAccessService.clearCurrentContext();
        AuthorizedUrlContext.clear();
    }

    private RunToolCatalog createToolCatalog(
            Set<String> unavailableTools,
            FinalOutputContract finalOutputContract
    ) {
        RunToolCatalog catalog = toolRegistry.snapshot().excluding(unavailableTools);
        if (finalOutputContract instanceof FinalOutputContract.JsonSchema jsonSchema) {
            return catalog.replacing(StructuredOutputTool.terminal(jsonSchema));
        }
        return catalog;
    }

    private RunToolCatalog createToolCatalog(Set<String> unavailableTools) {
        return createToolCatalog(unavailableTools, new FinalOutputContract.Text());
    }

    private ReActLoop createLoop(RunToolCatalog toolCatalog) {
        return runtime.createLoop(toolCatalog, toolExecutor);
    }

    private void scheduleReplyAudit(RunTrace trace, String replyText, boolean recordMetadata) {
        CompletableFuture.runAsync(() -> {
            try {
                ReplyAuditor.ReplyAuditResult result = replyAuditor.audit(replyText);
                if (recordMetadata) {
                    Map<String, String> metadata = new HashMap<>(trace.snapshot().metadata());
                    metadata.put("reply_audit_passed", String.valueOf(result.passed()));
                    metadata.put("reply_audit_score", String.valueOf(result.score()));
                    metadata.put("reply_audit_reason", result.reason());
                    trace.putMetadata(metadata);
                }
            } catch (Exception e) {
                log.debug("Async reply audit failed: {}", e.getMessage());
            }
        }, BlockingTaskExecutor.shared());
    }

    private static void finishTraceAsync(RunTrace trace) {
        CompletableFuture.runAsync(() -> {
            try {
                trace.finish();
            } catch (Exception e) {
                log.error("Async trace finish failed: {}", e.getMessage(), e);
            }
        }, BlockingTaskExecutor.shared());
    }

    private static void emitCompressionEvents(
            CompressionOutcome compression,
            StreamCallback callback
    ) {
        if (compression.hasMinor()) {
            callback.onEvent(StreamEvent.compress(
                    "minor", compression.minorStripped() + " 条工具消息已清理"));
        }
        if (compression.hasMajor()) {
            callback.onEvent(StreamEvent.compress(
                    "major",
                    compression.majorResult().messagesBefore()
                            + " → "
                            + compression.majorResult().messagesAfter()
                            + " 条消息已压缩"));
        }
    }

    private static void finishVoice(VoiceOutputCoordinator voiceCoordinator) {
        if (voiceCoordinator == null) {
            return;
        }
        int timeoutSeconds = EnvConfig.get().getInt(EnvKey.MODEL_VOICE_TIMEOUT_SECONDS, 120);
        voiceCoordinator.finishAndAwait(Duration.ofSeconds(timeoutSeconds));
    }

    private static Boolean effectiveThinking(
            AgentRunCommand command,
            PreparedAgentRun prepared
    ) {
        return command.enableThinking() != null
                ? command.enableThinking()
                : prepared.gapAnalysis().needsThinking();
    }

    private static void recordReactStats(RunTrace trace, ReActResult result) {
        if (result.loopStats() == null) {
            return;
        }
        ReActLoopStats stats = result.loopStats();
        trace.recordReactStats(
                stats.outcome(),
                stats.rounds(),
                stats.toolCalls(),
                stats.reflectionChecks(),
                stats.inputTokens(),
                stats.outputTokens(),
                stats.llmCalls(),
                stats.toolRetries());
    }

    private static boolean requiresConfirmation(ReActResult result) {
        return result.steps().stream()
                .flatMap(step -> step.toolResults().stream())
                .anyMatch(toolResult -> toolResult.status()
                        == ToolResult.ResultStatus.CONFIRMATION_REQUIRED);
    }

    private static RiskLevel determineRisk(ReActResult result) {
        if (requiresConfirmation(result)) {
            return RiskLevel.HIGH;
        }
        boolean hasToolErrors = result.steps().stream()
                .flatMap(step -> step.toolResults().stream())
                .anyMatch(toolResult -> !toolResult.success());
        return hasToolErrors ? RiskLevel.MEDIUM : RiskLevel.LOW;
    }

    private static MessageBlock toArtifactBlock(Artifact artifact) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", artifact.type().name());
        if (artifact.mimeType() != null) {
            metadata.put("mimeType", artifact.mimeType());
        }
        if (artifact.name() != null) {
            metadata.put("name", artifact.name());
        }
        metadata.put("previewUrl", artifact.previewUrl());
        metadata.put("downloadUrl", artifact.downloadUrl());
        return new MessageBlock(
                MessageBlock.BlockType.ARTIFACT, null, artifact.id(), metadata);
    }

    private static MessageBlock toStructuredDataBlock(
            com.fasterxml.jackson.databind.JsonNode data
    ) {
        return new MessageBlock(
                MessageBlock.BlockType.STRUCTURED_DATA,
                null,
                null,
                Map.of("data", data.deepCopy()));
    }

    private static List<MessageBlock> finishAssistantBlocks(
            List<MessageBlock> blocks,
            StringBuilder text,
            String fallbackOutput,
            boolean appendFallbackAfterBlocks
    ) {
        flushText(blocks, text);
        if (blocks.isEmpty()
                || (appendFallbackAfterBlocks
                && fallbackOutput != null
                && !fallbackOutput.isBlank())) {
            blocks.add(new MessageBlock(
                    MessageBlock.BlockType.TEXT,
                    fallbackOutput != null ? fallbackOutput : "",
                    null));
        }
        return List.copyOf(blocks);
    }

    private static void flushText(List<MessageBlock> blocks, StringBuilder text) {
        if (text.length() == 0) {
            return;
        }
        blocks.add(new MessageBlock(MessageBlock.BlockType.TEXT, text.toString(), null));
        text.setLength(0);
    }

    private static void appendIfPresent(StringBuilder target, String value) {
        if (value != null && !value.isBlank()) {
            target.append(value);
        }
    }

    public static String friendlyErrorMessage(Exception exception) {
        Throwable cause = exception;
        while (cause.getCause() != null && cause != cause.getCause()) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return "服务内部错误，请稍后重试";
        }
        String lower = message.toLowerCase();
        if (lower.contains("insufficient_quota") || lower.contains("exhausted")) {
            return "API 额度已用完，请充值或更换 API Key";
        }
        if (lower.contains("invalid_api_key") || lower.contains("authentication")
                || lower.contains("unauthorized")) {
            return "API Key 无效或已过期，请检查配置";
        }
        if (lower.contains("rate_limit") || lower.contains("too_many_requests")
                || lower.contains("429")) {
            return "API 请求频率超限，请稍后重试";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "API 请求超时，请稍后重试";
        }
        if (lower.contains("context_length")
                || lower.contains("token") && lower.contains("limit")) {
            return "输入内容超出模型上下文长度限制";
        }
        String clean = message.replaceAll("\\{.*}", "").trim();
        if (clean.isEmpty()) {
            clean = message;
        }
        if (clean.length() > 200) {
            clean = clean.substring(0, 200) + "...";
        }
        return "模型调用失败: " + clean;
    }

    public record AgentRunCommand(
            String token,
            String text,
            List<MultimodalParser.RawAttachment> attachments,
            String requestedSessionId,
            String systemPromptOverride,
            CancellationToken cancellationToken,
            Boolean enableThinking,
            String contextUserId,
            AgentContext agentContext,
            FinalOutputContract finalOutputContract
    ) {
        public AgentRunCommand {
            finalOutputContract = finalOutputContract != null
                    ? finalOutputContract
                    : new FinalOutputContract.Text();
        }

        public AgentRunCommand(
                String token,
                String text,
                List<MultimodalParser.RawAttachment> attachments,
                String requestedSessionId,
                String systemPromptOverride,
                CancellationToken cancellationToken,
                Boolean enableThinking,
                String contextUserId,
                AgentContext agentContext
        ) {
            this(token, text, attachments, requestedSessionId, systemPromptOverride,
                    cancellationToken, enableThinking, contextUserId, agentContext,
                    new FinalOutputContract.Text());
        }
    }

    private static void recordFinalOutputContract(
            RunTrace trace, FinalOutputContract outputContract) {
        Map<String, String> metadata = new HashMap<>(trace.snapshot().metadata());
        if (outputContract instanceof FinalOutputContract.JsonSchema jsonSchema) {
            metadata.put("final_output_contract", "json_schema");
            metadata.put("final_output_schema_name", jsonSchema.name());
            metadata.put("final_output_schema_strict", String.valueOf(jsonSchema.strict()));
        } else {
            metadata.put("final_output_contract", "text");
        }
        trace.putMetadata(metadata);
    }
}
