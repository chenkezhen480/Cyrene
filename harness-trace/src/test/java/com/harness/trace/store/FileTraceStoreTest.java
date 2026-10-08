package com.harness.trace.store;

import com.harness.core.model.AgentTrace;
import com.harness.core.model.TraceCursor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class FileTraceStoreTest {
    @TempDir Path tempDir;

    @Test
    void ownerPaginationStatisticsAndCleanupKeepForeignAndLegacyTraces() throws Exception {
        var store = new FileTraceStore(tempDir);
        var old = Instant.EPOCH;
        for (String id : List.of("a", "b", "c")) store.save(trace(id, "user-a", "tenant-a", old));
        store.save(trace("foreign-tenant", "user-a", "tenant-b", old));
        store.save(trace("foreign-user", "user-b", "tenant-a", old));
        store.save(trace("current", "user-a", "tenant-a", Instant.now()));
        store.save(AgentTrace.builder().traceId("legacy").userId("user-a").timestamp(old).build());
        var first = store.findByOwner("user-a", "tenant-a", null, 2);
        assertThat(first.items()).extracting(AgentTrace::traceId).containsExactly("current", "c");
        assertThat(first.pageInfo().hasMore()).isTrue();
        assertThat(first.pageInfo().nextCursor()).isEqualTo(old + "|c");
        assertThat(store.findByOwner("user-a", "tenant-a", new TraceCursor(old, "c"), 2).items())
                .extracting(AgentTrace::traceId).containsExactly("b", "a");
        assertThat(store.countByOwner("user-a", "tenant-a")).isEqualTo(4);
        assertThat(store.countByOwner("User-a", "tenant-a")).isZero();
        assertThat(store.cleanupByOwner("user-a", "tenant-a", 1)).isEqualTo(3);
        assertThat(store.count()).isEqualTo(4);
        assertThat(store.findById("foreign-tenant")).isPresent();
        assertThat(store.findById("foreign-user")).isPresent();
        assertThat(store.findById("legacy")).isPresent();
        assertThat(store.findById("missing")).isEmpty();
        assertThatThrownBy(() -> store.findById("../outside")).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(tempDir.resolve("corrupt.json"), "invalid-json");
        assertThatThrownBy(() -> store.findById("corrupt")).isInstanceOf(TraceStoreException.class);
        assertThatThrownBy(() -> store.cleanupByOwner("user-a", "tenant-a", 1)).isInstanceOf(TraceStoreException.class);
        assertThat(store.findById("current")).isPresent();
    }

    private static AgentTrace trace(String id, String user, String tenant, Instant time) {
        return AgentTrace.builder().traceId(id).sessionId("session").userId(user).timestamp(time)
                .metadata(Map.of("tenant_id", tenant)).build();
    }
}
