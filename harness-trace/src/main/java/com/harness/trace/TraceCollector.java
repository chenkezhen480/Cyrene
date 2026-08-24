package com.harness.trace;

import com.harness.trace.store.TraceStore;
import com.harness.core.model.AgentTrace;
import com.harness.core.model.ModelUsage;
import com.harness.core.model.ReActStep;
import com.harness.core.model.RiskLevel;
import com.harness.core.runtime.RunTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer 5: Audit / Trace collector.
 * Collects all data from a single agent run and persists it.
 * Runs as a cross-cutting concern across all other layers.
 */
public class TraceCollector implements RunTrace {

    private static final Logger log = LoggerFactory.getLogger(TraceCollector.class);

    private final TraceStore store;
    private final AgentTrace.Builder builder;
    private final long startTime;

    public TraceCollector(TraceStore store) {
        this.store = store;
        this.builder = AgentTrace.builder();
        this.startTime = System.currentTimeMillis();
        log.debug("[L5-Trace] Trace collection started");
    }

    // ==================== Input Layer ====================

    @Override
    public synchronized void recordInput(String userId, String text, List<String> attachmentNames) {
        builder.userId(userId)
                .inputText(text)
                .inputAttachments(attachmentNames != null ? attachmentNames : List.of());
        log.debug("[L5-Trace] Input recorded: userId={}, textLen={}, attachments={}",
                userId, text != null ? text.length() : 0, attachmentNames != null ? attachmentNames.size() : 0);
    }

    // ==================== Preprocess Layer ====================

    @Override
    public synchronized void recordPreprocess(String intent, List<String> ragHits, String rerankResult) {
        builder.intent(intent)
                .ragHits(ragHits != null ? ragHits : List.of())
                .rerankResult(rerankResult);
        log.debug("[L5-Trace] Preprocess recorded: ragHits={}", ragHits != null ? ragHits.size() : 0);
    }

    // ==================== AI Layer ====================

    @Override
    public synchronized void recordLlmMeta(String model, String promptVersion) {
        builder.llmModel(model)
                .promptVersion(promptVersion);
        log.debug("[L5-Trace] LLM meta: model={}, promptVersion={}", model, promptVersion);
    }

    @Override
    public synchronized void addStep(ReActStep step) {
        List<ReActStep> current = new ArrayList<>(builder.build().steps());
        current.add(step);
        builder.steps(current);
        log.debug("[L5-Trace] Step {} added: action={}", step.stepNumber(), step.action());
    }

    // ==================== Output ====================

    @Override
    public synchronized void addTokens(long tokenCount) {
        if (tokenCount <= 0) {
            return;
        }
        long totalTokens = builder.build().totalTokens() + tokenCount;
        builder.totalTokens(totalTokens > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) totalTokens);
    }

    @Override
    public synchronized void recordModelUsage(ModelUsage usage) {
        if (usage == null) {
            return;
        }
        java.util.Map<String, String> metadata =
                new java.util.HashMap<>(builder.build().metadata());
        int callNumber = Integer.parseInt(metadata.getOrDefault("llmUsageCallCount", "0")) + 1;
        String prefix = "llmUsageCall" + callNumber;
        metadata.put("llmUsageCallCount", String.valueOf(callNumber));
        putIfObserved(metadata, prefix + "InputTokens", usage.inputTokens());
        putIfObserved(metadata, prefix + "CachedInputTokens", usage.cachedInputTokens());
        putIfObserved(metadata, prefix + "UncachedInputTokens", usage.uncachedInputTokens());
        putIfObserved(metadata, prefix + "CacheWriteTokens", usage.cacheWriteTokens());
        putIfObserved(metadata, prefix + "OutputTokens", usage.outputTokens());
        putIfObserved(metadata, prefix + "ReasoningTokens", usage.reasoningTokens());
        metadata.put(prefix + "LatencyMs", String.valueOf(usage.llmLatencyMs()));
        if (usage.cacheHitRatio() != null) {
            metadata.put(prefix + "CacheHitRatio", String.valueOf(usage.cacheHitRatio()));
        }
        if (usage.promptPrefixFingerprint() != null) {
            metadata.put(prefix + "PromptPrefixFingerprint", usage.promptPrefixFingerprint());
        }
        if (usage.toolCatalogVersion() != null) {
            metadata.put(prefix + "ToolCatalogVersion", String.valueOf(usage.toolCatalogVersion()));
        }

        accumulateObserved(metadata, "llmInputTokens", usage.inputTokens());
        accumulateObserved(metadata, "llmCachedInputTokens", usage.cachedInputTokens());
        incrementObservationCount(metadata, "llmInputUsageObservedCalls", usage.inputTokens());
        incrementObservationCount(
                metadata, "llmCachedInputUsageObservedCalls", usage.cachedInputTokens());
        accumulateObserved(metadata, "llmCacheWriteTokens", usage.cacheWriteTokens());
        accumulateObserved(metadata, "llmOutputTokens", usage.outputTokens());
        accumulateObserved(metadata, "llmReasoningTokens", usage.reasoningTokens());
        accumulateObserved(metadata, "llmLatencyMs", usage.llmLatencyMs());
        Long totalInputTokens = observedLong(metadata, "llmInputTokens");
        Long totalCachedInputTokens = observedLong(metadata, "llmCachedInputTokens");
        if (totalInputTokens != null && totalInputTokens > 0
                && totalCachedInputTokens != null
                && metadata.get("llmInputUsageObservedCalls").equals(
                        metadata.get("llmCachedInputUsageObservedCalls"))) {
            metadata.put("llmUncachedInputTokens", String.valueOf(
                    Math.max(totalInputTokens - totalCachedInputTokens, 0)));
            metadata.put("llmCacheHitRatio", String.valueOf(
                    totalCachedInputTokens.doubleValue() / totalInputTokens));
        } else {
            metadata.remove("llmUncachedInputTokens");
            metadata.remove("llmCacheHitRatio");
        }
        builder.metadata(metadata);
    }

    @Override
    public synchronized void recordOutput(String output, RiskLevel risk, boolean userConfirmed) {
        builder.finalOutput(output)
                .riskLevel(risk)
                .userConfirmed(userConfirmed);
        log.debug("[L5-Trace] Output recorded: risk={}, outputLen={}, confirmed={}",
                risk, output != null ? output.length() : 0, userConfirmed);
    }

    @Override
    public synchronized void recordConfirmation(
            String requestId, String toolName, String argumentsHash, String decision) {
        java.util.Map<String, String> meta =
                new java.util.HashMap<>(builder.build().metadata());
        int confirmationCount = Integer.parseInt(
                meta.getOrDefault("confirmation_count", "0")) + 1;
        String prefix = "confirmation_" + confirmationCount + "_";
        meta.put("confirmation_count", String.valueOf(confirmationCount));
        meta.put(prefix + "request_id", requestId);
        meta.put(prefix + "tool", toolName);
        meta.put(prefix + "arguments_hash", argumentsHash);
        meta.put(prefix + "decision", decision);
        builder.metadata(meta);
        log.debug("[L5-Trace] Confirmation recorded: requestId={}, tool={}, decision={}",
                requestId, toolName, decision);
    }

    // ==================== ReAct Loop Stats ====================

    /**
     * Record ReAct loop quality signals into trace metadata.
     * Called after the ReAct loop completes, before finish().
     */
    public void recordReactStats(String outcome, int rounds, int toolCalls, int reflectionChecks) {
        recordReactStats(outcome, rounds, toolCalls, reflectionChecks, 0, 0, 0, 0);
    }

    /**
     * Record ReAct loop quality signals with full metrics.
     */
    @Override
    public synchronized void recordReactStats(String outcome, int rounds, int toolCalls, int reflectionChecks,
                                  long inputTokens, long outputTokens, int llmCalls, int toolRetries) {
        java.util.Map<String, String> meta = new java.util.HashMap<>(builder.build().metadata());
        meta.put("react_outcome", outcome);
        meta.put("react_rounds", String.valueOf(rounds));
        meta.put("react_tool_calls", String.valueOf(toolCalls));
        meta.put("react_reflection_checks", String.valueOf(reflectionChecks));
        meta.put("react_reflection_flagged_offtrack", "false"); // placeholder: future LLM-based offtrack detection
        if (inputTokens > 0 || outputTokens > 0) {
            meta.put("react_input_tokens", String.valueOf(inputTokens));
            meta.put("react_output_tokens", String.valueOf(outputTokens));
        }
        if (llmCalls > 0) {
            meta.put("react_llm_calls", String.valueOf(llmCalls));
        }
        if (toolRetries > 0) {
            meta.put("react_tool_retries", String.valueOf(toolRetries));
        }
        builder.metadata(meta);
        log.debug("[L5-Trace] React stats recorded: outcome={}, rounds={}, tools={}, llmCalls={}, inTok={}, outTok={}, retries={}",
                outcome, rounds, toolCalls, llmCalls, inputTokens, outputTokens, toolRetries);
    }

    // ==================== Persist ====================

    /**
     * Finalize and persist the trace. Call this at the end of each agent run.
     */
    @Override
    public synchronized AgentTrace finish() {
        builder.totalDurationMs(System.currentTimeMillis() - startTime);
        AgentTrace trace = builder.build();

        try {
            store.save(trace);
            log.debug("Trace saved: id={}, steps={}, duration={}ms",
                    trace.traceId(), trace.steps().size(), trace.totalDurationMs());
        } catch (Exception e) {
            log.error("Failed to save trace: {}", e.getMessage(), e);
        }

        return trace;
    }

    // ==================== Post-write Updates ====================

    /**
     * Update trace metadata after initial write (e.g., user feedback).
     * Supports "事后按 trace id 更新" — e.g., thumbs up/down from UI.
     *
     * @param traceId the trace to update
     * @param key metadata key (e.g., "user_feedback")
     * @param value metadata value (e.g., "positive" / "negative")
     */
    public void updateFeedback(String traceId, String key, String value) {
        try {
            store.updateMetadata(traceId, java.util.Map.of(key, value));
            log.debug("[L5-Trace] Feedback updated: traceId={}, {}={}", traceId, key, value);
        } catch (Exception e) {
            log.error("[L5-Trace] Failed to update feedback: traceId={}, {}", traceId, e.getMessage(), e);
        }
    }

    @Override
    public synchronized void setSessionId(String sessionId) {
        builder.sessionId(sessionId);
    }

    @Override
    public synchronized void putMetadata(String key, String value) {
        java.util.Map<String, String> metadata =
                new java.util.HashMap<>(builder.build().metadata());
        metadata.put(key, value);
        builder.metadata(metadata);
    }

    @Override
    public synchronized void putMetadata(java.util.Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        java.util.Map<String, String> metadata =
                new java.util.HashMap<>(builder.build().metadata());
        metadata.putAll(values);
        builder.metadata(metadata);
    }

    @Override
    public synchronized String traceId() {
        return builder.build().traceId();
    }

    @Override
    public synchronized AgentTrace snapshot() {
        return builder.build();
    }

    private static void putIfObserved(
            java.util.Map<String, String> metadata,
            String key,
            Long value
    ) {
        if (value != null) {
            metadata.put(key, String.valueOf(value));
        }
    }

    private static void accumulateObserved(
            java.util.Map<String, String> metadata,
            String key,
            Long value
    ) {
        if (value == null) {
            return;
        }
        long current = Long.parseLong(metadata.getOrDefault(key, "0"));
        metadata.put(key, String.valueOf(current + value));
    }

    private static void accumulateObserved(
            java.util.Map<String, String> metadata,
            String key,
            long value
    ) {
        accumulateObserved(metadata, key, Long.valueOf(value));
    }

    private static Long observedLong(java.util.Map<String, String> metadata, String key) {
        String value = metadata.get(key);
        return value != null ? Long.parseLong(value) : null;
    }

    private static void incrementObservationCount(
            java.util.Map<String, String> metadata,
            String key,
            Long observedValue
    ) {
        if (observedValue == null) {
            return;
        }
        long current = Long.parseLong(metadata.getOrDefault(key, "0"));
        metadata.put(key, String.valueOf(current + 1));
    }
}
