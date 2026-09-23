package com.harness.tool.knowledge.authority;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.MysqlConnectionPool;
import com.harness.core.knowledge.KnowledgeArtifact;
import com.harness.core.knowledge.KnowledgeArtifactType;
import com.harness.core.knowledge.KnowledgeIdentity;
import com.harness.core.knowledge.KnowledgeIngestJob;
import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeIndexOperation;
import com.harness.core.knowledge.KnowledgeIndexTask;
import com.harness.core.knowledge.KnowledgeIndexTaskStatus;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.knowledge.KnowledgeRevision;
import com.harness.core.knowledge.KnowledgeSource;
import com.harness.core.knowledge.KnowledgeSourceType;
import com.harness.core.knowledge.KnowledgeStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class MysqlKnowledgeArtifactAndGraphStoreIT {

    private static final String COLLECTION = "it_todo12_collection";
    private static final Instant AVAILABLE_AT = Instant.parse("2000-01-01T00:00:00Z");

    private MysqlKnowledgeArtifactRepository artifactRepository;
    private MysqlKnowledgeIngestJobStore ingestJobStore;
    private ObjectMapper objectMapper;

    @BeforeAll
    static void initEnv() {
        EnvConfig.init(Map.of(
                "HARNESS_AUDIT_DB_URL", "jdbc:mysql://localhost:3306/zhi_du_yuan?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                "HARNESS_AUDIT_DB_USER", "root",
                "HARNESS_AUDIT_DB_PASS", "1234",
                "HARNESS_AUDIT_STORE", "mysql"));
    }

    @BeforeEach
    void setUp() throws SQLException {
        cleanTestRows();
        objectMapper = new ObjectMapper();
        artifactRepository = new MysqlKnowledgeArtifactRepository();
        ingestJobStore = new MysqlKnowledgeIngestJobStore();
    }

    @AfterAll
    static void cleanUp() throws SQLException {
        cleanTestRows();
    }

    @Test
    void artifactRegistrationIsIdempotentAndIngestStagesAdvanceOnlyWhenClaimed() {
        KnowledgeArtifact artifact = artifact("source-a.md", "artifact-content-a");
        KnowledgeIngestJob job = job("it-todo12-ingest-a", artifact.id());

        var first = artifactRepository.registerWithIngestJob(artifact, job);
        var second = artifactRepository.registerWithIngestJob(artifact, job);
        assertThat(second).isEqualTo(first);
        assertThat(artifactRepository.findByContent(
                null, COLLECTION, artifact.contentHash(), artifact.artifactType()))
                .contains(artifact);

        KnowledgeIngestJob uploaded = ingestJobStore.claim(job.id(), AVAILABLE_AT).orElseThrow();
        assertThat(uploaded.id()).isEqualTo(job.id());
        KnowledgeIngestJob converted = ingestJobStore.advance(
                job.id(), KnowledgeIngestJob.Status.UPLOADED,
                KnowledgeIngestJob.Status.CONVERTED,
                "converted-artifact", null, null, null);
        assertThat(converted.status()).isEqualTo(KnowledgeIngestJob.Status.CONVERTED);
        ingestJobStore.claim(job.id(), AVAILABLE_AT).orElseThrow();
        ingestJobStore.advance(
                job.id(), KnowledgeIngestJob.Status.CONVERTED,
                KnowledgeIngestJob.Status.COMPILED,
                null, "source-concept", "source-revision", null);
        ingestJobStore.claim(job.id(), AVAILABLE_AT).orElseThrow();
        KnowledgeIngestJob indexed = ingestJobStore.advance(
                job.id(), KnowledgeIngestJob.Status.COMPILED,
                KnowledgeIngestJob.Status.INDEXED,
                null, null, null, Instant.now());
        assertThat(indexed.status()).isEqualTo(KnowledgeIngestJob.Status.INDEXED);
        assertThat(indexed.completedAt()).isNotNull();

        KnowledgeArtifact laterArtifact = artifact("source-z.md", "artifact-content-z");
        KnowledgeIngestJob laterJob = job("it-todo12-ingest-z", laterArtifact.id());
        artifactRepository.registerWithIngestJob(laterArtifact, laterJob);
        assertThat(ingestJobStore.findPage(job.id(), 1).items())
                .extracting(KnowledgeIngestJob::id)
                .containsExactly(laterJob.id());
    }

    @Test
    void sourceDocumentCompilationCommitsRevisionOutboxAndJobStageAtomically() {
        KnowledgeArtifact artifact = artifact("source-compile.md", "artifact-content-compile");
        String placeholderConceptId = "it-todo12-doc-placeholder";
        KnowledgeIngestJob job = job(
                "it-todo12-ingest-compile", artifact.id(), placeholderConceptId);
        artifactRepository.registerWithIngestJob(artifact, job);
        ingestJobStore.claim(job.id(), AVAILABLE_AT).orElseThrow();
        ingestJobStore.advance(
                job.id(), KnowledgeIngestJob.Status.UPLOADED,
                KnowledgeIngestJob.Status.CONVERTED,
                artifact.id(), null, null, null);
        ingestJobStore.claim(job.id(), AVAILABLE_AT).orElseThrow();

        Instant now = Instant.now();
        String conceptId = "it-todo12-doc-concept";
        String contentHash = KnowledgeIdentity.sha256("# Compiled");
        String revisionId = KnowledgeIdentity.revisionId(conceptId, 1, contentHash);
        KnowledgeRevision revision = new KnowledgeRevision(
                revisionId, conceptId, 1, "Compiled", null, "# Compiled",
                "it", now, contentHash, Map.of(), now);
        KnowledgeConcept concept = new KnowledgeConcept(
                conceptId, null, null, KnowledgeNamespaceType.COLLECTION, COLLECTION,
                KnowledgeConceptType.SOURCE_DOCUMENT, null, KnowledgeStatus.STABLE,
                revisionId, 1, null, now, now);
        KnowledgeSource source = new KnowledgeSource(
                revisionId, KnowledgeSourceType.KNOWLEDGE_ARTIFACT, artifact.id(),
                "cyrene://artifacts/" + artifact.id(), now, now);
        KnowledgeIndexTask indexTask = new KnowledgeIndexTask(
                null, conceptId, revisionId,
                KnowledgeIndexOperation.UPSERT_CURRENT, KnowledgeIndexTaskStatus.PENDING,
                0, now, null, null, null, now);

        KnowledgeIngestJob compiled = ingestJobStore.commitCompilation(
                job.id(), placeholderConceptId, new KnowledgeRevisionChange(
                        concept, 0, revision, List.of(source),
                        List.of(), List.of(), List.of(indexTask)));

        assertThat(compiled.status()).isEqualTo(KnowledgeIngestJob.Status.COMPILED);
        assertThat(compiled.sourceConceptId()).isEqualTo(conceptId);
        assertThat(compiled.sourceRevisionId()).isEqualTo(revisionId);
        assertThat(new MysqlKnowledgeRepository(objectMapper).findById(conceptId))
                .hasValueSatisfying(head -> {
                    assertThat(head.currentRevision().id()).isEqualTo(revisionId);
                    assertThat(head.currentRevision().revisionNumber()).isEqualTo(1);
                    assertThat(head.currentRevision().body()).isEqualTo("# Compiled");
                    assertThat(head.concept().status()).isEqualTo(KnowledgeStatus.STABLE);
                });
    }

    private static KnowledgeArtifact artifact(String fileName, String content) {
        String contentHash = KnowledgeIdentity.sha256(content);
        String id = KnowledgeIdentity.artifactId(
                null, COLLECTION, KnowledgeArtifactType.SOURCE_FILE, contentHash);
        return new KnowledgeArtifact(
                id, null, COLLECTION, KnowledgeArtifactType.SOURCE_FILE,
                fileName, "text/markdown", contentHash,
                "file:///it-artifacts/" + id, KnowledgeArtifact.Status.ACTIVE, AVAILABLE_AT);
    }

    private static KnowledgeIngestJob job(String id, String artifactId) {
        return job(id, artifactId, null);
    }

    private static KnowledgeIngestJob job(
            String id,
            String artifactId,
            String sourceConceptId
    ) {
        return new KnowledgeIngestJob(
                id, artifactId, null, COLLECTION, KnowledgeIngestJob.Status.UPLOADED,
                0, AVAILABLE_AT, null, null, sourceConceptId, null, null, AVAILABLE_AT, null);
    }

    private static void cleanTestRows() throws SQLException {
        try (Connection connection = MysqlConnectionPool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                execute(connection,
                        "DELETE FROM knowledge_tasks WHERE concept_id LIKE ?",
                        "it-todo12-%");
                execute(connection,
                        "DELETE FROM knowledge_tasks WHERE collection_key = ?", COLLECTION);
                execute(connection,
                        "DELETE FROM knowledge_metadata WHERE id LIKE ?",
                        "it-todo12-%");
                execute(connection,
                        "DELETE FROM user_preferences WHERE id LIKE ?",
                        "it-todo12-%");
                execute(connection,
                        "DELETE FROM knowledge_artifacts WHERE collection_key = ?", COLLECTION);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object parameter)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            statement.executeUpdate();
        }
    }
}
