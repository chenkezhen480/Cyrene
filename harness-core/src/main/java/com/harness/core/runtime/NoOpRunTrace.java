package com.harness.core.runtime;

import com.harness.core.model.AgentTrace;
import com.harness.core.model.ModelUsage;
import com.harness.core.model.ReActStep;
import com.harness.core.model.RiskLevel;

import java.util.List;
import java.util.Map;

final class NoOpRunTrace implements RunTrace {

    static final NoOpRunTrace INSTANCE = new NoOpRunTrace();

    private NoOpRunTrace() {
    }

    @Override public void recordInput(String userId, String text, List<String> attachmentNames) { }
    @Override public void recordPreprocess(String intent, List<String> ragHits, String rerankResult) { }
    @Override public void recordLlmMeta(String model, String promptVersion) { }
    @Override public void addStep(ReActStep step) { }
    @Override public void addTokens(long tokenCount) { }
    @Override public void recordModelUsage(ModelUsage usage) { }
    @Override public void recordOutput(String output, RiskLevel risk, boolean userConfirmed) { }
    @Override public void recordConfirmation(String requestId, String toolName, String argumentsHash, String decision) { }
    @Override public void recordReactStats(String outcome, int rounds, int toolCalls, int reflectionChecks,
                                           long inputTokens, long outputTokens, int llmCalls, int toolRetries) { }
    @Override public void setSessionId(String sessionId) { }
    @Override public void putMetadata(String key, String value) { }
    @Override public void putMetadata(Map<String, String> metadata) { }
    @Override public String traceId() { return ""; }
    @Override public AgentTrace snapshot() { return AgentTrace.builder().build(); }
    @Override public AgentTrace finish() { return snapshot(); }
}
