package com.harness.audit;

import com.harness.audit.store.TraceStore;
import com.harness.core.model.AgentTrace;
import com.harness.core.model.ReActStep;
import com.harness.core.model.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TraceCollectorTest {

    @Mock TraceStore store;

    TraceCollector collector;

    @BeforeEach
    void setUp() {
        collector = new TraceCollector(store);
    }

    @Test
    void recordInput_setsFields() {
        collector.recordInput("user1", "hello", List.of("file.pdf"));

        AgentTrace trace = collector.builder().build();
        assertThat(trace.userId()).isEqualTo("user1");
        assertThat(trace.inputText()).isEqualTo("hello");
        assertThat(trace.inputAttachments()).containsExactly("file.pdf");
    }

    @Test
    void recordInput_nullAttachments_defaultsToEmpty() {
        collector.recordInput("user1", "hello", null);

        AgentTrace trace = collector.builder().build();
        assertThat(trace.inputAttachments()).isEmpty();
    }

    @Test
    void recordPreprocess_setsFields() {
        collector.recordPreprocess("search", List.of("doc1", "doc2"), "reranked");

        AgentTrace trace = collector.builder().build();
        assertThat(trace.intent()).isEqualTo("search");
        assertThat(trace.ragHits()).containsExactly("doc1", "doc2");
        assertThat(trace.rerankResult()).isEqualTo("reranked");
    }

    @Test
    void recordLlmMeta_setsFields() {
        collector.recordLlmMeta("gpt-4o", "v2");

        AgentTrace trace = collector.builder().build();
        assertThat(trace.llmModel()).isEqualTo("gpt-4o");
        assertThat(trace.promptVersion()).isEqualTo("v2");
    }

    @Test
    void addStep_accumulates() {
        var inspection = new ReActStep.InspectionResult(ReActStep.InspectionResult.InspectionStatus.PASS, "ok");
        ReActStep step1 = new ReActStep(1, "think", "search", List.of(), List.of(), "result", inspection);
        ReActStep step2 = new ReActStep(2, "think2", "answer", List.of(), List.of(), "final", inspection);

        collector.addStep(step1);
        collector.addStep(step2);

        AgentTrace trace = collector.builder().build();
        assertThat(trace.steps()).hasSize(2);
        assertThat(trace.steps().get(0).stepNumber()).isEqualTo(1);
        assertThat(trace.steps().get(1).stepNumber()).isEqualTo(2);
    }

    @Test
    void recordOutput_setsFields() {
        collector.recordOutput("The answer is 42", RiskLevel.LOW, false);

        AgentTrace trace = collector.builder().build();
        assertThat(trace.finalOutput()).isEqualTo("The answer is 42");
        assertThat(trace.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(trace.userConfirmed()).isFalse();
    }

    @Test
    void recordConfirmation_appendsAuditableDecisions() {
        collector.recordConfirmation("request-1", "update_api", "hash-1", "APPROVED");
        collector.recordConfirmation("request-2", "delete_api", "hash-2", "REJECTED");

        AgentTrace trace = collector.builder().build();
        assertThat(trace.metadata())
                .containsEntry("confirmation_count", "2")
                .containsEntry("confirmation_1_request_id", "request-1")
                .containsEntry("confirmation_1_tool", "update_api")
                .containsEntry("confirmation_1_arguments_hash", "hash-1")
                .containsEntry("confirmation_1_decision", "APPROVED")
                .containsEntry("confirmation_2_request_id", "request-2")
                .containsEntry("confirmation_2_decision", "REJECTED");
    }

    @Test
    void finish_savesTrace() {
        collector.recordInput("user1", "test", List.of());
        collector.recordOutput("result", RiskLevel.LOW, false);

        AgentTrace trace = collector.finish();

        assertThat(trace).isNotNull();
        assertThat(trace.traceId()).isNotBlank();
        assertThat(trace.totalDurationMs()).isGreaterThanOrEqualTo(0);

        ArgumentCaptor<AgentTrace> captor = ArgumentCaptor.forClass(AgentTrace.class);
        verify(store).save(captor.capture());
        assertThat(captor.getValue().traceId()).isEqualTo(trace.traceId());
    }

    @Test
    void finish_storeThrowsStillReturnsTrace() {
        doThrow(new RuntimeException("DB down")).when(store).save(any());

        collector.recordInput("user1", "test", List.of());
        AgentTrace trace = collector.finish();

        // Should still return trace even if save fails
        assertThat(trace).isNotNull();
        assertThat(trace.traceId()).isNotBlank();
    }
}
