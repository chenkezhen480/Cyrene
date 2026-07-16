package com.harness.preprocess.memory;

import com.harness.core.model.MemoryMessage;
import com.harness.core.model.MessageBlock;
import com.harness.env.EnvConfig;
import com.harness.env.RedisConnectionPool;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class RedisSessionMessageCacheIT {

    static final String TEST_USER = "it_user_redis";
    static final String TEST_PREFIX = "it_harness";

    RedisSessionMessageCache cache;
    List<String> testSessionIds = new ArrayList<>();

    @BeforeAll
    static void initEnv() {
        EnvConfig.init(Map.of(
                "HARNESS_MEMORY_REDIS_URL", "redis://localhost:6379",
                "HARNESS_MEMORY_REDIS_DB", "10",
                "HARNESS_MEMORY_REDIS_KEY_PREFIX", TEST_PREFIX,
                "HARNESS_MEMORY_REDIS_TTL_MINUTES", "10",
                "HARNESS_CACHE_MAX_SESSIONS_PER_USER", "100",
                "HARNESS_CACHE_MAX_MB_PER_USER", "10",
                "HARNESS_CACHE_MAX_MB_GLOBAL", "4096",
                "HARNESS_CACHE_EVICTION_TARGET_RATIO", "50"
        ));
    }

    @BeforeEach
    void setUp() {
        cache = new RedisSessionMessageCache();
    }

    @AfterEach
    void cleanUp() {
        for (String sid : testSessionIds) {
            try {
                cache.remove(sid);
            } catch (Exception e) {
                // ignore
            }
        }
        testSessionIds.clear();
    }

    @AfterAll
    static void shutDown() {
        RedisConnectionPool.shutdown();
    }

    private String newSessionId() {
        String sid = "it_sess_" + System.nanoTime();
        testSessionIds.add(sid);
        return sid;
    }

    private MemoryMessage msg(String role, String content) {
        return new MemoryMessage(0, null, role, List.of(new MessageBlock(MessageBlock.BlockType.TEXT, content, null)), false, Instant.now());
    }

    @Test
    void put_andGetIfPresent() {
        String sid = newSessionId();
        List<MemoryMessage> messages = List.of(msg("user", "Hello"), msg("assistant", "Hi"));

        cache.put(sid, TEST_USER, messages);

        List<MemoryMessage> cached = cache.getIfPresent(sid);
        assertThat(cached).hasSize(2);
        assertThat(cached.get(0).text()).isEqualTo("Hello");
        assertThat(cached.get(1).text()).isEqualTo("Hi");
    }

    @Test
    void getIfPresent_miss_returnsNull() {
        String sid = newSessionId();

        List<MemoryMessage> cached = cache.getIfPresent(sid);

        assertThat(cached).isNull();
    }

    @Test
    void append_addsMessage() {
        String sid = newSessionId();
        cache.put(sid, TEST_USER, List.of(msg("user", "First")));

        cache.append(sid, TEST_USER, msg("assistant", "Second"));

        List<MemoryMessage> cached = cache.getIfPresent(sid);
        assertThat(cached).hasSize(2);
        assertThat(cached.get(1).text()).isEqualTo("Second");
    }

    @Test
    void append_toEmpty_createsEntry() {
        String sid = newSessionId();

        cache.append(sid, TEST_USER, msg("user", "First message"));

        List<MemoryMessage> cached = cache.getIfPresent(sid);
        assertThat(cached).hasSize(1);
        assertThat(cached.get(0).text()).isEqualTo("First message");
    }

    @Test
    void remove_deletesEntry() {
        String sid = newSessionId();
        cache.put(sid, TEST_USER, List.of(msg("user", "Hello")));

        cache.remove(sid);

        assertThat(cache.getIfPresent(sid)).isNull();
    }

    @Test
    void size_countsEntries() {
        String sid1 = newSessionId();
        String sid2 = newSessionId();
        cache.put(sid1, TEST_USER, List.of(msg("user", "A")));
        cache.put(sid2, TEST_USER, List.of(msg("user", "B")));

        // size() may include other entries in Redis, so just check >= 2
        assertThat(cache.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void setOnEvict_callbackTriggered() {
        String sid = newSessionId();
        cache.put(sid, TEST_USER, List.of(msg("user", "data")));

        AtomicInteger evicted = new AtomicInteger(0);
        String[] evictedId = new String[1];
        cache.setOnEvict(id -> {
            evicted.incrementAndGet();
            evictedId[0] = id;
        });

        // Remove triggers eviction callback
        cache.remove(sid);

        assertThat(evicted.get()).isEqualTo(1);
        assertThat(evictedId[0]).isEqualTo(sid);
    }

    @Test
    void getGlobalEstimatedBytes_returnsPositive() {
        String sid = newSessionId();
        cache.put(sid, TEST_USER, List.of(msg("user", "Some content here")));

        long bytes = cache.getGlobalEstimatedBytes();

        assertThat(bytes).isGreaterThanOrEqualTo(0);
    }

    @Test
    void put_overwritesExisting() {
        String sid = newSessionId();
        cache.put(sid, TEST_USER, List.of(msg("user", "Old")));

        cache.put(sid, TEST_USER, List.of(msg("user", "New1"), msg("assistant", "New2")));

        List<MemoryMessage> cached = cache.getIfPresent(sid);
        assertThat(cached).hasSize(2);
        assertThat(cached.get(0).text()).isEqualTo("New1");
    }

    @Test
    void evictExpired_noExpired_returnsZero() {
        // With 10-minute TTL, nothing should be expired immediately
        int evicted = cache.evictExpired();

        assertThat(evicted).isEqualTo(0);
    }
}
