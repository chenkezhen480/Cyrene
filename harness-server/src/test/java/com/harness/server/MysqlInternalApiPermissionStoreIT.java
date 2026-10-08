package com.harness.server;

import com.harness.server.security.MysqlInternalApiPermissionStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses connection-local temporary tables only; existing database rows are never modified. */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "HARNESS_TEST_MYSQL_URL", matches = "jdbc:mysql:.*")
class MysqlInternalApiPermissionStoreIT {
    @Test
    void mysqlEnforcesUniqueBinaryScopesRollbackRevocationAndStablePages() throws Exception {
        try (Connection connection = DriverManager.getConnection(System.getenv("HARNESS_TEST_MYSQL_URL"),
                System.getenv("HARNESS_TEST_MYSQL_USER"), System.getenv("HARNESS_TEST_MYSQL_PASSWORD"))) {
            String schema = Files.readString(Path.of("../sql/schema-mysql.sql"));
            int start = schema.indexOf("CREATE TABLE IF NOT EXISTS internal_api_permission");
            assertThat(start).isGreaterThanOrEqualTo(0);
            String ddl = schema.substring(start, schema.indexOf(';', start)).replace(
                    "CREATE TABLE IF NOT EXISTS", "CREATE TEMPORARY TABLE");
            try (var statement = connection.createStatement()) {
                statement.execute("SET SESSION sql_mode = 'STRICT_ALL_TABLES'");
                statement.execute(ddl);
                int profileStart = schema.indexOf("CREATE TABLE IF NOT EXISTS `tool_permission_profile`");
                statement.execute(schema.substring(profileStart, schema.indexOf(';', profileStart)).replace(
                        "CREATE TABLE IF NOT EXISTS", "CREATE TEMPORARY TABLE"));
                int sessionStart = schema.indexOf("CREATE TABLE IF NOT EXISTS `sessions`");
                statement.execute(schema.substring(sessionStart, schema.indexOf(';', sessionStart)).replace(
                        "CREATE TABLE IF NOT EXISTS", "CREATE TEMPORARY TABLE"));
            }
            // Store calls close their connection; retain this session so its temporary table survives.
            Connection fixture = spy(connection);
            doNothing().when(fixture).close();
            var sessions = new com.harness.input.memory.MysqlSessionStore(() -> fixture);
            var session = sessions.create("user-a", "t1");
            sessions.recordIdentity(session.id(), "user-a", "t1", "reader");
            assertThat(sessions.findByIdAndOwner(session.id(), "user-a", "t1").orElseThrow().identity())
                    .isEqualTo("reader");
            sessions.recordIdentity(session.id(), "user-a", "t1", "reader");
            assertThatThrownBy(() -> sessions.recordIdentity(session.id(), "user-a", "t2", "admin"))
                    .isInstanceOf(com.harness.input.memory.MemoryStoreException.class);
            var store = new MysqlInternalApiPermissionStore(() -> fixture);
            store.replace("t1", "reader", Set.of("a", "b"));
            store.replace("t2", "reader", Set.of("private"));
            try (var statement = connection.createStatement()) {
                statement.execute("INSERT INTO tool_permission_profile (tenant_id, identity, disabled_tools_json) "
                        + "VALUES ('t1', 'alpha', JSON_ARRAY()), "
                        + "('t1', 'reader', JSON_ARRAY()), ('t2', 'foreign', JSON_ARRAY())");
            }
            var profiles = new ToolPermissionStore(() -> fixture, new ObjectMapper());
            var identities = profiles.listIdentities("t1", null, 1);
            assertThat(identities.items()).containsExactly("alpha");
            assertThat(profiles.listIdentities("t1", identities.pageInfo().nextCursor(), 1).items()).containsExactly("reader");
            profiles.saveProfile("t1", "alpha", Set.of("web.search"));
            assertThat(profiles.findDisabledTools("t1", "alpha").orElseThrow()).containsExactly("web.search");
            assertThat(store.isAllowed("t1", "Reader", "a")).isFalse();
            assertThat(store.isAllowed("t1", "reader", "A")).isFalse();
            assertThat(store.isAllowed("t1", "reader", "private")).isFalse();
            var first = store.page("t1", "reader", 0, 1);
            assertThat(first.items()).extracting(MysqlInternalApiPermissionStore.PermissionView::endpointKey).containsExactly("a");
            assertThat(store.page("t1", "reader", Long.parseLong(first.pageInfo().nextCursor()), 1).items())
                    .extracting(MysqlInternalApiPermissionStore.PermissionView::endpointKey).containsExactly("b");
            assertThatThrownBy(() -> store.replace("t1", "reader", Set.of("new", "z".repeat(129))))
                    .isInstanceOf(MysqlInternalApiPermissionStore.PermissionStoreException.class);
            assertThat(store.isAllowed("t1", "reader", "a")).isTrue();
            assertThat(store.isAllowed("t1", "reader", "new")).isFalse();
            store.replace("t1", "reader", Set.of("a", "c"));
            assertThat(store.page("t1", "reader", 0, 1).items().getFirst().id()).isEqualTo(first.items().getFirst().id());
            connection.setAutoCommit(true);
            try (var statement = connection.prepareStatement("INSERT INTO internal_api_permission "
                    + "(tenant_id, identity, endpoint_key) VALUES ('t1', 'reader', 'a')")) {
                assertThatThrownBy(statement::executeUpdate).isInstanceOf(java.sql.SQLException.class);
            }
            store.replace("t1", "reader", Set.of());
            assertThat(store.isAllowed("t1", "reader", "a")).isFalse();
            assertThat(store.isAllowed("t2", "reader", "private")).isTrue();
        }
    }
}
