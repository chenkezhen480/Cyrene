package com.harness.audit;

import com.harness.audit.store.TraceStore;
import com.harness.core.model.AgentTrace;
import com.harness.core.model.ReActStep;
import com.harness.core.model.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer 5: Audit / Trace collector.
 * Collects all data from a single agent run and persists it.
 * Runs as a cross-cutting concern across all other layers.
 */
public class TraceCollector {

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

    public void recordInput(String userId, String text, List<String> attachmentNames) {
        builder.userId(userId)
                .inputText(text)
                .inputAttachments(attachmentNames != null ? attachmentNames : List.of());
        log.debug("[L5-Trace] Input recorded: userId={}, textLen={}, attachments={}",
                userId, text != null ? text.length() : 0, attachmentNames != null ? attachmentNames.size() : 0);
    }

    // ==================== Preprocess Layer ====================

    public void recordPreprocess(String intent, List<String> ragHits, String rerankResult) {
        builder.intent(intent)
                .ragHits(ragHits != null ? ragHits : List.of())
                .rerankResult(rerankResult);
        log.debug("[L5-Trace] Preprocess recorded: ragHits={}", ragHits != null ? ragHits.size() : 0);
    }

    // ==================== AI Layer ====================

    public void recordLlmMeta(String model, String promptVersion) {
        builder.llmModel(model)
                .promptVersion(promptVersion);
        log.debug("[L5-Trace] LLM meta: model={}, promptVersion={}", model, promptVersion);
    }

    public void addStep(ReActStep step) {
        List<ReActStep> current = new ArrayList<>(builder.build().steps());
        current.add(step);
        builder.steps(current);
        log.debug("[L5-Trace] Step {} added: action={}", step.stepNumber(), step.action());
    }

    // ==================== Output ====================

    public void recordOutput(String output, RiskLevel risk, boolean userConfirmed) {
        builder.finalOutput(output)
                .riskLevel(risk)
                .userConfirmed(userConfirmed);
        log.debug("[L5-Trace] Output recorded: risk={}, outputLen={}, confirmed={}",
                risk, output != null ? output.length() : 0, userConfirmed);
    }

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
    public void recordReactStats(String outcome, int rounds, int toolCalls, int reflectionChecks,
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
    public AgentTrace finish() {
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

    /**
     * Get the current trace builder (for reading intermediate state).
     */
    public AgentTrace.Builder builder() {
        return builder;
    }
}
