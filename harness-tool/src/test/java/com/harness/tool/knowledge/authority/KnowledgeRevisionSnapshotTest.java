package com.harness.tool.knowledge.authority;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.knowledge.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRevisionSnapshotTest {
    @Test void vectorMetadataKeepsProvenanceWithoutDuplicatingDocumentBody() {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var now = Instant.parse("2026-09-10T00:00:00.123456789Z");
        var revision = new KnowledgeRevision("rev", "doc", 1, "Title", "Summary", "正文\n\n🙂",
                "ingest", now, "hash", Map.of("graphId", "graph"), now);
        var source = new KnowledgeSource("rev", KnowledgeSourceType.SESSION_MESSAGE, "17", "message:17", now, now);
        var snapshot = new KnowledgeRevisionSnapshot(KnowledgeConceptType.SOURCE_DOCUMENT, "docs", revision,
                List.of(source), List.of());
        var stored = KnowledgeRevisionSnapshot.fromJson(mapper, snapshot.withBody("").toJson(mapper));
        assertThat(stored.revision().body()).isEmpty();
        assertThat(stored.sourceKeys()).containsExactly("SESSION_MESSAGE:17");
        assertThat(stored.sources()).containsExactly(source);
        assertThat(stored.withBody(revision.body())).isEqualTo(snapshot);
    }
}
