package com.harness.tool.knowledge.authority;

import com.harness.core.env.EnvConfig;
import com.harness.graph.model.GraphChangeSet;
import com.harness.graph.model.GraphMutationResult;
import com.harness.graph.model.GraphNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
class MysqlKnowledgeGraphMutationJobStoreIT {

    private static final String REQUEST_ID = "it-graph-saga-request";
    private static final String REVISION_ID = "it-graph-space-revision";
    private static final String URL = "jdbc:mysql://localhost:3306/zhi_du_yuan"
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";

    @BeforeAll
    static void configure() throws Exception {
        EnvConfig.init(Map.of(
                "HARNESS_AUDIT_DB_URL", URL,
                "HARNESS_AUDIT_DB_USER", "root",
                "HARNESS_AUDIT_DB_PASS", "1234",
                "HARNESS_AUDIT_STORE", "mysql"));
        cleanup();
    }

    @AfterAll
    static void cleanupAfter() throws Exception {
        cleanup();
    }

    @Test
    void persistsStagesBindingAndRejectsRequestIdPayloadReuse() throws Exception {
        MysqlKnowledgeGraphMutationJobStore store =
                new MysqlKnowledgeGraphMutationJobStore();
        Instant now = Instant.parse("2026-09-08T11:00:00Z");
        GraphChangeSet changeSet = changeSet("graph-it");

        KnowledgeGraphMutationJob registered = store.register(
                changeSet, null, null, now);
        KnowledgeGraphMutationJob same = store.register(
                changeSet, null, null, now.plusSeconds(1));
        assertThat(same.payloadHash()).isEqualTo(registered.payloadHash());
        assertThatThrownBy(() -> store.register(
                changeSet("another-graph"), null, null, now.plusSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another payload");

        KnowledgeGraphMutationJob claimedGraph = store.claim(REQUEST_ID, now)
                .orElseThrow();
        assertThat(claimedGraph.attempts()).isOne();
        store.markGraphCommitted(REQUEST_ID,
                new GraphMutationResult(REQUEST_ID, true, 1, 0), now);
        KnowledgeGraphMutationJob claimedKnowledge = store.claim(REQUEST_ID, now)
                .orElseThrow();
        assertThat(claimedKnowledge.status())
                .isEqualTo(KnowledgeGraphMutationJob.Status.GRAPH_COMMITTED);

        KnowledgeGraphMutationJob completed = store.completeKnowledge(
                REQUEST_ID, REVISION_ID, now);

        assertThat(completed.status())
                .isEqualTo(KnowledgeGraphMutationJob.Status.KNOWLEDGE_COMMITTED);
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM knowledge_graph_mutation_jobs
                     WHERE result_revision_id = ? AND graph_id = 'graph-it'
                       AND schema_id = 'schema-it' AND status = 'knowledge_committed'
                     """)) {
            statement.setString(1, REVISION_ID);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                assertThat(resultSet.getInt(1)).isOne();
            }
        }
    }

    private static GraphChangeSet changeSet(String graphId) {
        return new GraphChangeSet(
                REQUEST_ID, graphId, "schema-it",
                List.of(new GraphNode("node-1", Set.of("TestNode"), Map.of())),
                List.of(), Set.of(), Set.of());
    }

    private static void cleanup() throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement jobs = connection.prepareStatement(
                         "DELETE FROM knowledge_graph_mutation_jobs WHERE request_id = ?")) {
                jobs.setString(1, REQUEST_ID);
                jobs.executeUpdate();
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(URL, "root", "1234");
    }
}
