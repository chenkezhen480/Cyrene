package com.harness.tool.knowledge.okf;

import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.core.knowledge.OkfKnowledgeDocument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OkfMarkdownCodecTest {

    private final OkfMarkdownCodec codec = new OkfMarkdownCodec();

    @Test
    void roundTrip_preservesUnknownFieldsAndLifecycleValues() {
        Instant generatedAt = Instant.parse("2026-09-01T03:00:00Z");
        OkfKnowledgeDocument original = new OkfKnowledgeDocument(
                "User Preference",
                "Response verbosity",
                "Current preference",
                null,
                new OkfKnowledgeDocument.Generated("compiler/v1", generatedAt),
                List.of(new OkfKnowledgeDocument.Verified("human:user-1", generatedAt)),
                KnowledgeStatus.STABLE,
                Instant.parse("2027-03-01T00:00:00Z"),
                List.of(new OkfKnowledgeDocument.Source(
                        "message-1", "cyrene://sessions/s1/messages/1", generatedAt)),
                Map.of(
                        "x-cyrene-concept-id", "concept-1",
                        "vendor-review-state", Map.of("state", "ready")),
                "# Preference\n\nKeep answers concise.");

        String markdown = codec.write(original);
        OkfKnowledgeDocument parsed = codec.read(markdown);

        assertThat(markdown).startsWith("---\n").contains("vendor-review-state:");
        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void read_rejectsDuplicateSourcesAndUnknownStatus() {
        String duplicateSources = """
                ---
                type: Reference
                generated: {by: compiler/v1, at: 2026-09-01T03:00:00Z}
                status: draft
                sources:
                  - {id: a, resource: cyrene://artifacts/a}
                  - {id: a, resource: cyrene://artifacts/a}
                ---

                body
                """;
        assertThatThrownBy(() -> codec.read(duplicateSources))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate OKF source");

        assertThatThrownBy(() -> codec.read(duplicateSources.replace("draft", "current")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported OKF status");
    }

    @Test
    void redactor_removesCredentialsWithoutChangingOrdinaryTokenLanguage() {
        OkfSensitiveDataRedactor redactor = new OkfSensitiveDataRedactor();
        String redacted = redactor.redact("""
                Authorization: Bearer secret-token-value
                Cookie: sid=abc123
                api_key=topsecret
                token budget is 30000
                """);

        assertThat(redacted)
                .doesNotContain("secret-token-value", "sid=abc123", "topsecret")
                .contains("token budget is 30000")
                .contains("[REDACTED]");
    }
}
