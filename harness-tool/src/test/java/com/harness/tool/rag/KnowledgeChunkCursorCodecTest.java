package com.harness.tool.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeChunkCursorCodecTest {

    @Test
    void cursorRoundTripBindsCollectionFilterAndLastSortKey() {
        String cursor = KnowledgeChunkCursorCodec.encode(
                "manuals", " upload ", "chunk-0002");

        assertThat(cursor).doesNotContain("manuals", "upload", "chunk-0002");
        assertThat(KnowledgeChunkCursorCodec.decodeLastId(
                cursor, "manuals", "upload"))
                .isEqualTo("chunk-0002");
    }

    @Test
    void rejectsCursorReusedWithDifferentQueryScope() {
        String cursor = KnowledgeChunkCursorCodec.encode(
                "manuals", "upload", "chunk-0002");

        assertThatThrownBy(() -> KnowledgeChunkCursorCodec.decodeLastId(
                cursor, "other", "upload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
        assertThatThrownBy(() -> KnowledgeChunkCursorCodec.decodeLastId(
                cursor, "manuals", "billing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void rejectsMalformedCursor() {
        assertThatThrownBy(() -> KnowledgeChunkCursorCodec.decodeLastId(
                "not-a-cursor", "manuals", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid knowledge page cursor");
    }

    @Test
    void collectionCursorRoundTripIsOpaque() {
        String cursor = KnowledgeChunkCursorCodec.encodeCollection("tenant-manuals");

        assertThat(cursor).doesNotContain("tenant-manuals");
        assertThat(KnowledgeChunkCursorCodec.decodeLastCollection(cursor))
                .isEqualTo("tenant-manuals");
    }
}
