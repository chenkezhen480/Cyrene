package com.harness.react;

import com.harness.provider.ChatModelProvider;
import com.harness.provider.LangChainJsonSchemaMapper;
import com.harness.provider.VisionModelProvider;
import com.harness.provider.VoiceModelProvider;
import com.harness.provider.impl.FallbackChatModel;
import com.harness.core.model.*;
import com.harness.core.runtime.RunTrace;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.exception.StructuredOutputException;
import com.harness.tool.Tool;
import com.harness.tool.ToolExecutor;
import com.harness.tool.ToolCatalog;
import com.harness.tool.builtin.StructuredOutputTool;
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
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Layer 3+4: ReAct loop engine, powered by LangChain4j 1.15.
 * Uses FallbackChatModel for transparent multimodal degradation.
 */
public class ReActEngine implements ReActLoop {

    private static final String TOOL_PLANNING_INSTRUCTION = """
            <tool_planning_phase>
            This request requires a separately streamed final answer. While tools are available,
            do not compose the user-facing final answer. Call the tools still required, or respond
            with only READY_FOR_FINAL when no more tools are needed.
            </tool_planning_phase>
            """;

    private static final String STRUCTURED_OUTPUT_INSTRUCTION = """
            <structured_output_phase>
            Complete any information-gathering tool calls first. Then call structured_output
            with the final response matching its argument schema. The structured_output call
            must be the only tool call in that round. Do not return the final value as prose.
            </structured_output_phase>
            """;

    private static final Logger log = LoggerFactory.getLogger(ReActEngine.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private final ChatModelProvider chatModelProvider;
    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ToolCatalog toolCatalog;
    private final ToolExecutor toolExecutor;
    private final Inspector inspector;
    private final AdaptiveReflector adaptiveReflector;
    private final int maxIterations;
    private final long llmTimeoutSeconds;
    private final FinalResponseGenerator finalResponseGenerator;

    /**
     * @param maxIterationsOverride if > 0, overrides the global HARNESS_REACT_MAX_ITERATIONS setting
     */
    ReActEngine(ChatModelProvider chatModelProvider, ToolCatalog toolCatalog, ToolExecutor toolExecutor,
                VisionModelProvider visionProvider, VoiceModelProvider voiceProvider,
                int maxIterationsOverride) {
        this(
                chatModelProvider,
                toolCatalog,
                toolExecutor,
                visionProvider,
                voiceProvider,
                maxIterationsOverride,
                defaultFinalResponseGenerator(chatModelProvider));
    }

    ReActEngine(ChatModelProvider chatModelProvider, ToolCatalog toolCatalog, ToolExecutor toolExecutor,
                VisionModelProvider visionProvider, VoiceModelProvider voiceProvider,
                int maxIterationsOverride, FinalResponseGenerator finalResponseGenerator) {
        this.chatModelProvider = chatModelProvider;
        ChatModel rawModel = chatModelProvider.chatModel();
        if (visionProvider != null || voiceProvider != null) {
            this.chatModel = new FallbackChatModel(
                    rawModel,
                    visionProvider,
                    voiceProvider,
                    chatModelProvider.modalCapabilities(),
                    chatModelProvider.modelName());
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
        this.llmTimeoutSeconds = timeoutSeconds(chatModelProvider);
        this.finalResponseGenerator = java.util.Objects.requireNonNull(
                finalResponseGenerator, "finalResponseGenerator");
    }

    private static FinalResponseGenerator defaultFinalResponseGenerator(
            ChatModelProvider chatModelProvider) {
        return new FinalResponseGenerator(
                chatModelProvider, timeoutSeconds(chatModelProvider));
    }

    private static int timeoutSeconds(ChatModelProvider provider) {
        int configured = provider.timeoutSeconds();
        return configured > 0 ? configured : 300;
    }

    @Override
    public ReActResult execute(ReActRequest request) {
        return execute(
                request.systemPrompt(), request.userMessage(), request.historyMessages(), request.trace(),
                request.listener(), request.cancellationToken(), request.enableThinking(),
                request.confirmationContext(), request.finalOutputContract());
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
                                ConfirmationExecutionContext confirmationContext,
                                FinalOutputContract finalOutputContract) {
        long loopStart = System.currentTimeMillis();
        log.debug("[L3-ReAct] Starting ReAct loop: maxIterations={}, historyMessages={}, tools={}, thinking={}",
                maxIterations, historyMessages.size(), toolCatalog.size(), enableThinking);

        boolean structuredOutput = finalOutputContract instanceof FinalOutputContract.JsonSchema;

        List<ToolSpecification> toolSpecs = toToolSpecifications(toolCatalog.getAll());
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(structuredOutput
                ? systemPrompt + "\n\n" + STRUCTURED_OUTPUT_INSTRUCTION
                : systemPrompt));
        messages.addAll(historyMessages);
        messages.add(UserMessage.from(userMessage));

        List<ReActStep> allSteps = new ArrayList<>();
        List<Artifact> allArtifacts = new ArrayList<>();
        ChatRequestParameters finalRequestParameters =
                buildRequestParameters(enableThinking, List.of());
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
                    throw new CancellationException("Request cancelled");
                }

                ChatRequestParameters mergedParams =
                        buildRequestParameters(enableThinking, toolSpecs);
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
                        throw new CancellationException("Request cancelled");
                    }
                    throw e;
                } finally {
                    if (cancellationToken != null) cancellationToken.untrackCurrentThread();
                }
                if (cancellationToken != null && cancellationToken.isCancelled()) {
                    log.info("[L3-ReAct] Cancellation detected after LLM call at iteration {}", i);
                    throw new CancellationException("Request cancelled");
                }

                AiMessage aiMessage = response.aiMessage();
                llmCalls++;
                ModelUsage usage = recordModelUsage(
                        response,
                        trace,
                        System.currentTimeMillis() - llmStart,
                        messages,
                        toolSpecs);
                totalInputTokens += observedTokens(usage.inputTokens());
                totalOutputTokens += observedTokens(usage.outputTokens());

                // Final answer (no tool calls)
                if (aiMessage.toolExecutionRequests() == null || aiMessage.toolExecutionRequests().isEmpty()) {
                    if (structuredOutput) {
                        throw new StructuredOutputException(
                                StructuredOutputException.Code.STRUCTURED_OUTPUT_EMPTY,
                                "Model did not submit the final response through structured_output");
                    }
                    String answer = aiMessage.text();
                    long totalMs = System.currentTimeMillis() - loopStart;
                    log.info("[L3-ReAct] Finished in {}ms, steps={}, outputLen={}", totalMs, allSteps.size(), answer != null ? answer.length() : 0);
                    buildFinalStep(i, answer, allSteps, messages, aiMessage, listener);
                    ReActLoopStats stats = new ReActLoopStats("completed", i, totalToolCalls, reflectionChecks,
                            totalInputTokens, totalOutputTokens, llmCalls, toolRetries);
                    return new ReActResult(answer, allSteps, allArtifacts, stats);
                }

                // Tool execution round
                List<ToolExecutionRequest> toolReqs = normalizeToolRequests(
                        aiMessage.toolExecutionRequests());
                validateStructuredOutputRound(toolReqs, structuredOutput);
                if (!toolReqs.equals(aiMessage.toolExecutionRequests())) {
                    aiMessage = AiMessage.from(
                            aiMessage.text() != null ? aiMessage.text() : "", toolReqs);
                }
                messages.add(aiMessage);
                totalToolCalls += toolReqs.size();
                log.debug("[L3-ReAct] LLM requested {} tool calls", toolReqs.size());
                if (aiMessage.text() != null && !aiMessage.text().isBlank()) {
                    log.debug("[L3-ReAct] LLM reasoning: {}", truncate(aiMessage.text()));
                }

                ToolExecutionOutput toolOutput = executeToolCalls(
                        toolReqs, messages, allArtifacts, listener, cancellationToken, confirmationContext);

                if (structuredOutput && isStructuredOutputRound(toolReqs)) {
                    buildStructuredOutputStep(
                            i, aiMessage, toolOutput, allSteps, listener);
                    ToolResult submitted = toolOutput.toolResults().get(0);
                    if (!submitted.success()) {
                        throw new StructuredOutputException(
                                StructuredOutputException.Code.STRUCTURED_OUTPUT_SCHEMA_MISMATCH,
                                "structured_output rejected the submitted value",
                                java.util.Map.of("error", submitted.error() != null
                                        ? submitted.error() : "Unknown validation error"));
                    }
                    ReActLoopStats stats = new ReActLoopStats(
                            "completed", i, totalToolCalls, reflectionChecks,
                            totalInputTokens, totalOutputTokens, llmCalls, toolRetries);
                    return new ReActResult(
                            submitted.output(), allSteps, allArtifacts, stats);
                }

                // Post-tool processing: inspection, hints, adaptive reflection
                RoundOutcome outcome = processToolRound(i, aiMessage, toolReqs,
                        toolOutput.toolCalls(), toolOutput.toolResults(),
                        allSteps, allArtifacts, messages, listener, userMessage);
                if (outcome.reflected()) reflectionChecks++;
                if (outcome.inspectionStatus() == ReActStep.InspectionResult.InspectionStatus.TOOL_ERROR
                        || outcome.inspectionStatus() == ReActStep.InspectionResult.InspectionStatus.LOOP_DETECTED) {
                    toolRetries++;
                }
                if (outcome.action() == RoundAction.GENERATE_FINAL) {
                    if (structuredOutput) {
                        throw new StructuredOutputException(
                                StructuredOutputException.Code.STRUCTURED_OUTPUT_EMPTY,
                                "Tool failure limit reached before structured_output was submitted");
                    }
                    GeneratedFinalResponse finalResponse = generateBlockingFinalResponse(
                            systemPrompt, messages, finalRequestParameters,
                            cancellationToken, trace);
                    AiMessage finalMessage = finalResponse.response().aiMessage();
                    llmCalls++;
                    ModelUsage finalUsage = finalResponse.usage();
                    totalInputTokens += observedTokens(finalUsage.inputTokens());
                    totalOutputTokens += observedTokens(finalUsage.outputTokens());
                    buildFinalStep(
                            i + 1, finalMessage.text(), allSteps, messages, finalMessage, listener);
                    ReActLoopStats stats = new ReActLoopStats(
                            "tool_failure_limit", i, totalToolCalls, reflectionChecks,
                            totalInputTokens, totalOutputTokens, llmCalls, toolRetries);
                    return new ReActResult(finalMessage.text(), allSteps, allArtifacts, stats);
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

            if (structuredOutput) {
                throw new StructuredOutputException(
                        StructuredOutputException.Code.STRUCTURED_OUTPUT_EMPTY,
                        "Iteration limit reached before structured_output was submitted");
            }
            GeneratedFinalResponse finalResponse = generateBlockingFinalResponse(
                    systemPrompt, messages, finalRequestParameters,
                    cancellationToken, trace);
            AiMessage finalMessage = finalResponse.response().aiMessage();
            llmCalls++;
            ModelUsage finalUsage = finalResponse.usage();
            totalInputTokens += observedTokens(finalUsage.inputTokens());
            totalOutputTokens += observedTokens(finalUsage.outputTokens());
            buildFinalStep(
                    maxIterations + 1, finalMessage.text(), allSteps, messages, finalMessage, listener);
            long totalMs = System.currentTimeMillis() - loopStart;
            log.warn("[L3-ReAct] Reached max iterations ({}), generated a tool-free final answer",
                    maxIterations);
            log.info("[L3-ReAct] Finished in {}ms, steps={}, artifacts={}", totalMs, allSteps.size(), allArtifacts.size());
            ReActLoopStats stats = new ReActLoopStats("max_iterations", maxIterations, totalToolCalls, reflectionChecks,
                    totalInputTokens, totalOutputTokens, llmCalls, toolRetries);
            return new ReActResult(finalMessage.text(), allSteps, allArtifacts, stats);
        } finally {
            ReActStep.clearCurrentSteps();
        }
    }

    /**
     * Streaming variant of execute(). Uses StreamingChatModel for real-time token output.
     * Falls back to blocking execute() if no streaming model is available.
     */
    @Override
    public ReActResult streamExecute(ReActRequest request) {
        if (request.finalOutputContract() instanceof FinalOutputContract.JsonSchema) {
            return execute(request);
        }
        return streamExecute(
                request.systemPrompt(), request.userMessage(), request.historyMessages(), request.trace(),
                request.listener(), request.cancellationToken(), request.enableThinking(),
                request.confirmationContext(), request.finalOutputContract());
    }

    private ReActResult streamExecute(String systemPrompt, String userMessage, List<ChatMessage> historyMessages,
                                      RunTrace trace, ReActListener listener,
                                com.harness.core.model.CancellationToken cancellationToken,
                                Boolean enableThinking,
                                ConfirmationExecutionContext confirmationContext,
                                FinalOutputContract finalOutputContract) {
        if (streamingChatModel == null) {
            log.warn("[L3-ReAct] Falling back to blocking mode (streaming unavailable)");
            return execute(systemPrompt, userMessage, historyMessages, trace, listener,
                    cancellationToken, enableThinking, confirmationContext,
                    new FinalOutputContract.Text());
        }

        long loopStart = System.currentTimeMillis();
        log.debug("[L3-ReAct] Starting STREAMING ReAct loop: maxIterations={}, tools={}",
                maxIterations, toolCatalog.size());

        List<ToolSpecification> toolSpecs = toToolSpecifications(toolCatalog.getAll());
        boolean guardedFinalStreaming = !toolSpecs.isEmpty();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(guardedFinalStreaming
                ? systemPrompt + "\n\n" + TOOL_PLANNING_INSTRUCTION
                : systemPrompt));
        messages.addAll(historyMessages);
        messages.add(UserMessage.from(userMessage));

        List<ReActStep> allSteps = new ArrayList<>();
        List<Artifact> allArtifacts = new ArrayList<>();
        ChatRequestParameters finalRequestParameters =
                buildRequestParameters(enableThinking, List.of());
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
                    throw new CancellationException("Request cancelled");
                }

                ChatRequestParameters mergedParams =
                        buildRequestParameters(enableThinking, toolSpecs);
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
                        throw new CancellationException("Request cancelled");
                    }
                    Throwable cause = e instanceof java.util.concurrent.ExecutionException
                            && e.getCause() != null ? e.getCause() : e;
                    log.error("[L3-ReAct] Streaming LLM call failed: {}", cause.getMessage());
                    throw cause instanceof RuntimeException ? (RuntimeException) cause : new RuntimeException(cause);
                } finally {
                    if (cancellationToken != null) cancellationToken.untrackCurrentThread();
                }
                if (cancellationToken != null && cancellationToken.isCancelled()) {
                    log.info("[L3-ReAct] Cancellation detected after streaming LLM call at iteration {}", i);
                    throw new CancellationException("Request cancelled");
                }

                AiMessage aiMessage = response.aiMessage();
                llmCalls++;
                ModelUsage usage = recordModelUsage(
                        response,
                        trace,
                        System.currentTimeMillis() - llmStart,
                        messages,
                        toolSpecs);
                totalInputTokens += observedTokens(usage.inputTokens());
                totalOutputTokens += observedTokens(usage.outputTokens());

                // Final answer (no tool calls)
                if (aiMessage.toolExecutionRequests() == null || aiMessage.toolExecutionRequests().isEmpty()) {
                    if (guardedFinalStreaming) {
                        GeneratedFinalResponse finalResponse = generateFinalResponse(
                                systemPrompt,
                                messages,
                                finalRequestParameters,
                                listener,
                                cancellationToken,
                                trace);
                        response = finalResponse.response();
                        aiMessage = response.aiMessage();
                        llmCalls++;
                        ModelUsage finalUsage = finalResponse.usage();
                        totalInputTokens += observedTokens(finalUsage.inputTokens());
                        totalOutputTokens += observedTokens(finalUsage.outputTokens());
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
                List<ToolExecutionRequest> toolReqs = normalizeToolRequests(
                        aiMessage.toolExecutionRequests());
                if (!toolReqs.equals(aiMessage.toolExecutionRequests())) {
                    aiMessage = AiMessage.from(
                            aiMessage.text() != null ? aiMessage.text() : "", toolReqs);
                }
                messages.add(aiMessage);
                totalToolCalls += toolReqs.size();
                log.debug("[L3-ReAct] Streaming: LLM requested {} tool calls", toolReqs.size());

                ToolExecutionOutput toolOutput = executeToolCalls(
                        toolReqs, messages, allArtifacts, listener, cancellationToken, confirmationContext);

                // Post-tool processing: inspection, hints, adaptive reflection
                RoundOutcome outcome = processToolRound(i, aiMessage, toolReqs,
                        toolOutput.toolCalls(), toolOutput.toolResults(),
                        allSteps, allArtifacts, messages, listener, userMessage);
                if (outcome.reflected()) reflectionChecks++;
                if (outcome.inspectionStatus() == ReActStep.InspectionResult.InspectionStatus.TOOL_ERROR
                        || outcome.inspectionStatus() == ReActStep.InspectionResult.InspectionStatus.LOOP_DETECTED) {
                    toolRetries++;
                }
                if (outcome.action() == RoundAction.GENERATE_FINAL) {
                    GeneratedFinalResponse finalResponse = generateFinalResponse(
                            systemPrompt,
                            messages,
                            finalRequestParameters,
                            listener,
                            cancellationToken,
                            trace);
                    AiMessage finalMessage = finalResponse.response().aiMessage();
                    llmCalls++;
                    ModelUsage finalUsage = finalResponse.usage();
                    totalInputTokens += observedTokens(finalUsage.inputTokens());
                    totalOutputTokens += observedTokens(finalUsage.outputTokens());
                    buildFinalStep(
                            i + 1, finalMessage.text(), allSteps, messages, finalMessage, listener);
                    ReActLoopStats stats = new ReActLoopStats(
                            "tool_failure_limit", i, totalToolCalls, reflectionChecks,
                            totalInputTokens, totalOutputTokens, llmCalls, toolRetries);
                    return new ReActResult(finalMessage.text(), allSteps, allArtifacts, stats);
                }
                if (outcome.action() == RoundAction.RETURN_RESULT) {
                    ReActResult r = outcome.result();
                    if (guardedFinalStreaming
                            && shouldGenerateFinalResponse(outcome.inspectionStatus())) {
                        GeneratedFinalResponse finalResponse = generateFinalResponse(
                                systemPrompt,
                                messages,
                                finalRequestParameters,
                                listener,
                                cancellationToken,
                                trace);
                        ChatResponse finalChatResponse = finalResponse.response();
                        AiMessage finalAiMessage = finalChatResponse.aiMessage();
                        llmCalls++;
                        ModelUsage finalUsage = finalResponse.usage();
                        totalInputTokens += observedTokens(finalUsage.inputTokens());
                        totalOutputTokens += observedTokens(finalUsage.outputTokens());
                        buildFinalStep(
                                i, finalAiMessage.text(), allSteps, messages, finalAiMessage, listener);
                        String loopOutcome = confirmationOutcome(outcome.inspectionStatus());
                        ReActLoopStats stats = new ReActLoopStats(
                                loopOutcome,
                                i,
                                totalToolCalls,
                                reflectionChecks,
                                totalInputTokens,
                                totalOutputTokens,
                                llmCalls,
                                toolRetries);
                        return new ReActResult(
                                finalAiMessage.text(), allSteps, allArtifacts, stats);
                    }
                    if (r.loopStats() == null) {
                        String loopOutcome = confirmationOutcome(outcome.inspectionStatus());
                        ReActLoopStats stats = new ReActLoopStats(loopOutcome, i, totalToolCalls, reflectionChecks,
                                totalInputTokens, totalOutputTokens, llmCalls, toolRetries);
                        return new ReActResult(r.output(), r.steps(), r.artifacts(), stats);
                    }
                    return r;
                }
            }

            GeneratedFinalResponse finalResponse = generateFinalResponse(
                    systemPrompt,
                    messages,
                    finalRequestParameters,
                    listener,
                    cancellationToken,
                    trace);
            AiMessage finalMessage = finalResponse.response().aiMessage();
            llmCalls++;
            ModelUsage finalUsage = finalResponse.usage();
            totalInputTokens += observedTokens(finalUsage.inputTokens());
            totalOutputTokens += observedTokens(finalUsage.outputTokens());
            buildFinalStep(
                    maxIterations + 1, finalMessage.text(), allSteps, messages, finalMessage, listener);
            long totalMs = System.currentTimeMillis() - loopStart;
            log.warn("[L3-ReAct] Streaming: reached max iterations ({}), generated a tool-free final answer",
                    maxIterations);
            log.info("[L3-ReAct] Finished in {}ms, steps={}, artifacts={}", totalMs, allSteps.size(), allArtifacts.size());
            ReActLoopStats stats = new ReActLoopStats("max_iterations", maxIterations, totalToolCalls, reflectionChecks,
                    totalInputTokens, totalOutputTokens, llmCalls, toolRetries);
            return new ReActResult(finalMessage.text(), allSteps, allArtifacts, stats);
        } finally {
            ReActStep.clearCurrentSteps();
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
            JsonObjectSchema params;
            try {
                params = LangChainJsonSchemaMapper.toObjectSchema(s.parameters());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Invalid parameter schema for tool '" + s.name() + "': " + e.getMessage(), e);
            }
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

    private ChatRequestParameters buildRequestParameters(
            Boolean enableThinking,
            List<ToolSpecification> toolSpecifications
    ) {
        return chatModelProvider.planningRequestParameters(
                enableThinking, toolSpecifications);
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

    private static boolean shouldGenerateFinalResponse(
            ReActStep.InspectionResult.InspectionStatus status) {
        return status == ReActStep.InspectionResult.InspectionStatus.CONFIRMATION_REJECTED
                || status == ReActStep.InspectionResult.InspectionStatus.CONFIRMATION_EXPIRED;
    }

    private record GeneratedFinalResponse(ChatResponse response, ModelUsage usage) {}

    private GeneratedFinalResponse generateFinalResponse(
            String systemPrompt,
            List<ChatMessage> messages,
            ChatRequestParameters requestParameters,
            ReActListener listener,
            com.harness.core.model.CancellationToken cancellationToken,
            RunTrace trace) {
        long startedAt = System.currentTimeMillis();
        FinalResponseGenerator.Result generated = finalResponseGenerator.generateStreaming(
                systemPrompt,
                messages,
                requestParameters,
                listener,
                cancellationToken);
        ModelUsage usage = recordModelUsage(
                generated.response(),
                trace,
                System.currentTimeMillis() - startedAt,
                generated.messages(),
                List.of());
        return new GeneratedFinalResponse(generated.response(), usage);
    }

    private GeneratedFinalResponse generateBlockingFinalResponse(
            String systemPrompt,
            List<ChatMessage> messages,
            ChatRequestParameters requestParameters,
            com.harness.core.model.CancellationToken cancellationToken,
            RunTrace trace) {
        long startedAt = System.currentTimeMillis();
        FinalResponseGenerator.Result generated = finalResponseGenerator.generateBlocking(
                systemPrompt,
                messages,
                requestParameters,
                cancellationToken);
        ModelUsage usage = recordModelUsage(
                generated.response(),
                trace,
                System.currentTimeMillis() - startedAt,
                generated.messages(),
                List.of());
        return new GeneratedFinalResponse(generated.response(), usage);
    }

    // ── Shared helpers to deduplicate execute() / streamExecute() ──────────

    private enum RoundAction { CONTINUE, GENERATE_FINAL, RETURN_RESULT }

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
    private record ToolExecutionOutput(List<ToolCall> toolCalls, List<ToolResult> toolResults) {}

    private static boolean isStructuredOutputRound(List<ToolExecutionRequest> requests) {
        return requests.size() == 1
                && StructuredOutputTool.TOOL_NAME.equals(requests.get(0).name());
    }

    private static void validateStructuredOutputRound(
            List<ToolExecutionRequest> requests,
            boolean structuredOutput
    ) {
        if (!structuredOutput) {
            return;
        }
        boolean containsSubmission = requests.stream()
                .anyMatch(request -> StructuredOutputTool.TOOL_NAME.equals(request.name()));
        if (containsSubmission && !isStructuredOutputRound(requests)) {
            throw new StructuredOutputException(
                    StructuredOutputException.Code.STRUCTURED_OUTPUT_SCHEMA_MISMATCH,
                    "structured_output must be the only tool call in its round");
        }
    }

    private void buildStructuredOutputStep(
            int iteration,
            AiMessage aiMessage,
            ToolExecutionOutput output,
            List<ReActStep> allSteps,
            ReActListener listener
    ) {
        ReActStep step = new ReActStep(
                iteration,
                aiMessage.text(),
                StructuredOutputTool.TOOL_NAME,
                output.toolCalls(),
                output.toolResults(),
                output.toolResults().get(0).output(),
                new ReActStep.InspectionResult(
                        ReActStep.InspectionResult.InspectionStatus.PASS,
                        "structured output submitted"));
        allSteps.add(step);
        if (listener != null) {
            listener.onStep(step);
        }
    }

    private ToolExecutionOutput executeToolCalls(List<ToolExecutionRequest> toolReqs,
                                                 List<ChatMessage> messages,
                                                 List<Artifact> allArtifacts,
                                                 ReActListener listener,
                                                 com.harness.core.model.CancellationToken cancellationToken,
                                                 ConfirmationExecutionContext confirmationContext) {
        List<ToolCall> toolCalls = new ArrayList<>();
        List<ToolResult> toolResults = new ArrayList<>();
        List<PlannedToolCall> plannedCalls = toolReqs.stream()
                .map(this::prepareToolCall)
                .toList();

        // Emit tool_call_created for ALL tools immediately when LLM response is received,
        // before any execution begins — so the frontend can show queued card states right away.
        if (listener != null) {
            for (PlannedToolCall plannedCall : plannedCalls) {
                listener.onToolCallCreated(
                        plannedCall.toolCall().id(),
                        plannedCall.toolCall().toolName(),
                        plannedCall.arguments());
            }
        }

        for (int callIndex = 0; callIndex < plannedCalls.size(); callIndex++) {
            PlannedToolCall plannedCall = plannedCalls.get(callIndex);
            ToolExecutionRequest toolReq = plannedCall.request();
            ToolCall tc = plannedCall.toolCall();
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                emitCancelledCalls(plannedCalls.subList(callIndex, plannedCalls.size()), listener);
                throw new CancellationException("Request cancelled");
            }

            // Interactive execution emits this only after approval, immediately before tool.execute().
            if (listener != null && confirmationContext == null) {
                listener.onToolCallStart(tc.id(), tc.toolName(), plannedCall.arguments());
            }

            toolCalls.add(tc);

            log.debug("[L3-ReAct] Executing tool: {}", tc.toolName());
            ToolResult result = executeWithRetry(tc, listener, cancellationToken, confirmationContext);
            toolResults.add(result);

            if (listener != null) {
                ToolCallStatus status = terminalStatus(result, cancellationToken);
                listener.onToolCallDone(
                        tc.id(),
                        tc.toolName(),
                        status,
                        result.durationMs(),
                        status == ToolCallStatus.SUCCEEDED ? "" : errorSummary(result));
            }

            // Check cancellation after long-running tool execution
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                log.info("[L3-ReAct] Cancellation detected after tool execution: {}", tc.toolName());
                emitCancelledCalls(
                        plannedCalls.subList(callIndex + 1, plannedCalls.size()), listener);
                throw new CancellationException("Request cancelled");
            }

            if (result.success() && result.content() != null) {
                if (!result.content().artifacts().isEmpty()) {
                    allArtifacts.addAll(result.content().artifacts());
                    if (listener != null) {
                        listener.onArtifact(result.content().artifacts());
                    }
                }
                if (result.content().json() != null && listener != null) {
                    listener.onStructuredOutput(result.content().json());
                }
            }

            messages.add(ToolExecutionResultMessage.from(toolReq,
                    result.success() ? result.output() : "ERROR: " + result.error()));
        }

        return new ToolExecutionOutput(toolCalls, toolResults);
    }

    private record PlannedToolCall(
            ToolExecutionRequest request, ToolCall toolCall, String arguments) {}

    private PlannedToolCall prepareToolCall(ToolExecutionRequest request) {
        ToolExecutionRequest normalizedRequest = normalizeToolRequest(request);
        String toolCallId = normalizedRequest.id();
        String arguments = normalizedRequest.arguments();
        return new PlannedToolCall(
                normalizedRequest,
                new ToolCall(toolCallId, normalizedRequest.name(), parseArgs(arguments)),
                arguments);
    }

    private static List<ToolExecutionRequest> normalizeToolRequests(
            List<ToolExecutionRequest> requests) {
        return requests.stream().map(ReActEngine::normalizeToolRequest).toList();
    }

    private static ToolExecutionRequest normalizeToolRequest(ToolExecutionRequest request) {
        String toolCallId = request.id();
        String arguments = request.arguments() != null ? request.arguments() : "null";
        if (toolCallId != null && !toolCallId.isBlank()
                && Objects.equals(arguments, request.arguments())) {
            return request;
        }
        if (toolCallId == null || toolCallId.isBlank()) {
            toolCallId = java.util.UUID.randomUUID().toString();
        }
        return ToolExecutionRequest.builder()
                .id(toolCallId)
                .name(request.name())
                .arguments(arguments)
                .build();
    }

    private void emitCancelledCalls(List<PlannedToolCall> calls, ReActListener listener) {
        if (listener == null) {
            return;
        }
        for (PlannedToolCall call : calls) {
            listener.onToolCallDone(
                    call.toolCall().id(),
                    call.toolCall().toolName(),
                    ToolCallStatus.CANCELLED,
                    0,
                    "Cancelled");
        }
    }

    private ToolCallStatus terminalStatus(
            ToolResult result,
            com.harness.core.model.CancellationToken cancellationToken) {
        if ((cancellationToken != null && cancellationToken.isCancelled())
                || result.status() == ToolResult.ResultStatus.CONFIRMATION_CANCELLED) {
            return ToolCallStatus.CANCELLED;
        }
        return result.success() ? ToolCallStatus.SUCCEEDED : ToolCallStatus.FAILED;
    }

    private String errorSummary(ToolResult result) {
        String error = result.error();
        if (error == null || error.isBlank()) {
            return "Tool execution failed";
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
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

        if (inspection.status() == ReActStep.InspectionResult.InspectionStatus.CONFIRMATION_REQUIRED
                || inspection.status() == ReActStep.InspectionResult.InspectionStatus.CONFIRMATION_REJECTED
                || inspection.status() == ReActStep.InspectionResult.InspectionStatus.CONFIRMATION_EXPIRED) {
            if (listener != null) listener.onStep(step);
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
            if (signal.hardLimit()) {
                ReActStep.InspectionResult hardLimitInspection =
                        new ReActStep.InspectionResult(
                                ReActStep.InspectionResult.InspectionStatus.LOOP_DETECTED,
                                signal.prompt());
                step = new ReActStep(
                        step.stepNumber(), step.thought(), step.action(),
                        step.toolCalls(), step.toolResults(), step.observation(),
                        hardLimitInspection);
                allSteps.set(allSteps.size() - 1, step);
                if (listener != null) listener.onStep(step);
                log.warn("[L3-ReAct] Tool failure hard limit reached at step {}", iteration);
                return new RoundOutcome(
                        RoundAction.GENERATE_FINAL,
                        null,
                        false,
                        hardLimitInspection.status());
            }
            reflected = true;
            log.debug("[L3-ReAct] Adaptive reflection triggered at step {}", iteration);
        }

        if (listener != null) listener.onStep(step);
        return new RoundOutcome(RoundAction.CONTINUE, null, reflected, inspection.status());
    }

    /** Record provider-neutral usage and prompt-cache diagnostics for one model call. */
    private ModelUsage recordModelUsage(
            ChatResponse response,
            RunTrace trace,
            long llmMs,
            List<ChatMessage> messages,
            List<ToolSpecification> toolSpecifications
    ) {
        ModelUsage usage = chatModelProvider.modelUsage(response, llmMs)
                .withPromptContext(
                        promptPrefixFingerprint(messages, toolSpecifications),
                        toolCatalog.version());
        long inputTokens = observedTokens(usage.inputTokens());
        long outputTokens = observedTokens(usage.outputTokens());
        trace.addTokens(inputTokens + outputTokens);
        trace.recordModelUsage(usage);
        log.debug(
                "[L3-ReAct] LLM call in {}ms, tokens: in={}, cached={}, out={}, reasoning={}",
                llmMs,
                usage.inputTokens(),
                usage.cachedInputTokens(),
                usage.outputTokens(),
                usage.reasoningTokens());
        return usage;
    }

    private static long observedTokens(Long value) {
        return value != null ? value : 0;
    }

    private static String promptPrefixFingerprint(
            List<ChatMessage> messages,
            List<ToolSpecification> toolSpecifications
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String systemPrompt = SystemMessage.findFirst(messages)
                    .map(SystemMessage::text)
                    .orElse("");
            digest.update(systemPrompt.getBytes(StandardCharsets.UTF_8));
            for (ToolSpecification specification : toolSpecifications) {
                digest.update((byte) 0);
                digest.update(specification.toJson().getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

}
