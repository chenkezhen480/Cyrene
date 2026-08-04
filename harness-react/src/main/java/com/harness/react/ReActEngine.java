package com.harness.react;

import com.harness.provider.ChatModelProvider;
import com.harness.provider.VisionModelProvider;
import com.harness.provider.VoiceModelProvider;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import com.harness.provider.impl.FallbackChatModel;
import com.harness.core.model.*;
import com.harness.core.runtime.RunTrace;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.tool.ArtifactProducingTool;
import com.harness.tool.Tool;
import com.harness.tool.ToolExecutor;
import com.harness.tool.ToolCatalog;
import com.harness.tool.confirmation.ConfirmationExecutionContext;
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
import dev.langchain4j.model.output.TokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Layer 3+4: ReAct loop engine, powered by LangChain4j 1.15.
 * Uses FallbackChatModel for transparent multimodal degradation.
 */
public class ReActEngine implements ReActLoop {

    private static final String FINAL_STREAM_PLANNING_INSTRUCTION = """
            <voice_reply_tool_phase>
            This request requires a separately streamed final answer. While tools are available,
            do not compose the user-facing final answer. Call the tools still required, or respond
            with only READY_FOR_FINAL when no more tools are needed.
            </voice_reply_tool_phase>
            """;
    private static final String FINAL_STREAM_ANSWER_INSTRUCTION = """
            <voice_reply_final_phase>
            Tool use is now disabled. Produce the complete user-facing final answer now.
            Do not mention READY_FOR_FINAL or the phase transition.
            </voice_reply_final_phase>
            """;

    private static final Logger log = LoggerFactory.getLogger(ReActEngine.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ToolCatalog toolCatalog;
    private final ToolExecutor toolExecutor;
    private final Inspector inspector;
    private final AdaptiveReflector adaptiveReflector;
    private final int maxIterations;
    private final long llmTimeoutSeconds;

    /**
     * @param maxIterationsOverride if > 0, overrides the global HARNESS_REACT_MAX_ITERATIONS setting
     */
    ReActEngine(ChatModelProvider chatModelProvider, ToolCatalog toolCatalog, ToolExecutor toolExecutor,
                VisionModelProvider visionProvider, VoiceModelProvider voiceProvider,
                int maxIterationsOverride) {
        ChatModel rawModel = chatModelProvider.chatModel();
        if (visionProvider != null || voiceProvider != null) {
            this.chatModel = new FallbackChatModel(rawModel, visionProvider, voiceProvider, chatModelProvider.modelName());
        } else {
            this.chatModel = rawModel;
        }
        this.streamingChatModel = chatModelProvider.streamingModel();
        this.toolCatalog = toolCatalog;
        this.toolExecutor = toolExecutor;
        this.inspector = new Inspector();
        EnvConfig cfg = EnvConfig.get();
        int globalMax = cfg.getInt(EnvKey.REACT_MAX_ITERATIONS, 10);
        this.maxIterations = maxIterationsOverride > 0 ? maxIterationsOverride : globalMax;
        this.adaptiveReflector = new AdaptiveReflector(cfg.getInt(EnvKey.REACT_REFLECTION_THRESHOLD, 5));
        this.llmTimeoutSeconds = cfg.getInt(EnvKey.MODEL_CHAT_TIMEOUT_SECONDS, 300);
    }

    @Override
    public ReActResult execute(ReActRequest request) {
        return execute(
                request.systemPrompt(), request.userMessage(), request.historyMessages(), request.trace(),
                request.listener(), request.cancellationToken(), request.enableThinking(),
                request.confirmationContext());
    }

    /**
     * Execute ReAct loop with optional history messages, step listener, and cancellation token.
     *
     * @param systemPrompt system prompt
     * @param userMessage current user input
     * @param historyMessages prior conversation messages (as ChatMessage list)
     * @param trace run trace recorder
     * @param listener optional callback for intermediate step events (for SSE streaming)
     * @param cancellationToken optional token to check for cancellation between iterations
     * @param enableThinking null = use env default, true = force thinking, false = force no thinking
     */
    private ReActResult execute(String systemPrompt, String userMessage, List<ChatMessage> historyMessages,
                                RunTrace trace, ReActListener listener,
                                com.harness.core.model.CancellationToken cancellationToken,
                                Boolean enableThinking,
                                ConfirmationExecutionContext confirmationContext) {
        long loopStart = System.currentTimeMillis();
        log.debug("[L3-ReAct] Starting ReAct loop: maxIterations={}, historyMessages={}, tools={}, thinking={}",
                maxIterations, historyMessages.size(), toolCatalog.size(), enableThinking);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.addAll(historyMessages);
        messages.add(UserMessage.from(userMessage));

        List<ToolSpecification> toolSpecs = toToolSpecifications(toolCatalog.getAll());
        List<ReActStep> allSteps = new ArrayList<>();
        List<Artifact> allArtifacts = new ArrayList<>();
        ChatRequestParameters thinkingParams = buildThinkingParams(enableThinking);
        int totalToolCalls = 0;
        int reflectionChecks = 0;
        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        int llmCalls = 0;
        int toolRetries = 0;

        // 设置 ThreadLocal，整个 ReAct 循环期间工具都可读取步骤历史
        ReActStep.setCurrentSteps(allSteps);
        try {
            for (int i = 1; i <= maxIterations; i++) {
                log.debug("[L3-ReAct] Iteration {}/{}", i, maxIterations);

                if (cancellationToken != null && cancellationToken.isCancelled()) {
                    log.info("[L3-ReAct] Cancellation detected at iteration {}, stopping", i);
                    return cancelledResult(allSteps, allArtifacts, totalToolCalls, i, reflectionChecks,
                            totalInputTokens, totalOutputTokens, llmCalls, toolRetries);
                }

                ChatRequestParameters mergedParams = buildMergedParams(thinkingParams, toolSpecs);
                ChatRequest.Builder reqBuilder = ChatRequest.builder().messages(messages);
                if (mergedParams != null) {
                    reqBuilder.parameters(mergedParams);
                }

                // Blocking LLM call
                long llmStart = System.currentTimeMillis();
                ChatResponse response;
                if (cancellationToken != null) cancellationToken.trackCurrentThread();
                try {
                    response = chatModel.chat(reqBuilder.build());
                } catch (Exception e) {
                    if (cancellationToken != null && cancellationToken.isCancelled()) {
                        log.info("[L3-ReAct] LLM call interrupted by cancellation");
                        return cancelledResult(allSteps, allArtifacts, totalToolCalls, i, reflectionChecks,
                                totalInputTokens, totalOutputTokens, llmCalls, toolRetries);
                    }
                    throw e;
                } finally {
                    if (cancellationToken != null) cancellationToken.untrackCurrentThread();
                }
                if (cancellationToken != null && cancellationToken.isCancelled()) {
                    log.info("[L3-ReAct] Cancellation detected after LLM call at iteration {}", i);
                    return cancelledResult(allSteps, allArtifacts, totalToolCalls, i, reflectionChecks,
                            totalInputTokens, totalOutputTokens, llmCalls, toolRetries);
                }

                AiMessage aiMessage = response.aiMessage();
                llmCalls++;
                TokenUsage tu = logTokenUsage(response, trace, System.currentTimeMillis() - llmStart);
                if (tu != null) {
                    totalInputTokens += tu.inputTokenCount();
                    totalOutputTokens += tu.outputTokenCount();
                }

                // Final answer (no tool calls)
                if (aiMessage.toolExecutionRequests() == null || aiMessage.toolExecutionRequests().isEmpty()) {
                    String answer = aiMessage.text();
                    long totalMs = System.currentTimeMillis() - loopStart;
                    log.info("[L3-ReAct] Finished in {}ms, steps={}, outputLen={}", totalMs, allSteps.size(), answer != null ? answer.length() : 0);
                    buildFinalStep(i, answer, allSteps, messages, aiMessage, listener);
                    ReActLoopStats stats = new ReActLoopStats("completed", i, totalToolCalls, reflectionChecks,
                            totalInputTokens, totalOutputTokens, llmCalls, toolRetries);
                    return new ReActResult(answer, allSteps, allArtifacts, stats);
                }

                // Tool execution round
                messages.add(aiMessage);
                List<ToolExecutionRequest> toolReqs = aiMessage.toolExecutionRequests();
                totalToolCalls += toolReqs.size();
                log.debug("[L3-ReAct] LLM requested {} tool calls", toolReqs.size());
                if (aiMessage.text() != null && !aiMessage.text().isBlank()) {
                    log.debug("[L3-ReAct] LLM reasoning: {}", truncate(aiMessage.text()));
                }

                ToolExecutionOutput toolOutput = executeToolCalls(
                        toolReqs, messages, allArtifacts, listener, cancellationToken, confirmationContext);
                if (toolOutput.earlyReturn() != null) return toolOutput.earlyReturn();

                // Post-tool processing: inspection, hints, adaptive reflection
                RoundOutcome outcome = processToolRound(i, aiMessage, toolReqs,
                        toolOutput.toolCalls(), toolOutput.toolResults(),
                        allSteps, allArtifacts, messages, listener, userMessage);
                if (outcome.reflected()) reflectionChecks++;
                if (outcome.inspectionStatus() == ReActStep.InspectionResult.InspectionStatus.TOOL_ERROR) {
                    toolRetries++;
                }
                if (outcome.action() == RoundAction.RETURN_RESULT) {
                    ReActResult r = outcome.result();
                    if (r.loopStats() == null) {
                        String loopOutcome = confirmationOutcome(outcome.inspectionStatus());
                        ReActLoopStats stats = new ReActLoopStats(loopOutcome, i, totalToolCalls, reflectionChecks,
                                totalInputTokens, totalOutputTokens, llmCalls, toolRetries);
                        return new ReActResult(r.output(), r.steps(), r.artifacts(), stats);
                    }
                    return r;
                }
            }

            String lastOutput = allSteps.isEmpty() ? "Max iterations reached" :
                    allSteps.get(allSteps.size() - 1).observation();
            long totalMs = System.currentTimeMillis() - loopStart;
            log.warn("[L3-ReAct] Reached max iterations ({}), returning last output", maxIterations);
            log.info("[L3-ReAct] Finished in {}ms, steps={}, artifacts={}", totalMs, allSteps.size(), allArtifacts.size());
            ReActLoopStats stats = new ReActLoopStats("max_iterations", maxIterations, totalToolCalls, reflectionChecks,
                    totalInputTokens, totalOutputTokens, llmCalls, toolRetries);
            return new ReActResult(lastOutput, allSteps, allArtifacts, stats);
        } finally {
            ReActStep.clearCurrentSteps();
        }
    }

    /**
     * Streaming variant of execute(). Uses StreamingChatModel for real-time token output.
     * Falls back to blocking execute() if no streaming model is available.
     */
    @Override
    public ReActResult streamExecute(ReActRequest request, boolean finalAnswerOnlyStreaming) {
        return streamExecute(
                request.systemPrompt(), request.userMessage(), request.historyMessages(), request.trace(),
                request.listener(), request.cancellationToken(), request.enableThinking(),
                request.confirmationContext(), finalAnswerOnlyStreaming);
    }

    private ReActResult streamExecute(String systemPrompt, String userMessage, List<ChatMessage> historyMessages,
                                      RunTrace trace, ReActListener listener,
                                      com.harness.core.model.CancellationToken cancellationToken,
                                      Boolean enableThinking,
                                      ConfirmationExecutionContext confirmationContext,
                                      boolean finalAnswerOnlyStreaming) {
        if (streamingChatModel == null) {
            log.warn("[L3-ReAct] Falling back to blocking mode (streaming unavailable)");
            return execute(systemPrompt, userMessage, historyMessages, trace, listener,
                    cancellationToken, enableThinking, confirmationContext);
        }

        long loopStart = System.currentTimeMillis();
        log.debug("[L3-ReAct] Starting STREAMING ReAct loop: maxIterations={}, tools={}",
                maxIterations, toolCatalog.size());

        List<ToolSpecification> toolSpecs = toToolSpecifications(toolCatalog.getAll());
        boolean guardedFinalStreaming = finalAnswerOnlyStreaming && !toolSpecs.isEmpty();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(guardedFinalStreaming
                ? systemPrompt + "\n\n" + FINAL_STREAM_PLANNING_INSTRUCTION
                : systemPrompt));
        messages.addAll(historyMessages);
        messages.add(UserMessage.from(userMessage));

        List<ReActStep> allSteps = new ArrayList<>();
        List<Artifact> allArtifacts = new ArrayList<>();
        ChatRequestParameters thinkingParams = buildThinkingParams(enableThinking);
        int totalToolCalls = 0;
        int reflectionChecks = 0;
        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        int llmCalls = 0;
        int toolRetries = 0;

        // 设置 ThreadLocal，整个 ReAct 循环期间工具都可读取步骤历史
        ReActStep.setCurrentSteps(allSteps);
        try {
            for (int i = 1; i <= maxIterations; i++) {
                log.debug("[L3-ReAct] Streaming iteration {}/{}", i, maxIterations);

                if (cancellationToken != null && cancellationToken.isCancelled()) {
                    log.info("[L3-ReAct] Cancellation detected at iteration {}", i);
                    return cancelledResult(allSteps, allArtifacts, totalToolCalls, i, reflectionChecks,
                            totalInputTokens, totalOutputTokens, llmCalls, toolRetries);
                }

                ChatRequestParameters mergedParams = buildMergedParams(thinkingParams, toolSpecs);
                ChatRequest.Builder reqBuilder = ChatRequest.builder().messages(messages);
                if (mergedParams != null) {
                    reqBuilder.parameters(mergedParams);
                }

                // Streaming LLM call
                long llmStart = System.currentTimeMillis();
                CompletableFuture<ChatResponse> responseFuture = new CompletableFuture<>();

                streamingChatModel.chat(reqBuilder.build(), new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String text) {
                        if (!guardedFinalStreaming && listener != null) {
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
                    response = responseFuture.get(llmTimeoutSeconds, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    log.error("[L3-ReAct] Streaming LLM call timed out after {}s", llmTimeoutSeconds);
                    throw new RuntimeException("Streaming LLM call timed out after " + llmTimeoutSeconds + "s", e);
                } catch (Exception e) {
                    if (cancellationToken != null && cancellationToken.isCancelled()) {
                        log.info("[L3-ReAct] Streaming LLM call interrupted by cancellation");
                        return cancelledResult(allSteps, allArtifacts, totalToolCalls, i, reflectionChecks,
                                totalInputTokens, totalOutputTokens, llmCalls, toolRetries);
                    }
                    Throwable cause = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
                    log.error("[L3-ReAct] Streaming LLM call failed: {}", cause.getMessage());
                    throw cause instanceof RuntimeException ? (RuntimeException) cause : new RuntimeException(cause);
                } finally {
                    if (cancellationToken != null) cancellationToken.untrackCurrentThread();
                }
                if (cancellationToken != null && cancellationToken.isCancelled()) {
                    log.info("[L3-ReAct] Cancellation detected after streaming LLM call at iteration {}", i);
                    return cancelledResult(allSteps, allArtifacts, totalToolCalls, i, reflectionChecks,
                            totalInputTokens, totalOutputTokens, llmCalls, toolRetries);
                }

                AiMessage aiMessage = response.aiMessage();
                llmCalls++;
                TokenUsage tu = logTokenUsage(response, trace, System.currentTimeMillis() - llmStart);
                if (tu != null) {
                    totalInputTokens += tu.inputTokenCount();
                    totalOutputTokens += tu.outputTokenCount();
                }

                // Final answer (no tool calls)
                if (aiMessage.toolExecutionRequests() == null || aiMessage.toolExecutionRequests().isEmpty()) {
                    if (guardedFinalStreaming) {
                        long finalLlmStart = System.currentTimeMillis();
                        response = streamFinalAnswer(
                                messages, systemPrompt, thinkingParams, listener, cancellationToken);
                        aiMessage = response.aiMessage();
                        llmCalls++;
                        TokenUsage finalUsage = logTokenUsage(
                                response, trace, System.currentTimeMillis() - finalLlmStart);
                        if (finalUsage != null) {
                            totalInputTokens += finalUsage.inputTokenCount();
                            totalOutputTokens += finalUsage.outputTokenCount();
                        }
                    }
                    String answer = aiMessage.text();
                    long totalMs = System.currentTimeMillis() - loopStart;
                    log.info("[L3-ReAct] Finished in {}ms, steps={}", totalMs, allSteps.size());
                    buildFinalStep(i, answer, allSteps, messages, aiMessage, listener);
                    ReActLoopStats stats = new ReActLoopStats("completed", i, totalToolCalls, reflectionChecks,
                            totalInputTokens, totalOutputTokens, llmCalls, toolRetries);
                    return new ReActResult(answer, allSteps, allArtifacts, stats);
                }

                // Tool execution round
                messages.add(aiMessage);
                List<ToolExecutionRequest> toolReqs = aiMessage.toolExecutionRequests();
                totalToolCalls += toolReqs.size();
                log.debug("[L3-ReAct] Streaming: LLM requested {} tool calls", toolReqs.size());

                ToolExecutionOutput toolOutput = executeToolCalls(
                        toolReqs, messages, allArtifacts, listener, cancellationToken, confirmationContext);
                if (toolOutput.earlyReturn() != null) return toolOutput.earlyReturn();

                // Post-tool processing: inspection, hints, adaptive reflection
                RoundOutcome outcome = processToolRound(i, aiMessage, toolReqs,
                        toolOutput.toolCalls(), toolOutput.toolResults(),
                        allSteps, allArtifacts, messages, listener, userMessage);
                if (outcome.reflected()) reflectionChecks++;
                if (outcome.inspectionStatus() == ReActStep.InspectionResult.InspectionStatus.TOOL_ERROR) {
                    toolRetries++;
                }
                if (outcome.action() == RoundAction.RETURN_RESULT) {
                    ReActResult r = outcome.result();
                    if (r.loopStats() == null) {
                        String loopOutcome = confirmationOutcome(outcome.inspectionStatus());
                        ReActLoopStats stats = new ReActLoopStats(loopOutcome, i, totalToolCalls, reflectionChecks,
                                totalInputTokens, totalOutputTokens, llmCalls, toolRetries);
                        return new ReActResult(r.output(), r.steps(), r.artifacts(), stats);
                    }
                    return r;
                }
            }

            String lastOutput = allSteps.isEmpty() ? "Max iterations reached" :
                    allSteps.get(allSteps.size() - 1).observation();
            long totalMs = System.currentTimeMillis() - loopStart;
            log.warn("[L3-ReAct] Streaming: reached max iterations ({}), returning last output", maxIterations);
            log.info("[L3-ReAct] Finished in {}ms, steps={}, artifacts={}", totalMs, allSteps.size(), allArtifacts.size());
            ReActLoopStats stats = new ReActLoopStats("max_iterations", maxIterations, totalToolCalls, reflectionChecks,
                    totalInputTokens, totalOutputTokens, llmCalls, toolRetries);
            return new ReActResult(lastOutput, allSteps, allArtifacts, stats);
        } finally {
            ReActStep.clearCurrentSteps();
        }
    }

    private ChatResponse streamFinalAnswer(
            List<ChatMessage> messages,
            String systemPrompt,
            ChatRequestParameters thinkingParams,
            ReActListener listener,
            com.harness.core.model.CancellationToken cancellationToken
    ) {
        if (cancellationToken != null && cancellationToken.isCancelled()) {
            throw new java.util.concurrent.CancellationException("Final answer generation cancelled");
        }

        List<ChatMessage> finalMessages = new ArrayList<>(messages);
        finalMessages.set(0, SystemMessage.from(
                systemPrompt + "\n\n" + FINAL_STREAM_ANSWER_INSTRUCTION));
        ChatRequest.Builder requestBuilder = ChatRequest.builder().messages(finalMessages);
        if (thinkingParams != null) {
            requestBuilder.parameters(thinkingParams);
        }

        CompletableFuture<ChatResponse> responseFuture = new CompletableFuture<>();
        streamingChatModel.chat(requestBuilder.build(), new StreamingChatResponseHandler() {
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

        if (cancellationToken != null) {
            cancellationToken.trackCurrentThread();
        }
        try {
            return responseFuture.get(llmTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException(
                    "Final answer streaming call timed out after " + llmTimeoutSeconds + "s", e);
        } catch (Exception e) {
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                throw new java.util.concurrent.CancellationException("Final answer generation cancelled");
            }
            Throwable cause = e instanceof java.util.concurrent.CompletionException && e.getCause() != null
                    ? e.getCause()
                    : e;
            throw cause instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new RuntimeException(cause);
        } finally {
            if (cancellationToken != null) {
                cancellationToken.untrackCurrentThread();
            }
        }
    }

    /**
     * Execute a tool once. On error, returns the failure result to the LLM
     * which learns and adjusts strategy in the next ReAct iteration.
     *
     * @param toolCall the tool call to execute
     * @param listener optional listener (unused, kept for interface consistency)
     * @param cancellationToken optional cancellation token to check before execution
     * @return the tool result
     */
    private ToolResult executeWithRetry(ToolCall toolCall, ReActListener listener,
                                        com.harness.core.model.CancellationToken cancellationToken,
                                        ConfirmationExecutionContext confirmationContext) {
        if (cancellationToken != null && cancellationToken.isCancelled()) {
            return ToolResult.fail(toolCall.id(), toolCall.toolName(), "Cancelled", 0);
        }

        // Track thread for interruption during tool execution
        if (cancellationToken != null) cancellationToken.trackCurrentThread();

        // Register CancellableTool's cancel callback
        Runnable cancelCallback = null;
        Tool tool = toolCatalog.get(toolCall.toolName());
        if (tool == null) {
            log.warn("[L3-ReAct] Blocked unavailable tool call: {}", toolCall.toolName());
            return ToolResult.fail(
                    toolCall.id(),
                    toolCall.toolName(),
                    "Tool is unavailable for this agent run: " + toolCall.toolName(),
                    0);
        }
        if (cancellationToken != null && tool instanceof com.harness.tool.CancellableTool ct) {
            cancelCallback = ct::cancel;
            cancellationToken.addCancelCallback(cancelCallback);
        }

        try {
            ToolResult result = toolExecutor.executeAuthorized(
                    toolCall, tool, confirmationContext);

            if (result.success()) {
                return result;
            }

            // No retry — error is returned to LLM which learns and adjusts strategy in next iteration
            log.warn("[L3-ReAct] Tool [{}] failed, returning error to LLM: {}", toolCall.toolName(), result.error());
            return result;
        } finally {
            if (cancellationToken != null) {
                cancellationToken.untrackCurrentThread();
                // Unregister CancellableTool's cancel callback
                if (cancelCallback != null) {
                    cancellationToken.removeCancelCallback(cancelCallback);
                }
            }
        }
    }

    private List<ToolSpecification> toToolSpecifications(List<ToolSpec> specs) {
        return specs.stream().map(s -> {
            JsonObjectSchema params = JsonSchemaConverter.toObjectSchema(s.parameters());
            return ToolSpecification.builder()
                    .name(s.name())
                    .description(s.description())
                    .parameters(params)
                    .build();
        }).toList();
    }

    private JsonNode parseArgs(String arguments) {
        try {
            return OBJECT_MAPPER.readTree(arguments);
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

    /**
     * Merge thinking params and tool specs into a single ChatRequestParameters.
     * <p>
     * LangChain4j ChatRequest 不允许同时调用 builder.parameters() 和 builder.toolSpecifications()。
     * 解决方案：把 toolSpecifications 放进 OpenAiChatRequestParameters 内部，只用 parameters() 一个入口。
     */
    private ChatRequestParameters buildMergedParams(ChatRequestParameters thinkingParams, List<ToolSpecification> toolSpecs) {
        boolean hasThinking = thinkingParams != null;
        boolean hasTools = !toolSpecs.isEmpty();

        if (!hasThinking && !hasTools) return null;

        if (hasThinking && hasTools) {
            // Merge: thinking customParameters + tools into one parameters object
            return OpenAiChatRequestParameters.builder()
                    .customParameters(((OpenAiChatRequestParameters) thinkingParams).customParameters())
                    .toolSpecifications(toolSpecs)
                    .build();
        }

        if (hasThinking) {
            return thinkingParams; // thinking only, no tools
        }

        // tools only, no thinking override
        return OpenAiChatRequestParameters.builder()
                .toolSpecifications(toolSpecs)
                .build();
    }

    private String truncate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private String confirmationOutcome(ReActStep.InspectionResult.InspectionStatus status) {
        return switch (status) {
            case CONFIRMATION_REQUIRED -> "confirmation_required";
            case CONFIRMATION_REJECTED -> "confirmation_rejected";
            case CONFIRMATION_EXPIRED -> "confirmation_expired";
            default -> "completed";
        };
    }

    // ── Shared helpers to deduplicate execute() / streamExecute() ──────────

    private enum RoundAction { CONTINUE, RETURN_RESULT }

    /** Build and register a final step (final_answer / summary). */
    private ReActStep buildFinalStep(int iteration, String answer, List<ReActStep> allSteps,
                                     List<ChatMessage> messages, AiMessage aiMessage,
                                     ReActListener listener) {
        // thought=null, observation=null: the answer lives only in AgentTrace.finalOutput.
        // Frontend does not consume step-level thought/observation — only finalOutput matters.
        ReActStep step = new ReActStep(iteration, null, "final_answer",
                List.of(), List.of(), null,
                new ReActStep.InspectionResult(ReActStep.InspectionResult.InspectionStatus.PASS, "complete"));
        allSteps.add(step);
        if (listener != null) listener.onStep(step);
        if (aiMessage != null) messages.add(aiMessage);
        return step;
    }

    /**
     * Execute tool calls and collect results.
     * Handles cancellation checks, onToolCallStart notification, artifact detection.
     */
    private record ToolExecutionOutput(List<ToolCall> toolCalls, List<ToolResult> toolResults,
                                       ReActResult earlyReturn) {}

    private ToolExecutionOutput executeToolCalls(List<ToolExecutionRequest> toolReqs,
                                                 List<ChatMessage> messages,
                                                 List<Artifact> allArtifacts,
                                                 ReActListener listener,
                                                 com.harness.core.model.CancellationToken cancellationToken,
                                                 ConfirmationExecutionContext confirmationContext) {
        List<ToolCall> toolCalls = new ArrayList<>();
        List<ToolResult> toolResults = new ArrayList<>();

        // Emit tool_call_created for ALL tools immediately when LLM response is received,
        // before any execution begins — so the frontend can show queued card states right away.
        if (listener != null) {
            for (ToolExecutionRequest toolReq : toolReqs) {
                listener.onToolCallCreated(toolReq.name(), toolReq.arguments());
            }
        }

        for (ToolExecutionRequest toolReq : toolReqs) {
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                return new ToolExecutionOutput(toolCalls, toolResults, cancelledResult(null, allArtifacts));
            }

            // Interactive execution emits this only after approval, immediately before tool.execute().
            if (listener != null && confirmationContext == null) {
                listener.onToolCallStart(toolReq.name(), toolReq.arguments());
            }

            JsonNode argsNode = parseArgs(toolReq.arguments());
            ToolCall tc = ToolCall.of(toolReq.name(), argsNode);
            toolCalls.add(tc);

            log.debug("[L3-ReAct] Executing tool: {}", tc.toolName());
            ToolResult result = executeWithRetry(tc, listener, cancellationToken, confirmationContext);
            toolResults.add(result);

            if (listener != null) {
                listener.onToolCallDone(tc.toolName(), result.success(), result.durationMs());
            }

            // Check cancellation after long-running tool execution
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                log.info("[L3-ReAct] Cancellation detected after tool execution: {}", tc.toolName());
                return new ToolExecutionOutput(toolCalls, toolResults, cancelledResult(null, allArtifacts));
            }

            if (result.success() && result.output() != null) {
                Tool tool = toolCatalog.get(tc.toolName());
                if (tool instanceof ArtifactProducingTool) {
                    parseArtifacts(result.output(), allArtifacts, OBJECT_MAPPER, listener);
                }
            }

            messages.add(ToolExecutionResultMessage.from(toolReq,
                    result.success() ? result.output() : "ERROR: " + result.error()));
        }

        return new ToolExecutionOutput(toolCalls, toolResults, null);
    }

    /**
     * Post-tool round processing: inspection → step → failure tracking → hints → adaptive reflection.
     * Returns CONTINUE to proceed to next iteration, or RETURN_RESULT with a ReActResult to exit.
     */
    private record RoundOutcome(RoundAction action, ReActResult result, boolean reflected,
                                ReActStep.InspectionResult.InspectionStatus inspectionStatus) {}

    private RoundOutcome processToolRound(int iteration, AiMessage aiMessage,
                                          List<ToolExecutionRequest> toolReqs,
                                          List<ToolCall> toolCalls, List<ToolResult> toolResults,
                                          List<ReActStep> allSteps, List<Artifact> allArtifacts,
                                          List<ChatMessage> messages, ReActListener listener,
                                          String userMessage) {

        ReActStep.InspectionResult inspection = inspector.inspect(toolCalls, toolResults);
        ReActStep step = new ReActStep(iteration,
                aiMessage.text(),
                toolReqs.stream().map(ToolExecutionRequest::name).reduce((a, b) -> a + "," + b).orElse(""),
                toolCalls, toolResults,
                toolResults.stream().map(r -> r.output() != null ? r.output() : "").reduce((a, b) -> a + "\n" + b).orElse(""),
                inspection);
        allSteps.add(step);
        if (listener != null) listener.onStep(step);

        if (inspection.status() == ReActStep.InspectionResult.InspectionStatus.CONFIRMATION_REQUIRED
                || inspection.status() == ReActStep.InspectionResult.InspectionStatus.CONFIRMATION_REJECTED
                || inspection.status() == ReActStep.InspectionResult.InspectionStatus.CONFIRMATION_EXPIRED) {
            return new RoundOutcome(
                    RoundAction.RETURN_RESULT,
                    new ReActResult(inspection.reason(), allSteps, allArtifacts),
                    false,
                    inspection.status());
        }

        // Inspection hint injection (for non-PASS statuses)
        if (inspection.status() != ReActStep.InspectionResult.InspectionStatus.PASS) {
            log.debug("[L3-ReAct] Inspection: status={}, reason={}", inspection.status(), inspection.reason());
            String hint = Inspector.buildInspectionHint(inspection);
            if (hint != null) {
                messages.add(UserMessage.from(hint));
                log.debug("[L3-ReAct] Injected inspection hint into context: {}", hint);
            }
        }

        // Adaptive reflection: signal-driven, per-tool tracking
        boolean reflected = false;
        AdaptiveReflector.ReflectionSignal signal = adaptiveReflector.shouldReflect(
                inspection, toolCalls, toolResults, allSteps, userMessage);
        if (signal != null) {
            messages.add(UserMessage.from(signal.prompt()));
            reflected = true;
            log.debug("[L3-ReAct] Adaptive reflection triggered at step {}", iteration);
        }

        return new RoundOutcome(RoundAction.CONTINUE, null, reflected, inspection.status());
    }

    /** Log token usage from response metadata and accumulate into the run trace. */
    private TokenUsage logTokenUsage(ChatResponse response, RunTrace trace, long llmMs) {
        if (response.metadata() != null && response.metadata().tokenUsage() != null) {
            var usage = response.metadata().tokenUsage();
            log.debug("[L3-ReAct] LLM call in {}ms, tokens: in={}, out={}",
                    llmMs, usage.inputTokenCount(), usage.outputTokenCount());
            trace.addTokens((long) usage.inputTokenCount() + usage.outputTokenCount());
            return usage;
        } else {
            log.debug("[L3-ReAct] LLM call in {}ms (no token usage metadata)", llmMs);
            return null;
        }
    }

    private ReActResult cancelledResult(List<ReActStep> allSteps, List<Artifact> allArtifacts,
                                         int totalToolCalls, int rounds, int reflectionChecks,
                                         long inputTokens, long outputTokens, int llmCalls, int toolRetries) {
        String output = allSteps.isEmpty() ? "Request cancelled" :
                allSteps.get(allSteps.size() - 1).observation();
        ReActLoopStats stats = new ReActLoopStats("cancelled", rounds, totalToolCalls, reflectionChecks,
                inputTokens, outputTokens, llmCalls, toolRetries);
        return new ReActResult(output, allSteps, allArtifacts, stats);
    }

    /** Overload for cancellation inside executeToolCalls where stats counters are not available. */
    private ReActResult cancelledResult(List<ReActStep> allSteps, List<Artifact> allArtifacts) {
        String output = allSteps == null || allSteps.isEmpty() ? "Request cancelled" :
                allSteps.get(allSteps.size() - 1).observation();
        return new ReActResult(output, allSteps != null ? allSteps : List.of(), allArtifacts);
    }

    /**
     * Parse artifacts from an ArtifactProducingTool's output.
     * Tries JSON format first ({"artifacts": [{id, name, mimeType, sizeBytes, downloadUrl}]}),
     * falls back to markdown link parsing (![name](/api/artifacts/{id}/preview)).
     */
    private void parseArtifacts(String toolOutput, List<Artifact> allArtifacts,
                                com.fasterxml.jackson.databind.ObjectMapper mapper,
                                ReActListener listener) {
        try {
            List<Artifact> parsed = new ArrayList<>();

            // Try JSON format first (PythonSandbox, VideoGeneration)
            try {
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(toolOutput);
                com.fasterxml.jackson.databind.JsonNode artifactsNode = root.get("artifacts");
                if (artifactsNode != null && artifactsNode.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode a : artifactsNode) {
                        String id = a.has("id") ? a.get("id").asText() : null;
                        String name = a.has("name") ? a.get("name").asText() : "artifact";
                        String mimeType = a.has("mimeType") ? a.get("mimeType").asText() : null;
                        long sizeBytes = a.has("sizeBytes") ? a.get("sizeBytes").asLong() : 0;
                        if (id != null) {
                            parsed.add(new Artifact(id, null, name,
                                    Artifact.inferType(mimeType), mimeType, sizeBytes, "",
                                    java.time.Instant.now()));
                        }
                    }
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // Not JSON — fall through to regex
            }

            // Fallback: parse markdown links ![name](/api/artifacts/{id}/preview)
            if (parsed.isEmpty()) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("!\\[(.+?)\\]\\(/api/artifacts/([^/]+)/preview\\)").matcher(toolOutput);
                while (m.find()) {
                    String name = m.group(1);
                    String id = m.group(2);
                    parsed.add(new Artifact(id, null, name,
                            Artifact.ArtifactType.IMAGE, "image/png", 0, "",
                            java.time.Instant.now()));
                }
            }

            if (!parsed.isEmpty()) {
                allArtifacts.addAll(parsed);
                log.info("[L3-ReAct] Collected {} artifacts from tool output", parsed.size());
                if (listener != null) {
                    listener.onArtifact(parsed);
                }
            }
        } catch (Exception e) {
            log.warn("[L3-ReAct] Failed to parse artifacts from tool output: {}", e.getMessage());
        }
    }

}
