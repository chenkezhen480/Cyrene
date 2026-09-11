package com.harness.core.knowledge;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeAuthorityCursorTest {

    @Test
    void compoundCursors_roundTripDelimiterRichIdentifiers() {
        KnowledgeSourceCursor source = new KnowledgeSourceCursor(
                Instant.parse("2026-09-01T10:20:30.123Z"),
                KnowledgeSourceType.BUSINESS_RESULT,
                "source.with|delimiters/值");
        KnowledgeVerificationCursor verification = new KnowledgeVerificationCursor(
                Instant.parse("2026-09-01T10:20:30.123Z"), 42);
        KnowledgeLinkCursor link = new KnowledgeLinkCursor(
                "concept.with|delimiters/值", KnowledgeLinkType.RELATED_TO);

        assertThat(KnowledgeSourceCursor.parse(source.encode())).isEqualTo(source);
        assertThat(KnowledgeVerificationCursor.parse(verification.encode()))
                .isEqualTo(verification);
        assertThat(KnowledgeLinkCursor.parse(link.encode())).isEqualTo(link);
    }

    @Test
    void compoundCursors_rejectMalformedValues() {
        assertThatThrownBy(() -> KnowledgeSourceCursor.parse("broken"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgeVerificationCursor.parse("broken"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgeLinkCursor.parse("broken"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
