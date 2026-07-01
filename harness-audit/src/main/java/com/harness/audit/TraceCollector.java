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

    /**
     * Get the current trace builder (for reading intermediate state).
     */
    public AgentTrace.Builder builder() {
        return builder;
    }
}
