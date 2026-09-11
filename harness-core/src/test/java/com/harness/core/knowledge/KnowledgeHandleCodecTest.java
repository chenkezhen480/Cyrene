package com.harness.core.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeHandleCodecTest {

    private final KnowledgeHandleCodec codec = new KnowledgeHandleCodec(new ObjectMapper());

    @Test
    void rejectsModifiedPayloadAndUnsignedHandles() {
        KnowledgeHandle handle = KnowledgeHandle.document(KnowledgeConceptType.SOURCE_DOCUMENT,
                "concept-1", "revision-1", "manuals", "concept-1", 0);
        String original = codec.encode(handle);
        String changed = codec.encode(KnowledgeHandle.document(KnowledgeConceptType.SOURCE_DOCUMENT,
                "concept-1", "revision-1", "manuals", "concept-1", 99));
        String forged = changed.substring(0, changed.lastIndexOf('.'))
                + original.substring(original.lastIndexOf('.'));
        assertThatThrownBy(() -> codec.decode(forged)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(original.substring(0, original.lastIndexOf('.'))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void documentHandle_roundTripsStableScopeWithoutOwnerClaims() {
        KnowledgeHandle handle = KnowledgeHandle.document(
                KnowledgeConceptType.SOURCE_DOCUMENT,
                "concept-1",
                "revision-2",
                "manuals",
                "concept-1",
                3);

        String encoded = codec.encode(handle);
        KnowledgeHandle decoded = codec.decode(encoded);

        assertThat(encoded).startsWith("kh1.");
        assertThat(decoded).isEqualTo(handle);
        assertThat(decoded.documentId()).isEqualTo(decoded.conceptId());
    }

    @Test
    void handle_rejectsIncompleteOrCrossRouteLocatorFields() {
        assertThatThrownBy(() -> KnowledgeHandle.document(
                KnowledgeConceptType.SOURCE_DOCUMENT,
                "concept-1",
                "revision-1",
                null,
                "concept-1",
                0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collectionKey");

        assertThatThrownBy(() -> new KnowledgeHandle(
                KnowledgeHandle.CURRENT_PROTOCOL_VERSION,
                KnowledgeConceptType.USER_EPISODE,
                "concept-1",
                "revision-1",
                KnowledgeRouteTarget.USER_MEMORY,
                "manuals",
                "document-1",
                0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-document");
    }

    @Test
    void codec_rejectsUnknownEnvelope() {
        assertThatThrownBy(() -> codec.decode("https://example.com/free-uri"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("envelope");
    }

    @Test
    void handle_rejectsKindAndRouteMismatch() {
        assertThatThrownBy(() -> KnowledgeHandle.concept(
                KnowledgeConceptType.SOURCE_DOCUMENT,
                "concept-1",
                "revision-1",
                KnowledgeRouteTarget.USER_MEMORY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DOCUMENT");
    }

    @Test
    void graphSchemaHandle_roundTripsAsGraphRoute() {
        KnowledgeHandle handle = KnowledgeHandle.concept(
                KnowledgeConceptType.GRAPH_SCHEMA,
                "schema-concept",
                "schema-revision",
                KnowledgeRouteTarget.GRAPH);

        assertThat(codec.decode(codec.encode(handle))).isEqualTo(handle);
    }

    @Test
    void handle_rejectsMysqlOnlyLongTermMemoryKinds() {
        assertThatThrownBy(() -> KnowledgeHandle.concept(
                KnowledgeConceptType.USER_PREFERENCE,
                "preference-1",
                "revision-1",
                KnowledgeRouteTarget.USER_MEMORY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("searchable Wiki Concepts");
    }
}
