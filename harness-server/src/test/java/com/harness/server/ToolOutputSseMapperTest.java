package com.harness.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.Artifact;
import com.harness.core.model.StreamEvent;
import com.harness.core.model.ToolOutput;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolOutputSseMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void toPayload_limitsTextToOneHundredUnicodeCodePoints() {
        String text = "a".repeat(99) + "😀tail";
        StreamEvent event = StreamEvent.toolOutput(
                "call-1", "search", ToolOutput.text(text));

        Map<String, Object> payload = ToolOutputSseMapper.toPayload(event);

        String preview = (String) payload.get("text");
        assertThat(preview.codePointCount(0, preview.length())).isEqualTo(100);
        assertThat(preview).endsWith("😀");
        assertThat(payload)
                .containsEntry("textLength", 104)
                .containsEntry("truncated", true)
                .containsEntry("toolCallId", "call-1")
                .containsEntry("toolName", "search");
    }

    @Test
    void toPayload_groupsArtifactsAndStructuredDataWithText() throws Exception {
        Artifact artifact = new Artifact(
                "artifact-1", "session-1", "result.png",
                Artifact.ArtifactType.IMAGE, "image/png", 12,
                "result.png", Instant.parse("2026-08-31T00:00:00Z"));
        ToolOutput output = new ToolOutput(
                "short output", List.of(artifact), MAPPER.readTree("{\"value\":1}"));

        Map<String, Object> payload = ToolOutputSseMapper.toPayload(
                StreamEvent.toolOutput("call-2", "image_generation", output));

        assertThat(payload)
                .containsEntry("text", "short output")
                .containsEntry("textLength", 12)
                .containsEntry("truncated", false)
                .containsEntry("data", MAPPER.readTree("{\"value\":1}"));
        assertThat((List<?>) payload.get("artifacts"))
                .singleElement()
                .satisfies(value -> assertThat(((Map<?, ?>) value).get("artifactId"))
                        .isEqualTo("artifact-1"));
    }
}
