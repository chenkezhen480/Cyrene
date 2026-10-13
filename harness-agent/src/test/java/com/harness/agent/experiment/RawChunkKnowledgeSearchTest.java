package com.harness.agent.experiment;

import com.harness.agent.knowledge.KnowledgeToolRuntimeContext;
import com.harness.core.knowledge.*;
import com.harness.core.model.KnowledgeRequestContext;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class RawChunkKnowledgeSearchTest {
    private final Instant now = Instant.parse("2026-10-12T00:00:00Z");

    @Test
    void rawBaselineKeepsOnlyCurrentLiveAuthorizedDocuments() {
        var scope = new KnowledgeToolRuntimeContext("fixtureTenant", "alpha",
                new KnowledgeRequestContext("experiment", Set.of("live", "expired", "foreign", "old")),
                null, Set.of("knowledge_search", "knowledge_read"), null);
        var live = head("live", "fixtureTenant", "experiment", "r1", "r1", null, KnowledgeStatus.STABLE);
        var expired = head("expired", "fixtureTenant", "experiment", "r1", "r1", now, KnowledgeStatus.STABLE);
        var foreign = head("foreign", "anotherTenant", "experiment", "r1", "r1", null, KnowledgeStatus.STABLE);
        var old = head("old", "fixtureTenant", "experiment", "r2", null, null, KnowledgeStatus.STABLE);
        var unlisted = head("unlisted", "fixtureTenant", "experiment", "r1", "r1", null, KnowledgeStatus.STABLE);
        var wrongCollection = head("live", "fixtureTenant", "other", "r1", "r1", null, KnowledgeStatus.STABLE);
        var deprecated = head("live", "fixtureTenant", "experiment", "r1", "r1", null, KnowledgeStatus.DEPRECATED);
        assertThat(RawChunkKnowledgeSearch.eligibleDocuments(
                List.of(live, expired, foreign, old, unlisted, wrongCollection, deprecated),
                scope, "experiment", Clock.fixed(now, ZoneOffset.UTC)))
                .containsOnlyKeys("live");
    }

    private KnowledgeHead head(String id, String tenant, String collection, String current,
                               String loaded, Instant stale, KnowledgeStatus status) {
        var concept = new KnowledgeConcept(id, tenant, null, KnowledgeNamespaceType.COLLECTION,
                collection, KnowledgeConceptType.SOURCE_DOCUMENT, null, status, current, 1,
                stale, now, now);
        var revision = loaded == null ? null : new KnowledgeRevision(loaded, id, 1, id, id, "", "experiment",
                now, "hash", java.util.Map.of(), now);
        return new KnowledgeHead(concept, revision);
    }
}
