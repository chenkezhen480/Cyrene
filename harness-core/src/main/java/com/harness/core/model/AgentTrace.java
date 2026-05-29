package com.harness.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Full trace of a single agent run. Persisted by the audit layer.
 */
public record AgentTrace(
        String traceId,
        Instant timestamp,

        // Input
        String userId,
        String sessionId,
        String inputText,
        List<String> inputAttachments,

        // Preprocess
        String intent,
        List<String> ragHits,
        String rerankResult,

        // ReAct loop
        String llmModel,
        String promptVersion,
        List<ReActStep> steps,

        // Output
        String finalOutput,
        RiskLevel riskLevel,
        boolean userConfirmed,

        // Meta
        long totalDurationMs,
        int totalTokens,
        Map<String, String> metadata
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String traceId = java.util.UUID.randomUUID().toString();
        private Instant timestamp = Instant.now();
        private String userId;
        private String sessionId;
        private String inputText;
        private List<String> inputAttachments = List.of();
        private String intent;
        private List<String> ragHits = List.of();
        private String rerankResult;
        private String llmModel;
        private String promptVersion;
        private List<ReActStep> steps = List.of();
        private String finalOutput;
        private RiskLevel riskLevel = RiskLevel.LOW;
        private boolean userConfirmed;
        private long totalDurationMs;
        private int totalTokens;
        private Map<String, String> metadata = Map.of();

        public Builder traceId(String v) { this.traceId = v; return this; }
        public Builder timestamp(Instant v) { this.timestamp = v; return this; }
        public Builder userId(String v) { this.userId = v; return this; }
        public Builder sessionId(String v) { this.sessionId = v; return this; }
        public Builder inputText(String v) { this.inputText = v; return this; }
        public Builder inputAttachments(List<String> v) { this.inputAttachments = v; return this; }
        public Builder intent(String v) { this.intent = v; return this; }
        public Builder ragHits(List<String> v) { this.ragHits = v; return this; }
        public Builder rerankResult(String v) { this.rerankResult = v; return this; }
        public Builder llmModel(String v) { this.llmModel = v; return this; }
        public Builder promptVersion(String v) { this.promptVersion = v; return this; }
        public Builder steps(List<ReActStep> v) { this.steps = v; return this; }
        public Builder finalOutput(String v) { this.finalOutput = v; return this; }
        public Builder riskLevel(RiskLevel v) { this.riskLevel = v; return this; }
        public Builder userConfirmed(boolean v) { this.userConfirmed = v; return this; }
        public Builder totalDurationMs(long v) { this.totalDurationMs = v; return this; }
        public Builder totalTokens(int v) { this.totalTokens = v; return this; }
        public Builder metadata(Map<String, String> v) { this.metadata = v; return this; }

        public AgentTrace build() {
            return new AgentTrace(traceId, timestamp, userId, sessionId, inputText, inputAttachments,
                    intent, ragHits, rerankResult, llmModel, promptVersion, steps,
                    finalOutput, riskLevel, userConfirmed, totalDurationMs, totalTokens, metadata);
        }
    }
}
