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
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
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
import java.util.concurrent.CompletableFuture;

/**
 * Layer 3+4: ReAct loop engine, powered by LangChain4j 1.15.
 * Uses FallbackChatModel for transparent multimodal degradation.
 */
public class ReActEngine {

    private static final Logger log = LoggerFactory.getLogger(ReActEngine.class);

    private static final int TOOL_MAX_RETRIES = 3;

    private final ChatModel chatModel;
    private final ChatModel chatModelNoThinking;
    private final StreamingChatModel streamingChatModel;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final Inspector inspector;
    private final int maxIterations;
    private final boolean stopOnToolError;

    public ReActEngine(ChatModelProvider chatModelProvider, ToolRegistry toolRegistry, ToolExecutor toolExecutor) {
        this(chatModelProvider, toolRegistry, toolExecutor, null, null);
    }

    public ReActEngine(ChatModelProvider chatModelProvider, ToolRegistry toolRegistry, ToolExecutor toolExecutor,
                       VisionModelProvider visionProvider, VoiceModelProvider voiceProvider) {
        ChatModel rawModel = chatModelProvider.chatModel();
        ChatModel rawNoThinking = chatModelProvider.chatModelNoThinking();
        if (visionProvider != null || voiceProvider != null) {
            this.chatModel = new FallbackChatModel(rawModel, visionProvider, voiceProvider, chatModelProvider.modelName());
            this.chatModelNoThinking = new FallbackChatModel(rawNoThinking, visionProvider, voiceProvider, chatModelProvider.modelName());
        } else {
            this.chatModel = rawModel;
            this.chatModelNoThinking = rawNoThinking;
        }
        this.streamingChatModel = chatModelProvider.streamingModel();
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.inspector = new Inspector();
        EnvConfig cfg = EnvConfig.get();
        this.maxIterations = cfg.getInt(EnvKey.REACT_MAX_ITERATIONS, 10);
        this.stopOnToolError = cfg.getBool(EnvKey.REACT_STOP_ON_TOOL_ERROR, false);
    }

    private ChatModel selectModel(Boolean enableThinking) {
        if (enableThinking != null && !enableThinking) return chatModelNoThinking;
        return chatModel;
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
        ChatModel activeModel = selectModel(enableThinking);
        log.info("[L3-ReAct] Starting ReAct loop: maxIterations={}, historyMessages={}, tools={}, thinking={}",
                maxIterations, historyMessages.size(), toolRegistry.size(), enableThinking);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.addAll(historyMessages);
        messages.add(UserMessage.from(userMessage));

        List<ToolSpecification> toolSpecs = toToolSpecifications(toolRegistry.getAll());
        List<ReActStep> allSteps = new ArrayList<>();

        for (int i = 1; i <= maxIterations; i++) {
            log.info("[L3-ReAct] Iteration {}/{}", i, maxIterations);

            // Check for cancellation between iterations
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                log.info("[L3-ReAct] Cancellation detected at iteration {}, stopping", i);
                String cancelledOutput = allSteps.isEmpty() ? "Request cancelled" :
                        allSteps.get(allSteps.size() - 1).observation();
                return new ReActResult(cancelledOutput, allSteps);
            }

            // Lightweight context cleanup: remove tool blocks if context is getting large
            if (i > 2) {
                stripToolMessages(messages);
            }

            ChatRequest.Builder reqBuilder = ChatRequest.builder().messages(messages);
            if (!toolSpecs.isEmpty()) {
                reqBuilder.toolSpecifications(toolSpecs);
            }

            long llmStart = System.currentTimeMillis();
            ChatResponse response;
            if (cancellationToken != null) cancellationToken.trackCurrentThread();
            try {
                response = activeModel.chat(reqBuilder.build());
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
            log.info("[L3-ReAct] LLM requested {} tool calls", toolReqs.size());
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

                log.info("[L3-ReAct] Executing tool: {}", tc.toolName());
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

            if (inspection.status() != ReActStep.InspectionResult.InspectionStatus.PASS) {
                log.info("[L3-ReAct] Inspection result: status={}, reason={}", inspection.status(), inspection.reason());
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
        if (streamingChatModel == null || (enableThinking != null && !enableThinking)) {
            log.warn("[L3-ReAct] Falling back to blocking mode (streaming unavailable or thinking disabled)");
            return execute(systemPrompt, userMessage, historyMessages, traceBuilder, listener, cancellationToken, enableThinking);
        }

        long loopStart = System.currentTimeMillis();
        log.info("[L3-ReAct] Starting STREAMING ReAct loop: maxIterations={}, tools={}",
                maxIterations, toolRegistry.size());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.addAll(historyMessages);
        messages.add(UserMessage.from(userMessage));

        List<ToolSpecification> toolSpecs = toToolSpecifications(toolRegistry.getAll());
        List<ReActStep> allSteps = new ArrayList<>();

        for (int i = 1; i <= maxIterations; i++) {
            log.info("[L3-ReAct] Streaming iteration {}/{}", i, maxIterations);

            if (cancellationToken != null && cancellationToken.isCancelled()) {
                log.info("[L3-ReAct] Cancellation detected at iteration {}", i);
                String cancelledOutput = allSteps.isEmpty() ? "Request cancelled" :
                        allSteps.get(allSteps.size() - 1).observation();
                return new ReActResult(cancelledOutput, allSteps);
            }

            if (i > 2) {
                stripToolMessages(messages);
            }

            ChatRequest.Builder reqBuilder = ChatRequest.builder().messages(messages);
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
                log.info("[L3-ReAct] Streaming LLM call in {}ms, tokens: in={}, out={}",
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
                log.info("[L3-ReAct] Streaming complete at iteration {}, answer: {}", i, truncate(answer));
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
            log.info("[L3-ReAct] Streaming: LLM requested {} tool calls", toolReqs.size());

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

                log.info("[L3-ReAct] Executing tool: {}", tc.toolName());
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

            if (inspection.status() != ReActStep.InspectionResult.InspectionStatus.PASS) {
                log.info("[L3-ReAct] Inspection: status={}, reason={}", inspection.status(), inspection.reason());
                String hint = Inspector.buildInspectionHint(inspection);
                if (hint != null) {
                    messages.add(UserMessage.from(hint));
                }
            }

            if (inspection.status() == ReActStep.InspectionResult.InspectionStatus.TOOL_ERROR && stopOnToolError) {
                log.warn("[L3-ReAct] Tool error at iteration {}, stopping", i);
                break;
            }
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
                log.info("[L3-ReAct] Tool [{}] succeeded on retry {}", toolCall.toolName(), attempt);
                return result;
            }
        }

        log.error("[L3-ReAct] Tool [{}] failed after {} retries", toolCall.toolName(), TOOL_MAX_RETRIES);
        return result;
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
