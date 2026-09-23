package com.harness.tool.knowledge.authority;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.knowledge.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "CYRENE_TEST_MYSQL_URL", matches = ".+")
class MysqlCompactKnowledgeIT {
    @Test void compactSchemaKeepsPaginationTransactionsAndComments() throws Exception {
        String adminUrl = System.getenv("CYRENE_TEST_MYSQL_URL");
        String user = System.getenv("CYRENE_TEST_MYSQL_USER");
        String password = System.getenv("CYRENE_TEST_MYSQL_PASSWORD");
        String database = "cyrene_knowledge_it_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(database).matches("cyrene_knowledge_it_[a-f0-9]{32}");
        try (var admin = DriverManager.getConnection(adminUrl, user, password);
             var ddl = admin.createStatement()) {
            ddl.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
            try {
                String url = adminUrl.substring(0, adminUrl.indexOf('/', "jdbc:mysql://".length()) + 1)
                        + database + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
                try (var connection = DriverManager.getConnection(url, user, password);
                     var statement = connection.createStatement()) {
                    String schema = Files.readString(Path.of("..", "sql", "schema-mysql.sql"))
                            .replaceAll("(?m)^--.*$", "");
                    for (String command : schema.split(";")) {
                        if (!command.isBlank()) statement.execute(command);
                    }
                    try (var tables = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = '" + database + "' AND table_comment <> ''")) {
                        tables.next();
                        assertThat(tables.getInt(1)).isEqualTo(10);
                    }
                    try (var columns = statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_schema = '" + database + "' AND column_comment = ''")) {
                        columns.next();
                        assertThat(columns.getInt(1)).isZero();
                    }
                }
                var repository = new MysqlKnowledgeRepository(
                        () -> DriverManager.getConnection(url, user, password), new ObjectMapper());
                Instant now = Instant.parse("2026-09-09T00:00:00Z");
                var revision = new KnowledgeRevision("rev1", "concept1", 1, "Title", "Summary", "Body",
                        "test", now, "hash", Map.of(), now);
                var concept = new KnowledgeConcept("concept1", "tenant1", null,
                        KnowledgeNamespaceType.OPERATION_MEMORY, null, KnowledgeConceptType.OPERATION_PLAYBOOK,
                        "procedure", KnowledgeStatus.STABLE, "rev1", 1, null, now, now);
                var verification = new KnowledgeVerification(null, "rev1", "process:test",
                        KnowledgeVerificationType.values()[0], KnowledgeVerificationResult.values()[0], "checked", now);
                var change = new KnowledgeRevisionChange(concept, 0, revision, List.of(),
                        List.of(verification, verification), List.of(
                        new KnowledgeLink("concept1", "related1", KnowledgeLinkType.RELATED_TO, now),
                        new KnowledgeLink("concept1", "related2", KnowledgeLinkType.RELATED_TO, now)), List.of());
                repository.commitChanges(List.of(change));
                var first = repository.findVerificationPage("rev1", null, 1);
                assertThat(first.pageInfo().hasMore()).isTrue();
                assertThat(repository.findVerificationPage("rev1",
                        KnowledgeVerificationCursor.from(first.items().getFirst()), 1).items()).hasSize(1);
                var links = repository.findOutgoingLinkPage("concept1", null, 1);
                assertThat(links.pageInfo().hasMore()).isTrue();
                assertThat(repository.findOutgoingLinkPage("concept1",
                        KnowledgeLinkCursor.outgoing(links.items().getFirst()), 1).items())
                        .extracting(KnowledgeLink::toConceptId).containsExactly("related2");
                assertThat(repository.findIncomingLinkPage("related1", null, 1).items()).hasSize(1);
                assertThatThrownBy(() -> repository.commitChanges(List.of(change)))
                        .isInstanceOf(KnowledgePersistenceException.class);
                assertThat(repository.findRevisionPage("concept1", null, 10).items()).hasSize(1);
                assertThat(repository.findVerificationPage("rev1", null, 10).items()).hasSize(2);

                // findAuthorityByIds projects an explicit UNION of knowledge_metadata and
                // user_preferences (HEAD_COLUMNS). A column added to only one of the two tables,
                // or dropped from the projection, makes every Wiki read fail here with
                // "Cannot read knowledge authority" — so assert the head actually resolves.
                assertThat(repository.findAuthorityByIds(List.of("concept1")))
                        .containsKey("concept1");

                // A head row must carry the event time too: findAuthorityByIds projects an explicit
                // UNION, so a column added only to knowledge_metadata is silently absent there and
                // the same episode would read back with an event time in one path and none in the
                // other. Inserted raw to keep this about the projection, not episode validation.
                try (var connection = DriverManager.getConnection(url, user, password);
                     var insert = connection.createStatement()) {
                    insert.executeUpdate("INSERT INTO knowledge_metadata (id, tenant_id, user_id, "
                            + "namespace_type, concept_type, logical_key, status, current_version, "
                            + "route_type, route_data, version, event_time, created_at, updated_at, "
                            + "revision_metadata) VALUES ('ep1', 'tenant1', 'user1', 'USER_MEMORY', "
                            + "'USER_EPISODE', 'ep1', 'stable', 'eprev1', 'USER_MEMORY', "
                            + "JSON_OBJECT(), 1, '2026-01-02 03:04:05', NOW(3), NOW(3), NULL)");
                }
                assertThat(repository.findAuthorityByIds(List.of("ep1")).get("ep1").concept().eventTime())
                        .as("the authority head must carry the event time it was written with")
                        .isEqualTo(java.time.Instant.parse("2026-01-02T03:04:05Z"));

                // The same mapper serves user_preferences, which has no event_time column at all,
                // so a preference read must not require it. Inserted raw because the point is the
                // read path, not preference validation.
                try (var connection = DriverManager.getConnection(url, user, password);
                     var insert = connection.createStatement()) {
                    insert.executeUpdate("INSERT INTO user_preferences (id, tenant_id, user_id, "
                            + "namespace_type, concept_type, logical_key, status, current_revision_id, "
                            + "version, created_at, updated_at, snapshot) VALUES ('pref1', 'tenant1', "
                            + "'user1', 'USER_MEMORY', 'USER_PREFERENCE', 'response.language', 'stable', "
                            + "'prefrev1', 1, NOW(3), NOW(3), JSON_OBJECT())");
                }
                assertThat(repository.findPage("tenant1", "user1", KnowledgeNamespaceType.USER_MEMORY,
                        KnowledgeConceptType.USER_PREFERENCE, KnowledgeStatus.STABLE, null, 10)
                        .items()).singleElement()
                        .satisfies(preference -> assertThat(preference.eventTime()).isNull());

                String preferenceId = KnowledgeIdentity.preferenceConceptId("tenant1", "user1", "response.verbosity");
                var preferenceRevision = new KnowledgeRevision("prefrev2", preferenceId, 1,
                        "Response verbosity", "Use concise answers", "Use concise answers",
                        "test", now, "preference-hash", Map.of(), now);
                var preferenceConcept = new KnowledgeConcept(preferenceId, "tenant1", "user1",
                        KnowledgeNamespaceType.USER_MEMORY, null, KnowledgeConceptType.USER_PREFERENCE,
                        "response.verbosity", KnowledgeStatus.STABLE, preferenceRevision.id(), 1, null, now, now);
                repository.commitChanges(List.of(new KnowledgeRevisionChange(preferenceConcept, 0,
                        preferenceRevision, List.of(), List.of(), List.of(), List.of())));
                assertThat(repository.findById(preferenceId).orElseThrow().concept())
                        .satisfies(saved -> {
                            assertThat(saved.currentRevisionId()).isEqualTo(preferenceRevision.id());
                            assertThat(saved.eventTime()).isNull();
                        });
            } finally {
                ddl.execute("DROP DATABASE " + database);
            }
        }
    }
}
