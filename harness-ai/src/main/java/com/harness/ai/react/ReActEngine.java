package com.harness.ai.react;

import com.harness.ai.model.ChatModelProvider;
import com.harness.ai.model.VisionModelProvider;
import com.harness.ai.model.VoiceModelProvider;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import com.harness.ai.model.impl.FallbackChatModel;
import com.harness.core.model.*;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.tool.ToolExecutor;
import com.harness.tool.ToolRegistry;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Layer 3+4: ReAct loop engine, powered by LangChain4j 1.15.
 * Uses FallbackChatModel for transparent multimodal degradation.
 */
public class ReActEngine {

    private static final Logger log = LoggerFactory.getLogger(ReActEngine.class);

    private static final int TOOL_MAX_RETRIES = 3;

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final Inspector inspector;
    private final int maxIterations;
    private final boolean stopOnToolError;
    private final int reflectionInterval;
    private final int loopDetectionThreshold;

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
        this.streamingChatModel = chatModelProvider.streamingModel();
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.inspector = new Inspector();
        EnvConfig cfg = EnvConfig.get();
        this.maxIterations = cfg.getInt(EnvKey.REACT_MAX_ITERATIONS, 10);
        this.stopOnToolError = cfg.getBool(EnvKey.REACT_STOP_ON_TOOL_ERROR, false);
        this.reflectionInterval = cfg.getInt(EnvKey.REACT_REFLECTION_INTERVAL, 3);
        this.loopDetectionThreshold = cfg.getInt(EnvKey.REACT_LOOP_DETECTION_THRESHOLD, 3);
    }

    public ReActResult execute(String systemPrompt, String userMessage, AgentTrace.Builder traceBuilder) {
        return execute(systemPrompt, userMessage, List.of(), traceBuilder, null, null, null);
    }

    public ReActResult execute(String systemPrompt, String userMessage, List<ChatMessage> historyMessages, AgentTrace.Builder traceBuilder) {
        return execute(systemPrompt, userMessage, historyMessages, traceBuilder, null, null, null);
    }

    /**
     * Execute ReAct loop with optional history messages, step listener, and cancellation token.
     *
     * @param systemPrompt system prompt
     * @param userMessage current user input
     * @param historyMessages prior conversation messages (as ChatMessage list)
     * @param traceBuilder trace collector
     * @param listener optional callback for intermediate step events (for SSE streaming)
     * @param cancellationToken optional token to check for cancellation between iterations
     * @param enableThinking null = use env default, true = force thinking, false = force no thinking
     */
    public ReActResult execute(String systemPrompt, String userMessage, List<ChatMessage> historyMessages,
                               AgentTrace.Builder traceBuilder, ReActListener listener,
                               com.harness.core.model.CancellationToken cancellationToken,
                               Boolean enableThinking) {
        long loopStart = System.currentTimeMillis();
        log.debug("[L3-ReAct] Starting ReAct loop: maxIterations={}, historyMessages={}, tools={}, thinking={}",
                maxIterations, historyMessages.size(), toolRegistry.size(), enableThinking);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.addAll(historyMessages);
        messages.add(UserMessage.from(userMessage));

        List<ToolSpecification> toolSpecs = toToolSpecifications(toolRegistry.getAll());
        List<ReActStep> allSteps = new ArrayList<>();

        // Build per-request thinking parameters (request-level overrides model-level)
        ChatRequestParameters thinkingParams = buildThinkingParams(enableThinking);

        for (int i = 1; i <= maxIterations; i++) {
            log.debug("[L3-ReAct] Iteration {}/{}", i, maxIterations);

            // Check for cancellation between iterations
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                log.info("[L3-ReAct] Cancellation detected at iteration {}, stopping", i);
                String cancelledOutput = allSteps.isEmpty() ? "Request cancelled" :
                        allSteps.get(allSteps.size() - 1).observation();
                return new ReActResult(cancelledOutput, allSteps);
            }

            ChatRequest.Builder reqBuilder = ChatRequest.builder().messages(messages);
            if (thinkingParams != null) reqBuilder.parameters(thinkingParams);
            if (!toolSpecs.isEmpty()) {
                reqBuilder.toolSpecifications(toolSpecs);
            }

            long llmStart = System.currentTimeMillis();
            ChatResponse response;
            if (cancellationToken != null) cancellationToken.trackCurrentThread();
            try {
                response = chatModel.chat(reqBuilder.build());
            } catch (Exception e) {
                if (cancellationToken != null && cancellationToken.isCancelled()) {
                    log.info("[L3-ReAct] LLM call interrupted by cancellation");
                    String cancelledOutput = allSteps.isEmpty() ? "Request cancelled" :
                            allSteps.get(allSteps.size() - 1).observation();
                    return new ReActResult(cancelledOutput, allSteps);
                }
                throw e;
            } finally {
                if (cancellationToken != null) cancellationToken.untrackCurrentThread();
            }
            // Check cancellation immediately after LLM returns (may have been cancelled during call)
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                log.info("[L3-ReAct] Cancellation detected after LLM call at iteration {}", i);
                String cancelledOutput = allSteps.isEmpty() ? "Request cancelled" :
                        allSteps.get(allSteps.size() - 1).observation();
                return new ReActResult(cancelledOutput, allSteps);
            }
            long llmMs = System.currentTimeMillis() - llmStart;
            AiMessage aiMessage = response.aiMessage();

            if (response.metadata() != null && response.metadata().tokenUsage() != null) {
                var usage = response.metadata().tokenUsage();
                log.debug("[L3-ReAct] LLM call in {}ms, tokens: in={}, out={}",
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
                log.info("[L3-ReAct] Finished in {}ms, steps={}, outputLen={}", totalMs, allSteps.size(), answer != null ? answer.length() : 0);
                ReActStep finalStep = new ReActStep(i, answer, "final_answer",
                        List.of(), List.of(), answer,
                        new ReActStep.InspectionResult(ReActStep.InspectionResult.InspectionStatus.PASS, "complete"));
                allSteps.add(finalStep);
                if (listener != null) {
                    listener.onStep(finalStep);
                }
                messages.add(aiMessage);
                return new ReActResult(answer, allSteps);
            }

            messages.add(aiMessage);
            List<ToolExecutionRequest> toolReqs = aiMessage.toolExecutionRequests();
            log.debug("[L3-ReAct] LLM requested {} tool calls", toolReqs.size());
            if (aiMessage.text() != null && !aiMessage.text().isBlank()) {
                log.debug("[L3-ReAct] LLM reasoning: {}", truncate(aiMessage.text()));
            }

            List<ToolCall> toolCalls = new ArrayList<>();
            List<ToolResult> toolResults = new ArrayList<>();

            for (ToolExecutionRequest toolReq : toolReqs) {
                // Check cancellation before each tool execution
                if (cancellationToken != null && cancellationToken.isCancelled()) {
                    log.info("[L3-ReAct] Cancellation detected before tool execution at iteration {}", i);
                    String cancelledOutput = allSteps.isEmpty() ? "Request cancelled" :
                            allSteps.get(allSteps.size() - 1).observation();
                    return new ReActResult(cancelledOutput, allSteps);
                }

                JsonNode argsNode = parseArgs(toolReq.arguments());
                ToolCall tc = ToolCall.of(toolReq.name(), argsNode);
                toolCalls.add(tc);

                log.debug("[L3-ReAct] Executing tool: {}", tc.toolName());
                log.debug("[L3-ReAct] Tool args: {}", truncate(tc.arguments().toString()));
                ToolResult result = executeWithRetry(tc, listener, cancellationToken);
                log.debug("[L3-ReAct] Tool [{}] result: success={}, output={}",
                        tc.toolName(), result.success(), truncate(result.success() ? result.output() : result.error()));
                toolResults.add(result);

                messages.add(ToolExecutionResultMessage.from(toolReq,
                        result.success() ? result.output() : "ERROR: " + result.error()));
            }

            ReActStep.InspectionResult inspection = inspector.inspect(toolCalls, toolResults);
            ReActStep step = new ReActStep(i,
                    aiMessage.text(),
                    toolReqs.stream().map(ToolExecutionRequest::name).reduce((a, b) -> a + "," + b).orElse(""),
                    toolCalls, toolResults,
                    toolResults.stream().map(r -> r.output() != null ? r.output() : "").reduce((a, b) -> a + "\n" + b).orElse(""),
                    inspection);
            allSteps.add(step);
            if (listener != null) {
                listener.onStep(step);
            }

            // 循环检测：连续 N 次相同工具调用
            if (loopDetectionThreshold > 0) {
                ReActStep.InspectionResult loopResult = Inspector.detectLoop(allSteps, loopDetectionThreshold);
                if (loopResult != null) {
                    inspection = loopResult;
                    // 覆盖 step 中的 inspection 结果
                    allSteps.set(allSteps.size() - 1, new ReActStep(i, aiMessage.text(),
                            step.action(), toolCalls, toolResults, step.observation(), loopResult));
                }
            }

            if (inspection.status() != ReActStep.InspectionResult.InspectionStatus.PASS) {
                log.debug("[L3-ReAct] Inspection result: status={}, reason={}", inspection.status(), inspection.reason());
                String hint = Inspector.buildInspectionHint(inspection);
                if (hint != null) {
                    messages.add(UserMessage.from(hint));
                    log.debug("[L3-ReAct] Injected inspection hint into context: {}", hint);
                }
            }

            if (inspection.status() == ReActStep.InspectionResult.InspectionStatus.TOOL_ERROR && stopOnToolError) {
                log.warn("[L3-ReAct] Tool error at iteration {}, stopping", i);
                break;
            }

            // LOOP_DETECTED: 强制停止，发起一次无工具的总结调用
            if (inspection.status() == ReActStep.InspectionResult.InspectionStatus.LOOP_DETECTED) {
                log.warn("[L3-ReAct] Loop detected at iteration {}, forcing summary", i);
                String summary = forceSummary(messages, chatModel);
                allSteps.add(new ReActStep(i, summary, "summary", List.of(), List.of(), summary,
                        new ReActStep.InspectionResult(ReActStep.InspectionResult.InspectionStatus.PASS, "loop summary")));
                return new ReActResult(summary, allSteps);
            }

            // 反思注入：每隔 N 步注入一条反思消息
            maybeInjectReflection(messages, i, reflectionInterval, userMessage);
        }

        String lastOutput = allSteps.isEmpty() ? "Max iterations reached" :
                allSteps.get(allSteps.size() - 1).observation();
        long totalMs = System.currentTimeMillis() - loopStart;
        log.warn("[L3-ReAct] Reached max iterations ({}), returning last output", maxIterations);
        log.info("[L3-ReAct] Finished in {}ms, steps={}", totalMs, allSteps.size());
        return new ReActResult(lastOutput, allSteps);
    }

    /**
     * Streaming variant of execute(). Uses StreamingChatModel for real-time token output.
     * Falls back to blocking execute() if no streaming model is available.
     */
    public ReActResult streamExecute(String systemPrompt, String userMessage, List<ChatMessage> historyMessages,
                                      AgentTrace.Builder traceBuilder, ReActListener listener,
                                      com.harness.core.model.CancellationToken cancellationToken) {
        return streamExecute(systemPrompt, userMessage, historyMessages, traceBuilder, listener, cancellationToken, null);
    }

    public ReActResult streamExecute(String systemPrompt, String userMessage, List<ChatMessage> historyMessages,
                                      AgentTrace.Builder traceBuilder, ReActListener listener,
                                      com.harness.core.model.CancellationToken cancellationToken,
                                      Boolean enableThinking) {
        if (streamingChatModel == null) {
            log.warn("[L3-ReAct] Falling back to blocking mode (streaming unavailable)");
            return execute(systemPrompt, userMessage, historyMessages, traceBuilder, listener, cancellationToken, enableThinking);
        }

        long loopStart = System.currentTimeMillis();
        log.debug("[L3-ReAct] Starting STREAMING ReAct loop: maxIterations={}, tools={}",
                maxIterations, toolRegistry.size());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.addAll(historyMessages);
        messages.add(UserMessage.from(userMessage));

        List<ToolSpecification> toolSpecs = toToolSpecifications(toolRegistry.getAll());
        List<ReActStep> allSteps = new ArrayList<>();

        // Build per-request thinking parameters (request-level overrides model-level)
        ChatRequestParameters thinkingParams = buildThinkingParams(enableThinking);

        for (int i = 1; i <= maxIterations; i++) {
            log.debug("[L3-ReAct] Streaming iteration {}/{}", i, maxIterations);

            if (cancellationToken != null && cancellationToken.isCancelled()) {
                log.info("[L3-ReAct] Cancellation detected at iteration {}", i);
                String cancelledOutput = allSteps.isEmpty() ? "Request cancelled" :
                        allSteps.get(allSteps.size() - 1).observation();
                return new ReActResult(cancelledOutput, allSteps);
            }

            ChatRequest.Builder reqBuilder = ChatRequest.builder().messages(messages);
            if (thinkingParams != null) reqBuilder.parameters(thinkingParams);
            if (!toolSpecs.isEmpty()) {
                reqBuilder.toolSpecifications(toolSpecs);
            }

            // Streaming LLM call
            long llmStart = System.currentTimeMillis();
            CompletableFuture<ChatResponse> responseFuture = new CompletableFuture<>();

            streamingChatModel.chat(reqBuilder.build(), new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String text) {
                    if (listener != null) {
                        listener.onToken(text);
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    responseFuture.complete(response);
                }

                @Override
                public void onError(Throwable error) {
                    responseFuture.completeExceptionally(error);
                }
            });

            ChatResponse response;
            if (cancellationToken != null) cancellationToken.trackCurrentThread();
            try {
                response = responseFuture.get();
            } catch (Exception e) {
                if (cancellationToken != null && cancellationToken.isCancelled()) {
                    log.info("[L3-ReAct] Streaming LLM call interrupted by cancellation");
                    String cancelledOutput = allSteps.isEmpty() ? "Request cancelled" :
                            allSteps.get(allSteps.size() - 1).observation();
                    return new ReActResult(cancelledOutput, allSteps);
                }
                log.error("[L3-ReAct] Streaming LLM call failed: {}", e.getMessage());
                throw new RuntimeException("Streaming LLM call failed", e);
            } finally {
                if (cancellationToken != null) cancellationToken.untrackCurrentThread();
            }
            // Check cancellation after streaming LLM returns
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                log.info("[L3-ReAct] Cancellation detected after streaming LLM call at iteration {}", i);
                String cancelledOutput = allSteps.isEmpty() ? "Request cancelled" :
                        allSteps.get(allSteps.size() - 1).observation();
                return new ReActResult(cancelledOutput, allSteps);
            }

            long llmMs = System.currentTimeMillis() - llmStart;
            AiMessage aiMessage = response.aiMessage();

            if (response.metadata() != null && response.metadata().tokenUsage() != null) {
                var usage = response.metadata().tokenUsage();
                log.debug("[L3-ReAct] Streaming LLM call in {}ms, tokens: in={}, out={}",
                        llmMs, usage.inputTokenCount(), usage.outputTokenCount());
                traceBuilder.totalTokens(
                        (traceBuilder.build().totalTokens())
                                + usage.inputTokenCount()
                                + usage.outputTokenCount());
            }

            // Final answer (no tool calls)
            if (aiMessage.toolExecutionRequests() == null || aiMessage.toolExecutionRequests().isEmpty()) {
                String answer = aiMessage.text();
                long totalMs = System.currentTimeMillis() - loopStart;
                log.info("[L3-ReAct] Finished in {}ms, steps={}", totalMs, allSteps.size());
                ReActStep finalStep = new ReActStep(i, answer, "final_answer",
                        List.of(), List.of(), answer,
                        new ReActStep.InspectionResult(ReActStep.InspectionResult.InspectionStatus.PASS, "complete"));
                allSteps.add(finalStep);
                if (listener != null) {
                    listener.onStep(finalStep);
                }
                messages.add(aiMessage);
                return new ReActResult(answer, allSteps);
            }

            // Tool execution round
            messages.add(aiMessage);
            List<ToolExecutionRequest> toolReqs = aiMessage.toolExecutionRequests();
            log.debug("[L3-ReAct] Streaming: LLM requested {} tool calls", toolReqs.size());

            List<ToolCall> toolCalls = new ArrayList<>();
            List<ToolResult> toolResults = new ArrayList<>();

            for (ToolExecutionRequest toolReq : toolReqs) {
                // Check cancellation before each tool execution
                if (cancellationToken != null && cancellationToken.isCancelled()) {
                    log.info("[L3-ReAct] Cancellation detected before tool execution at iteration {}", i);
                    String cancelledOutput = allSteps.isEmpty() ? "Request cancelled" :
                            allSteps.get(allSteps.size() - 1).observation();
                    return new ReActResult(cancelledOutput, allSteps);
                }

                if (listener != null) {
                    listener.onToolCallStart(toolReq.name(), toolReq.arguments());
                }

                JsonNode argsNode = parseArgs(toolReq.arguments());
                ToolCall tc = ToolCall.of(toolReq.name(), argsNode);
                toolCalls.add(tc);

                log.debug("[L3-ReAct] Executing tool: {}", tc.toolName());
                ToolResult result = executeWithRetry(tc, listener, cancellationToken);
                toolResults.add(result);

                messages.add(ToolExecutionResultMessage.from(toolReq,
                        result.success() ? result.output() : "ERROR: " + result.error()));
            }

            ReActStep.InspectionResult inspection = inspector.inspect(toolCalls, toolResults);
            ReActStep step = new ReActStep(i,
                    aiMessage.text(),
                    toolReqs.stream().map(ToolExecutionRequest::name).reduce((a, b) -> a + "," + b).orElse(""),
                    toolCalls, toolResults,
                    toolResults.stream().map(r -> r.output() != null ? r.output() : "").reduce((a, b) -> a + "\n" + b).orElse(""),
                    inspection);
            allSteps.add(step);
            if (listener != null) {
                listener.onStep(step);
            }

            // 循环检测：连续 N 次相同工具调用
            if (loopDetectionThreshold > 0) {
                ReActStep.InspectionResult loopResult = Inspector.detectLoop(allSteps, loopDetectionThreshold);
                if (loopResult != null) {
                    inspection = loopResult;
                    allSteps.set(allSteps.size() - 1, new ReActStep(i, aiMessage.text(),
                            step.action(), toolCalls, toolResults, step.observation(), loopResult));
                }
            }

            if (inspection.status() != ReActStep.InspectionResult.InspectionStatus.PASS) {
                log.debug("[L3-ReAct] Inspection: status={}, reason={}", inspection.status(), inspection.reason());
                String hint = Inspector.buildInspectionHint(inspection);
                if (hint != null) {
                    messages.add(UserMessage.from(hint));
                }
            }

            if (inspection.status() == ReActStep.InspectionResult.InspectionStatus.TOOL_ERROR && stopOnToolError) {
                log.warn("[L3-ReAct] Tool error at iteration {}, stopping", i);
                break;
            }

            // LOOP_DETECTED: 强制停止，发起一次无工具的总结调用
            if (inspection.status() == ReActStep.InspectionResult.InspectionStatus.LOOP_DETECTED) {
                log.warn("[L3-ReAct] Streaming: Loop detected at iteration {}, forcing summary", i);
                String summary = forceSummary(messages, chatModel);
                allSteps.add(new ReActStep(i, summary, "summary", List.of(), List.of(), summary,
                        new ReActStep.InspectionResult(ReActStep.InspectionResult.InspectionStatus.PASS, "loop summary")));
                return new ReActResult(summary, allSteps);
            }

            // 反思注入：每隔 N 步注入一条反思消息
            maybeInjectReflection(messages, i, reflectionInterval, userMessage);
        }

        String lastOutput = allSteps.isEmpty() ? "Max iterations reached" :
                allSteps.get(allSteps.size() - 1).observation();
        long totalMs = System.currentTimeMillis() - loopStart;
        log.warn("[L3-ReAct] Streaming: reached max iterations ({}), returning last output", maxIterations);
        log.info("[L3-ReAct] Finished in {}ms, steps={}", totalMs, allSteps.size());
        return new ReActResult(lastOutput, allSteps);
    }

    /**
     * Execute a tool with retry on error. Retries up to TOOL_MAX_RETRIES times when the tool
     * returns a failure (success=false). If the tool succeeds (even with empty output), no retry.
     * Checks cancellation before each attempt.
     *
     * @param toolCall the tool call to execute
     * @param listener optional listener for streaming retry notifications
     * @param cancellationToken optional cancellation token to check between retries
     * @return the tool result (either success or final failure after retries)
     */
    private ToolResult executeWithRetry(ToolCall toolCall, ReActListener listener,
                                        com.harness.core.model.CancellationToken cancellationToken) {
        // Check cancellation before first attempt
        if (cancellationToken != null && cancellationToken.isCancelled()) {
            return ToolResult.fail(toolCall.id(), toolCall.toolName(), "Cancelled", 0);
        }

        ToolResult result = toolExecutor.execute(toolCall);

        if (result.success()) {
            return result;
        }

        // Retry on error up to TOOL_MAX_RETRIES times
        for (int attempt = 1; attempt <= TOOL_MAX_RETRIES; attempt++) {
            // Check cancellation before each retry
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                log.info("[L3-ReAct] Cancellation detected during tool retry for [{}]", toolCall.toolName());
                return ToolResult.fail(toolCall.id(), toolCall.toolName(), "Cancelled", 0);
            }

            log.warn("[L3-ReAct] Tool [{}] failed (attempt {}/{}), retrying: {}",
                    toolCall.toolName(), attempt, TOOL_MAX_RETRIES, result.error());
            if (listener != null) {
                listener.onToolCallStart(toolCall.toolName(), toolCall.arguments().toString());
            }
            result = toolExecutor.execute(toolCall);
            if (result.success()) {
                log.debug("[L3-ReAct] Tool [{}] succeeded on retry {}", toolCall.toolName(), attempt);
                return result;
            }
        }

        log.error("[L3-ReAct] Tool [{}] failed after {} retries", toolCall.toolName(), TOOL_MAX_RETRIES);
        return result;
    }

    /**
     * Strip tool call/result messages from the conversation to free context space.
     * Keeps SystemMessage, UserMessage, and text-only AiMessages.
     * Preserves load_skill results across minor compression.
     */
    /**
     * 注入的消息仅存在于当前 ReAct 调用的 messages 列表中，不会被持久化。
     */
    private void maybeInjectReflection(List<ChatMessage> messages, int step, int reflectionInterval, String userInput) {
        if (reflectionInterval <= 0) return;
        if (step % reflectionInterval != 0 || step == 0) return;

        String reflectionPrompt = """
            [System Reflection]
            Review the steps taken so far.
            1. Are we stuck in a loop or repeating the same tool calls?
            2. Are we actually making progress toward the user's original goal?
            Think briefly and adjust your next action. If the task is impossible, output the final answer now.

            Original task: %s
            Steps executed so far: %d
            """.formatted(userInput, step);

        messages.add(UserMessage.from(reflectionPrompt));
        log.debug("[L3-ReAct] Injected reflection at step {}", step);
    }

    /**
     * 强制总结：用当前 messages 列表发起一次不带工具的 LLM 调用，让 LLM 基于已有信息做总结性回复。
     * 用于 LOOP_DETECTED 时的收尾。
     */
    private String forceSummary(List<ChatMessage> messages, ChatModel model) {
        try {
            // 添加总结提示
            messages.add(UserMessage.from(
                    "[System] A loop was detected. You MUST now output a final answer based on the information gathered so far. Do NOT call any more tools."));

            ChatRequest request = ChatRequest.builder().messages(messages).build();
            ChatResponse response = model.chat(request);
            return response.aiMessage().text();
        } catch (Exception e) {
            log.error("[L3-ReAct] Force summary failed: {}", e.getMessage());
            return "Unable to complete the task due to repeated tool call loops. Please try rephrasing your request.";
        }
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

    /**
     * Build per-request thinking parameters for OpenAI.
     * Request-level customParameters override model-level settings.
     *
     * @return ChatRequestParameters with thinking override, or null if no override needed
     */
    private ChatRequestParameters buildThinkingParams(Boolean enableThinking) {
        if (enableThinking == null) return null; // use model default
        return OpenAiChatRequestParameters.builder()
                .customParameters(Map.of("enable_thinking", enableThinking))
                .build();
    }

    private String truncate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    public record ReActResult(String output, List<ReActStep> steps) {}
}
