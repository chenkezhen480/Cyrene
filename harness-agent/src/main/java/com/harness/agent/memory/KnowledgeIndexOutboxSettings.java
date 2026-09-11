package com.harness.agent.memory;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;

import java.time.Duration;

/** Validated bounded Outbox worker settings. */
public record KnowledgeIndexOutboxSettings(
        int batchSize,
        int concurrency,
        Duration pollInterval,
        Duration stuckDuration,
        int maxAttempts
) {
    public KnowledgeIndexOutboxSettings {
        if (batchSize < 1 || batchSize > 500
                || concurrency < 1 || concurrency > 32
                || pollInterval == null || pollInterval.isNegative()
                || pollInterval.isZero() || pollInterval.compareTo(Duration.ofHours(1)) > 0
                || stuckDuration == null || stuckDuration.isNegative()
                || stuckDuration.isZero()
                || maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("invalid Knowledge index Outbox settings");
        }
    }

    public static KnowledgeIndexOutboxSettings fromEnvironment() {
        EnvConfig config = EnvConfig.get();
        return new KnowledgeIndexOutboxSettings(
                config.getInt(EnvKey.MEMORY_INDEX_OUTBOX_BATCH_SIZE, 100),
                config.getInt(EnvKey.MEMORY_INDEX_OUTBOX_CONCURRENCY, 2),
                Duration.ofSeconds(config.getLong(
                        EnvKey.MEMORY_INDEX_OUTBOX_POLL_SECONDS, 5)),
                Duration.ofMinutes(config.getLong(
                        EnvKey.MEMORY_INDEX_OUTBOX_STUCK_MINUTES, 30)),
                config.getInt(EnvKey.MEMORY_INDEX_OUTBOX_MAX_ATTEMPTS, 5));
    }
}
