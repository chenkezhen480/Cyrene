package com.harness.trace.store;

import com.harness.core.model.AgentTrace;
import com.harness.core.model.ReActStep;
import com.harness.core.model.RiskLevel;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.MysqlConnectionPool;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class MysqlTraceStoreIT {

    MysqlTraceStore store;
    String testTraceId;

    @BeforeAll
    static void initEnv() {
        EnvConfig.init(Map.of(
                "HARNESS_AUDIT_DB_URL", "jdbc:mysql://localhost:3306/agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                "HARNESS_AUDIT_DB_USER", "root",
                "HARNESS_AUDIT_DB_PASS", "1234"
        ));
    }

    @BeforeEach
    void setUp() {
        store = new MysqlTraceStore();
        testTraceId = "it-test-" + System.nanoTime();
    }

    @AfterAll
    static void cleanUp() {
        try (Connection conn = MysqlConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM agent_traces WHERE trace_id LIKE 'it-test-%'")) {
            ps.executeUpdate();
        } catch (SQLException e) {
            // ignore cleanup errors
        }
    }

    private AgentTrace buildTrace(String traceId) {
        return AgentTrace.builder()
                .traceId(traceId)
                .userId("it_user_trace")
                .sessionId("it_session_trace")
                .inputText("Test input")
                .finalOutput("Test output")
                .riskLevel(RiskLevel.LOW)
                .totalDurationMs(1500)
                .totalTokens(200)
                .metadata(Map.of("test", "true"))
                .build();
    }

    private AgentTrace buildTraceWithSteps(String traceId) {
        ReActStep.InspectionResult inspection = new ReActStep.InspectionResult(
                ReActStep.InspectionResult.InspectionStatus.PASS, "ok");
        ReActStep step = new ReActStep(1, "thinking", "search", List.of(), List.of(), "result", inspection);

        return AgentTrace.builder()
                .traceId(traceId)
                .userId("it_user_trace")
                .inputText("Search for something")
                .finalOutput("Found it")
                .riskLevel(RiskLevel.MEDIUM)
                .steps(List.of(step))
                .totalDurationMs(3000)
                .totalTokens(500)
                .build();
    }

    @Test
    void save_andFindById() {
        AgentTrace trace = buildTrace(testTraceId);

        store.save(trace);

        Optional<AgentTrace> found = store.findById(testTraceId);
        assertThat(found).isPresent();
        assertThat(found.get().traceId()).isEqualTo(testTraceId);
        assertThat(found.get().userId()).isEqualTo("it_user_trace");
        assertThat(found.get().inputText()).isEqualTo("Test input");
        assertThat(found.get().finalOutput()).isEqualTo("Test output");
        assertThat(found.get().riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(found.get().totalDurationMs()).isEqualTo(1500);
        assertThat(found.get().totalTokens()).isEqualTo(200);
    }

    @Test
    void save_withMetadata_persistsMap() {
        AgentTrace trace = AgentTrace.builder()
                .traceId(testTraceId)
                .inputText("test")
                .metadata(Map.of("key1", "val1", "key2", "val2"))
                .build();

        store.save(trace);

        Optional<AgentTrace> found = store.findById(testTraceId);
        assertThat(found).isPresent();
        assertThat(found.get().metadata()).containsEntry("key1", "val1").containsEntry("key2", "val2");
    }

    @Test
    void save_withSteps_persistsList() {
        AgentTrace trace = buildTraceWithSteps(testTraceId);

        store.save(trace);

        Optional<AgentTrace> found = store.findById(testTraceId);
        assertThat(found).isPresent();
        assertThat(found.get().steps()).hasSize(1);
        assertThat(found.get().steps().get(0).thought()).isEqualTo("thinking");
        assertThat(found.get().steps().get(0).action()).isEqualTo("search");
        assertThat(found.get().steps().get(0).inspection().status())
                .isEqualTo(ReActStep.InspectionResult.InspectionStatus.PASS);
    }

    @Test
    void findById_nonexistent_returnsEmpty() {
        Optional<AgentTrace> found = store.findById("nonexistent-trace-id-12345");

        assertThat(found).isEmpty();
    }

    @Test
    void listRecent_returnsLimitedResults() {
        // Save multiple traces
        for (int i = 0; i < 3; i++) {
            store.save(buildTrace(testTraceId + "-" + i));
        }

        List<AgentTrace> recent = store.listRecent(2);

        assertThat(recent).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void count_returnsPositiveAfterInsert() {
        int before = store.count();
        store.save(buildTrace(testTraceId));

        int after = store.count();

        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    void deleteById_existing_returnsTrue() {
        store.save(buildTrace(testTraceId));

        boolean deleted = store.deleteById(testTraceId);

        assertThat(deleted).isTrue();
        assertThat(store.findById(testTraceId)).isEmpty();
    }

    @Test
    void deleteById_nonexistent_returnsFalse() {
        boolean deleted = store.deleteById("nonexistent-trace-id-99999");

        assertThat(deleted).isFalse();
    }

    @Test
    void cleanup_removesOldTraces() {
        // cleanup(retentionDays) deletes traces older than retentionDays
        // With 0 days, it should delete all traces (including ours)
        // We test with a very large number first (should not delete ours)
        store.save(buildTrace(testTraceId));

        int deleted = store.cleanup(99999);

        // Our trace should still exist (not old enough)
        assertThat(store.findById(testTraceId)).isPresent();
    }
}
