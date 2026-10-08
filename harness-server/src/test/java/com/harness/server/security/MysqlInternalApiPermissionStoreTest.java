package com.harness.server.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Portable JDBC fixture; MySQL collation and isolation still require the dedicated IT. */
class MysqlInternalApiPermissionStoreTest {
    @TempDir Path directory;

    @Test
    void failedReplacementRollsBackAndPaginationKeepsScopeAndOrder() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("permissions.db");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE internal_api_permission (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "tenant_id TEXT NOT NULL, identity TEXT NOT NULL, endpoint_key TEXT NOT NULL "
                    + "CHECK(length(endpoint_key) <= 128), UNIQUE(tenant_id, identity, endpoint_key))");
        }
        var store = new MysqlInternalApiPermissionStore(() -> {
            var connection = DriverManager.getConnection(url);
            var fixture = spy(connection);
            doAnswer(call -> connection.prepareStatement(((String) call.getArgument(0)).replace(
                    "ON DUPLICATE KEY UPDATE endpoint_key = VALUES(endpoint_key)",
                    "ON CONFLICT(tenant_id, identity, endpoint_key) DO UPDATE SET endpoint_key = excluded.endpoint_key")))
                    .when(fixture).prepareStatement(anyString());
            return fixture;
        });
        store.replace("t1", "reader", Set.of("a", "b"));
        store.replace("t2", "reader", Set.of("private"));
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
        store.replace("t1", "reader", Set.of());
        assertThat(store.isAllowed("t1", "reader", "a")).isFalse();
        assertThat(store.isAllowed("t2", "reader", "private")).isTrue();
    }
}
