package com.harness.input.memory;

import com.harness.core.model.MemoryMessage;
import com.harness.core.model.MessageBlock;
import com.harness.core.env.EnvConfig;
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
        EnvConfig.init(Map.of("HARNESS_CACHE_SESSION_TTL_HOURS", "12"));
        cache = new InMemorySessionMessageCache();
    }

    private MemoryMessage msg(String role, String content) {
        return new MemoryMessage(0, null, null, role, List.of(new MessageBlock(MessageBlock.BlockType.TEXT, content, null)), false, Instant.now());
    }

    private List<MemoryMessage> msgs(int count) {
        List<MemoryMessage> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(msg("user", "Message " + i));
        }
        return list;
    }

    /** A cache whose entries expire immediately, for exercising TTL. */
    private InMemorySessionMessageCache immediateExpiryCache() {
        EnvConfig.init(Map.of("HARNESS_CACHE_SESSION_TTL_HOURS", "0"));
        return new InMemorySessionMessageCache();
    }

    // ---- Basic operations ----

    @Test
    void put_andGetIfPresent() {
        cache.put("s1", "u1", List.of(msg("user", "Hello")));

        List<MemoryMessage> result = cache.getIfPresent("s1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).text()).isEqualTo("Hello");
    }

    @Test
    void getIfPresent_miss_returnsNull() {
        assertThat(cache.getIfPresent("nonexistent")).isNull();
    }

    @Test
    void append_addsToExisting() {
        cache.put("s1", "u1", List.of(msg("user", "First")));

        cache.appendIfPresent("s1", "u1", msg("assistant", "Second"));

        assertThat(cache.getIfPresent("s1")).hasSize(2);
        assertThat(cache.getIfPresent("s1").get(1).text()).isEqualTo("Second");
    }

    @Test
    void append_toMissingSession_staysMissing() {
        cache.appendIfPresent("s1", "u1", msg("user", "First"));

        // A miss must stay a miss. Rebuilding the entry from this single message would publish
        // a history starting mid-conversation — for a Tool round that leaves tool results with
        // no declaring assistant message, which providers reject. The next lookup() reloads the
        // full history from the store instead.
        assertThat(cache.getIfPresent("s1")).isNull();
        assertThat(cache.size()).isEqualTo(0);
    }

    @Test
    void append_afterRemoval_staysMissingUntilNextPut() {
        cache.put("s1", "u1", msgs(2));
        cache.remove("s1");

        cache.appendIfPresent("s1", "u1", msg("assistant", "tail"));

        assertThat(cache.getIfPresent("s1")).isNull();

        cache.put("s1", "u1", msgs(2));
        assertThat(cache.getIfPresent("s1")).hasSize(2);
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

    // ---- No active eviction ----

    @Test
    void manySessionsForOneUser_areAllKept() {
        // The cache used to evict a user's oldest session past a per-user count and memory
        // cap. Both are gone: entries now disappear only by TTL, and MySQL holds the truth.
        for (int i = 0; i < 25; i++) {
            cache.put("s" + i, "u1", msgs(20));
        }

        assertThat(cache.size()).isEqualTo(25);
        assertThat(cache.getIfPresent("s0")).isNotNull();
        assertThat(cache.getIfPresent("s24")).isNotNull();
    }

    @Test
    void onEvict_calledOnRemove() {
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

    // ---- TTL expiry ----

    @Test
    void evictExpired_removesExpiredSessions() {
        InMemorySessionMessageCache shortTtlCache = immediateExpiryCache();
        shortTtlCache.put("s1", "u1", msgs(1));
        sleep(10);

        int evicted = shortTtlCache.evictExpired();

        assertThat(evicted).isEqualTo(1);
        assertThat(shortTtlCache.getIfPresent("s1")).isNull();
    }

    @Test
    void getIfPresent_expiredSession_returnsNull() {
        InMemorySessionMessageCache shortTtlCache = immediateExpiryCache();
        shortTtlCache.put("s1", "u1", msgs(1));
        sleep(10);

        assertThat(shortTtlCache.getIfPresent("s1")).isNull();
    }

    @Test
    void evictExpired_keepsSessionsThatWereNotIdleLongEnough() {
        cache.put("s1", "u1", msgs(1));

        assertThat(cache.evictExpired()).isEqualTo(0);
        assertThat(cache.getIfPresent("s1")).isNotNull();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
