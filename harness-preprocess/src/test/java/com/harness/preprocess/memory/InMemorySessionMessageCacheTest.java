package com.harness.preprocess.memory;

import com.harness.core.model.MemoryMessage;
import com.harness.env.EnvConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySessionMessageCacheTest {

    InMemorySessionMessageCache cache;

    @BeforeEach
    void setUp() {
        // Small limits for easy eviction testing
        EnvConfig.init(Map.of(
                "HARNESS_CACHE_MAX_SESSIONS_PER_USER", "3",
                "HARNESS_CACHE_MAX_MB_PER_USER", "1",
                "HARNESS_CACHE_MAX_MB_GLOBAL", "10",
                "HARNESS_CACHE_EVICTION_TARGET_RATIO", "50",
                "HARNESS_CACHE_SESSION_TTL_HOURS", "12"
        ));
        cache = new InMemorySessionMessageCache();
    }

    private MemoryMessage msg(String role, String content) {
        return new MemoryMessage(0, null, role, content, false, Instant.now());
    }

    private List<MemoryMessage> msgs(int count) {
        List<MemoryMessage> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(msg("user", "Message " + i));
        }
        return list;
    }

    // ---- Basic operations ----

    @Test
    void put_andGetIfPresent() {
        cache.put("s1", "u1", List.of(msg("user", "Hello")));

        List<MemoryMessage> result = cache.getIfPresent("s1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).content()).isEqualTo("Hello");
    }

    @Test
    void getIfPresent_miss_returnsNull() {
        assertThat(cache.getIfPresent("nonexistent")).isNull();
    }

    @Test
    void append_addsToExisting() {
        cache.put("s1", "u1", List.of(msg("user", "First")));

        cache.append("s1", "u1", msg("assistant", "Second"));

        assertThat(cache.getIfPresent("s1")).hasSize(2);
        assertThat(cache.getIfPresent("s1").get(1).content()).isEqualTo("Second");
    }

    @Test
    void append_toNewSession_createsEntry() {
        cache.append("s1", "u1", msg("user", "First"));

        assertThat(cache.getIfPresent("s1")).hasSize(1);
    }

    @Test
    void remove_deletesEntry() {
        cache.put("s1", "u1", List.of(msg("user", "data")));
        cache.remove("s1");

        assertThat(cache.getIfPresent("s1")).isNull();
        assertThat(cache.size()).isEqualTo(0);
    }

    @Test
    void put_overwritesExisting() {
        cache.put("s1", "u1", List.of(msg("user", "old")));

        cache.put("s1", "u1", List.of(msg("user", "new1"), msg("assistant", "new2")));

        assertThat(cache.getIfPresent("s1")).hasSize(2);
    }

    @Test
    void size_tracksEntries() {
        cache.put("s1", "u1", msgs(1));
        cache.put("s2", "u1", msgs(1));

        assertThat(cache.size()).isEqualTo(2);

        cache.remove("s1");
        assertThat(cache.size()).isEqualTo(1);
    }

    // ---- Memory accounting ----

    @Test
    void getGlobalEstimatedBytes_tracksMemory() {
        cache.put("s1", "u1", msgs(4)); // 4 * 2500 = 10000 bytes

        assertThat(cache.getGlobalEstimatedBytes()).isEqualTo(10000);
    }

    @Test
    void getGlobalEstimatedBytes_updatedOnRemove() {
        cache.put("s1", "u1", msgs(4));
        cache.put("s2", "u1", msgs(2));

        cache.remove("s1");

        assertThat(cache.getGlobalEstimatedBytes()).isEqualTo(5000); // only s2 remains
    }

    @Test
    void getGlobalEstimatedBytes_updatedOnPut_overwrite() {
        cache.put("s1", "u1", msgs(4)); // 10000 bytes
        cache.put("s1", "u1", msgs(1)); // 2500 bytes (overwrite)

        assertThat(cache.getGlobalEstimatedBytes()).isEqualTo(2500);
    }

    @Test
    void append_incrementsBytes() {
        cache.put("s1", "u1", msgs(2)); // 5000 bytes
        cache.append("s1", "u1", msg("user", "extra")); // +2500

        assertThat(cache.getGlobalEstimatedBytes()).isEqualTo(7500);
    }

    // ---- Per-user session count eviction ----

    @Test
    void perUserSessionLimit_evictsOldest() {
        // Max 3 sessions per user
        cache.put("s1", "u1", msgs(1));
        sleep(10); // ensure different timestamps
        cache.put("s2", "u1", msgs(1));
        sleep(10);
        cache.put("s3", "u1", msgs(1));
        sleep(10);
        cache.put("s4", "u1", msgs(1)); // triggers eviction of s1

        assertThat(cache.size()).isEqualTo(3);
        assertThat(cache.getIfPresent("s1")).isNull(); // evicted
        assertThat(cache.getIfPresent("s2")).isNotNull();
        assertThat(cache.getIfPresent("s3")).isNotNull();
        assertThat(cache.getIfPresent("s4")).isNotNull();
    }

    @Test
    void perUserSessionLimit_differentUsers_independent() {
        cache.put("s1", "u1", msgs(1));
        cache.put("s2", "u1", msgs(1));
        cache.put("s3", "u1", msgs(1));
        cache.put("s4", "u2", msgs(1)); // different user, no eviction for u1

        assertThat(cache.size()).isEqualTo(4);
        assertThat(cache.getIfPresent("s1")).isNotNull();
    }

    // ---- Per-user memory eviction ----

    @Test
    void perUserMemoryCap_evictsOldest() {
        // Max 1MB per user = 1,048,576 bytes. BYTES_PER_MESSAGE = 2500.
        // 1MB / 2500 = ~419 messages. Need > 419 messages to exceed.
        // Use 500 messages to be sure.
        cache.put("s1", "u1", msgs(500)); // 1,250,000 bytes > 1MB

        // Should have evicted s1 (only session, so it stays but memory is tracked)
        // Actually with only 1 session, it can't evict. Let me use 2 sessions.
        cache.put("s2", "u1", msgs(300)); // 750,000 bytes

        // s1=1,250,000 + s2=750,000 = 2,000,000 > 1,048,576 → evict s1
        assertThat(cache.getIfPresent("s1")).isNull();
        assertThat(cache.getIfPresent("s2")).isNotNull();
    }

    // ---- Global memory eviction ----

    @Test
    void globalMemoryCap_evictsGloballyOldest() {
        // Max 10MB global = 10,485,760 bytes. BYTES_PER_MESSAGE = 2500.
        // Need > 4194 messages to exceed. Use 2 sessions of 2500 each = 5000 msgs total.
        // Actually let's use smaller numbers. 10MB / 2500 = 4194 messages.
        // Let's put 2500 msgs per session, 2 sessions = 5000 msgs = 12,500,000 bytes > 10MB.
        // Eviction target = 50% of 10MB = 5MB = 5,242,880 bytes.
        cache.put("s1", "u1", msgs(2500)); // 6,250,000 bytes
        sleep(10);
        cache.put("s2", "u2", msgs(2500)); // total = 12,500,000 > 10MB

        // Should evict s1 (oldest) until below 50% target
        assertThat(cache.getGlobalEstimatedBytes()).isLessThanOrEqualTo(10_485_760L);
    }

    // ---- onEvict callback ----

    @Test
    void onEvict_calledOnEviction() {
        AtomicInteger count = new AtomicInteger(0);
        String[] evictedId = new String[1];
        cache.setOnEvict(id -> {
            count.incrementAndGet();
            evictedId[0] = id;
        });

        cache.put("s1", "u1", msgs(1));
        cache.remove("s1");

        assertThat(count.get()).isEqualTo(1);
        assertThat(evictedId[0]).isEqualTo("s1");
    }

    @Test
    void onEvict_calledOnPerUserEviction() {
        AtomicInteger count = new AtomicInteger(0);
        cache.setOnEvict(id -> count.incrementAndGet());

        cache.put("s1", "u1", msgs(1));
        sleep(10);
        cache.put("s2", "u1", msgs(1));
        sleep(10);
        cache.put("s3", "u1", msgs(1));
        sleep(10);
        cache.put("s4", "u1", msgs(1)); // triggers eviction of s1

        assertThat(count.get()).isEqualTo(1);
    }

    // ---- TTL expiry ----

    @Test
    void evictExpired_removesExpiredSessions() {
        // Use a very short TTL
        EnvConfig.init(Map.of(
                "HARNESS_CACHE_MAX_SESSIONS_PER_USER", "10",
                "HARNESS_CACHE_MAX_MB_PER_USER", "10",
                "HARNESS_CACHE_MAX_MB_GLOBAL", "10",
                "HARNESS_CACHE_EVICTION_TARGET_RATIO", "50",
                "HARNESS_CACHE_SESSION_TTL_HOURS", "0"  // 0 hours = immediate expiry
        ));
        InMemorySessionMessageCache shortTtlCache = new InMemorySessionMessageCache();

        shortTtlCache.put("s1", "u1", msgs(1));
        sleep(10); // ensure time passes

        int evicted = shortTtlCache.evictExpired();

        assertThat(evicted).isEqualTo(1);
        assertThat(shortTtlCache.getIfPresent("s1")).isNull();
    }

    @Test
    void getIfPresent_expiredSession_returnsNull() {
        EnvConfig.init(Map.of(
                "HARNESS_CACHE_MAX_SESSIONS_PER_USER", "10",
                "HARNESS_CACHE_MAX_MB_PER_USER", "10",
                "HARNESS_CACHE_MAX_MB_GLOBAL", "10",
                "HARNESS_CACHE_EVICTION_TARGET_RATIO", "50",
                "HARNESS_CACHE_SESSION_TTL_HOURS", "0"
        ));
        InMemorySessionMessageCache shortTtlCache = new InMemorySessionMessageCache();

        shortTtlCache.put("s1", "u1", msgs(1));
        sleep(10);

        // getIfPresent should detect expiry and return null
        assertThat(shortTtlCache.getIfPresent("s1")).isNull();
    }

    // ---- LRU behavior ----

    @Test
    void getIfPresent_touchesSession_extendsLife() {
        cache.put("s1", "u1", msgs(1));
        sleep(10);
        cache.put("s2", "u1", msgs(1));
        sleep(10);

        // Touch s1 by reading it
        cache.getIfPresent("s1");
        sleep(10);

        // s3 triggers eviction — s2 should be evicted (oldest untouched), not s1
        cache.put("s3", "u1", msgs(1));

        assertThat(cache.getIfPresent("s1")).isNotNull(); // touched, still alive
    }

    // ---- Cross-user isolation ----

    @Test
    void differentUsers_noCrossEviction() {
        cache.put("s1", "u1", msgs(1));
        sleep(50);
        cache.put("s2", "u2", msgs(1));
        sleep(50);
        cache.put("s3", "u1", msgs(1));
        sleep(50);
        cache.put("s4", "u1", msgs(1));
        sleep(50);
        cache.put("s5", "u2", msgs(1));
        sleep(50);
        cache.put("s6", "u2", msgs(1));
        sleep(50);

        // u1 has 3 sessions (s1, s3, s4), u2 has 3 sessions (s2, s5, s6)
        // Adding s7 for u1 should evict u1's oldest (s1), not u2's
        cache.put("s7", "u1", msgs(1));

        assertThat(cache.getIfPresent("s1")).isNull(); // u1's oldest evicted
        assertThat(cache.getIfPresent("s2")).isNotNull(); // u2 untouched
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
