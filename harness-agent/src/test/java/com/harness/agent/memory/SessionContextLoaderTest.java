package com.harness.agent.memory;

import com.harness.core.model.MemoryMessage;
import com.harness.core.model.MessageBlock;
import com.harness.core.runtime.RunTrace;
import com.harness.input.memory.InMemorySessionMessageCache;
import com.harness.input.memory.MessageStore;
import com.harness.input.memory.SessionCacheLookup;
import com.harness.input.memory.SessionCacheMetrics;
import com.harness.input.memory.SessionMessageCache;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionContextLoaderTest {

    @Test
    void load_cacheMiss_readsDatabaseRefillsCacheAndRecordsBothObservationLayers() {
        InMemorySessionMessageCache cache = new InMemorySessionMessageCache();
        MessageStore store = mock(MessageStore.class);
        RunTrace trace = mock(RunTrace.class);
        List<MemoryMessage> storedMessages = List.of(message("s1", "hello"));
        when(store.loadForContext("s1")).thenReturn(storedMessages);
        SessionContextLoader loader = new SessionContextLoader(cache, store);

        List<MemoryMessage> loaded = loader.load("s1", "u1", trace);

        assertThat(loaded).containsExactlyElementsOf(storedMessages);
        assertThat(cache.getIfPresent("s1")).containsExactlyElementsOf(storedMessages);
        SessionCacheMetrics.Snapshot metrics = cache.metricsSnapshot();
        assertThat(metrics.lookupTotals().get(SessionCacheLookup.Outcome.MISS)).isEqualTo(1);
        assertThat(metrics.refillTotal()).isEqualTo(1);
        assertThat(metrics.loadLatencies().get(SessionCacheMetrics.LoadSource.DATABASE).count())
                .isEqualTo(1);

        ArgumentCaptor<Map<String, String>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(trace).putMetadata(metadata.capture());
        assertThat(metadata.getValue())
                .containsEntry("sessionCacheHit", "false")
                .containsEntry("sessionCacheLookupOutcome", "miss")
                .containsEntry("sessionCacheBackend", "memory")
                .containsEntry("contextLoadSource", "database")
                .containsEntry("loadedMessageCount", "1")
                .containsEntry("cacheRefillCount", "1");
    }

    @Test
    void load_cacheHit_avoidsDatabaseAndRecordsCacheLatency() {
        InMemorySessionMessageCache cache = new InMemorySessionMessageCache();
        cache.put("s1", "u1", List.of(message("s1", "cached")));
        MessageStore store = mock(MessageStore.class);
        RunTrace trace = mock(RunTrace.class);
        SessionContextLoader loader = new SessionContextLoader(cache, store);

        List<MemoryMessage> loaded = loader.load("s1", "u1", trace);

        assertThat(loaded).extracting(MemoryMessage::text).containsExactly("cached");
        SessionCacheMetrics.Snapshot metrics = cache.metricsSnapshot();
        assertThat(metrics.lookupTotals().get(SessionCacheLookup.Outcome.HIT)).isEqualTo(1);
        assertThat(metrics.refillTotal()).isZero();
        assertThat(metrics.loadLatencies().get(SessionCacheMetrics.LoadSource.CACHE).count())
                .isEqualTo(1);
    }

    @Test
    void load_cacheError_isDistinctFromMissAndStillUsesDatabase() {
        SessionMessageCache cache = mock(SessionMessageCache.class);
        SessionCacheMetrics metrics = new SessionCacheMetrics("redis");
        MessageStore store = mock(MessageStore.class);
        RunTrace trace = mock(RunTrace.class);
        when(cache.lookup("s1")).thenReturn(SessionCacheLookup.error());
        when(cache.metrics()).thenReturn(metrics);
        when(store.loadForContext("s1")).thenReturn(List.of());
        SessionContextLoader loader = new SessionContextLoader(cache, store);

        loader.load("s1", "u1", trace);

        assertThat(metrics.snapshot(0, 0).lookupTotals()
                .get(SessionCacheLookup.Outcome.ERROR)).isEqualTo(1);
        assertThat(metrics.snapshot(0, 0).refillTotal()).isZero();
        ArgumentCaptor<Map<String, String>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(trace).putMetadata(metadata.capture());
        assertThat(metadata.getValue())
                .containsEntry("sessionCacheLookupOutcome", "error")
                .containsEntry("contextLoadSource", "database")
                .containsEntry("cacheRefillCount", "0");
    }

    private static MemoryMessage message(String sessionId, String text) {
        return new MemoryMessage(
                1,
                sessionId,
                "user",
                List.of(new MessageBlock(MessageBlock.BlockType.TEXT, text, null)),
                false,
                Instant.now());
    }
}
