package com.harness.core.runtime;

import com.harness.core.model.AgentTrace;
import com.harness.core.model.ReActStep;
import com.harness.core.model.RiskLevel;

import java.util.List;
import java.util.Map;

/**
 * Trace boundary for one agent run.
 *
 * <p>The runtime records semantic events through this contract. Persistence and
 * storage-specific behavior belong to the trace module.</p>
 */
public interface RunTrace {

    void recordInput(String userId, String text, List<String> attachmentNames);

    void recordPreprocess(String intent, List<String> ragHits, String rerankResult);

    void recordLlmMeta(String model, String promptVersion);

    void addStep(ReActStep step);

    void addTokens(long tokenCount);

    void recordOutput(String output, RiskLevel risk, boolean userConfirmed);

    void recordConfirmation(String requestId, String toolName, String argumentsHash, String decision);

    void recordReactStats(
            String outcome,
            int rounds,
            int toolCalls,
            int reflectionChecks,
            long inputTokens,
            long outputTokens,
            int llmCalls,
            int toolRetries
    );

    void setSessionId(String sessionId);

    void putMetadata(String key, String value);

    void putMetadata(Map<String, String> metadata);

    String traceId();

    AgentTrace snapshot();

    AgentTrace finish();

    static RunTrace noop() {
        return NoOpRunTrace.INSTANCE;
    }
}
