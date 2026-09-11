package com.harness.tool.knowledge.authority;

import com.harness.core.knowledge.KnowledgeSourceType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/** Prevents ordinary retention cleanup from deleting evidence used by a Revision. */
public final class KnowledgeSourcePurgeGuard {

    private final KnowledgeRepository repository;
    private final EnumMap<KnowledgeSourceType, LongAdder> retainedCounts =
            new EnumMap<>(KnowledgeSourceType.class);

    public KnowledgeSourcePurgeGuard(KnowledgeRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
        for (KnowledgeSourceType sourceType : KnowledgeSourceType.values()) {
            retainedCounts.put(sourceType, new LongAdder());
        }
    }

    /**
     * Returns true when an ordinary purge must retain the source. Each retained
     * decision increments a bounded metric labelled only by source type.
     */
    public boolean retainIfReferenced(KnowledgeSourceType sourceType, String sourceId) {
        Objects.requireNonNull(sourceType, "sourceType");
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId is required");
        }
        boolean referenced = repository.isSourceReferenced(sourceType, sourceId);
        if (referenced) {
            retainedCounts.get(sourceType).increment();
        }
        return referenced;
    }

    public Map<String, Long> metricsSnapshot() {
        Map<String, Long> snapshot = new java.util.LinkedHashMap<>();
        for (KnowledgeSourceType sourceType : KnowledgeSourceType.values()) {
            snapshot.put(sourceType.name(), retainedCounts.get(sourceType).sum());
        }
        return Map.copyOf(snapshot);
    }
}
