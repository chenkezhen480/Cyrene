package com.harness.server;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.env.MysqlConnectionPool;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "HARNESS_TEST_MYSQL_URL", matches = "jdbc:mysql://[^/]+/[^?]*_test[^?]*(\\?.*)?")
class MysqlSchemaUpgradeIT {
    @Test
    void unifiedSchemaUpgradesLegacyDataAndCanBeRepeatedWithoutChangingPermissions() throws Exception {
        String adminUrl = System.getenv("HARNESS_TEST_MYSQL_URL");
        String user = System.getenv("HARNESS_TEST_MYSQL_USER");
        String password = System.getenv("HARNESS_TEST_MYSQL_PASSWORD");
        String database = "cyrene_schema_it_" + UUID.randomUUID().toString().replace("-", "");
        String url = adminUrl.substring(0, adminUrl.indexOf('/', "jdbc:mysql://".length()) + 1)
                + database + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        String schema = Files.readString(Path.of("../sql/schema-mysql.sql"));
        try (var admin = DriverManager.getConnection(adminUrl, user, password); var ddl = admin.createStatement()) {
            ddl.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
            try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
                statement.execute(legacyDdl(schema, "sessions", List.of("tenant_id", "identity"), List.of("idx_session_memory_scan", "idx_session_owner_active")));
                statement.execute(legacyDdl(schema, "users", List.of("tenant_id", "identity"), List.of()));
                statement.execute(legacyDdl(schema, "messages", List.of("trace_id", "is_summary", "external_event_id"),
                        List.of("idx_messages_session_summary", "idx_message_session_trace", "uk_messages_external_event"))
                        .replace("`content`       JSON", "`content`       MEDIUMTEXT"));
                statement.execute(legacyDdl(schema, "agent_traces", List.of(), List.of("idx_trace_session_time", "idx_trace_owner_time")));
                statement.execute(legacyDdl(schema, "graph_space_bindings", List.of("description"), List.of()));
                statement.execute(legacyDdl(schema, "internal_api_permission", List.of("disabled"), List.of()));
                statement.execute("INSERT INTO sessions (id,user_id) VALUES ('session','owner')");
                statement.execute("INSERT INTO users (user_id,username,password_hash) VALUES ('owner','owner','oldHash')");
                statement.execute("INSERT INTO messages (session_id,role,content) VALUES ('session','user','你好'),"
                        + "('session','user','123'),('session','assistant','[{\"type\":\"TEXT\",\"text\":\"existing\"}]')");
                statement.execute("INSERT INTO agent_traces (trace_id,timestamp,full_json) VALUES ('trace',NOW(3),'{}')");
                statement.execute("INSERT INTO graph_space_bindings (tenant_id,graph_id,schema_id,permission) VALUES ('tenant','graph','schema','write')");
                statement.execute("INSERT INTO internal_api_permission (tenant_id,identity,endpoint_key) VALUES ('tenant','reader','trace.read')");
                applySchema(connection, schema);
                try (var rows = statement.executeQuery("SELECT JSON_UNQUOTE(JSON_EXTRACT(content,'$[0].text')) FROM messages ORDER BY id")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("你好");
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("123");
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("existing");
                    assertThat(rows.next()).isFalse();
                }
                try (var rows = statement.executeQuery("SELECT tenant_id,identity,password_hash FROM users WHERE user_id='owner'")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isNull();
                    assertThat(rows.getString(2)).isNull(); assertThat(rows.getString(3)).isEqualTo("oldHash");
                }
                try (var rows = statement.executeQuery("SELECT disabled FROM internal_api_permission")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getBoolean(1)).isFalse();
                }
                statement.execute("UPDATE internal_api_permission SET disabled=1");
                statement.execute("INSERT INTO messages (session_id,role,content,external_event_id) VALUES ('session','system',JSON_ARRAY(),'event')");
                applySchema(connection, schema);
                try (var rows = statement.executeQuery("SELECT disabled FROM internal_api_permission")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getBoolean(1)).isTrue();
                }
                try (var rows = statement.executeQuery("SELECT permission,description FROM graph_space_bindings")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("write"); assertThat(rows.getString(2)).isEmpty();
                }
                assertThatThrownBy(() -> statement.execute("INSERT INTO messages (session_id,role,content,external_event_id) "
                        + "VALUES ('session','system',JSON_ARRAY(),'event')")).isInstanceOf(java.sql.SQLException.class);
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(12);
                }
                verifyPoolAuthentication(url, user, password);
            } finally {
                if (!database.matches("cyrene_schema_it_[0-9a-f]{32}")) throw new IllegalStateException("Unsafe schema test target");
                ddl.execute("DROP DATABASE " + database);
            }
        }
    }

    private static String legacyDdl(String schema, String table, List<String> columns, List<String> indexes) {
        var match = Pattern.compile("CREATE TABLE IF NOT EXISTS `?" + table + "`? \\([\\s\\S]*?;").matcher(schema);
        if (!match.find()) throw new IllegalStateException("Missing table " + table);
        String ddl = match.group();
        for (String column : columns) ddl = ddl.replaceAll("(?m)^\\s*`?" + column + "`? [^\\r\\n]*\\R", "");
        for (String index : indexes) ddl = ddl.replaceAll("(?m)^\\s*(?:INDEX|UNIQUE KEY|UNIQUE INDEX) `?" + index + "`?[^\\r\\n]*\\R", "");
        return ddl.replaceAll(",\\s*\\)", "\n)");
    }

    private static void applySchema(Connection connection, String schema) throws Exception {
        try (var statement = connection.createStatement()) {
            for (String sql : schema.replaceAll("(?m)^--.*$", "").split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
        }
    }

    private static void verifyPoolAuthentication(String url, String user, String password) throws Exception {
        MysqlConnectionPool.shutdown();
        try {
            EnvConfig.init(Map.of(EnvKey.AUDIT_DB_URL, url, EnvKey.AUDIT_DB_USER, user,
                    EnvKey.AUDIT_DB_PASS, "invalidTestPassword"));
            assertThatThrownBy(MysqlConnectionPool::init).hasMessageContaining("MySQL")
                    .hasMessageContaining("认证失败").hasMessageNotContaining("未启动").hasMessageNotContaining("invalidTestPassword");
            EnvConfig.init(Map.of(EnvKey.AUDIT_DB_URL, url, EnvKey.AUDIT_DB_USER, user, EnvKey.AUDIT_DB_PASS, password));
            MysqlConnectionPool.init();
            MysqlConnectionPool.init();
            try (var connection = MysqlConnectionPool.getConnection()) { assertThat(connection.isValid(1)).isTrue(); }
        } finally { MysqlConnectionPool.shutdown(); }
    }
}
