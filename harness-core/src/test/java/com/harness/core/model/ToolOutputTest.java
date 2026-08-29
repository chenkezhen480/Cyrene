package com.harness.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolOutputTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void preservesOptionalTextArtifactAndJsonChannels() throws Exception {
        Artifact artifact = new Artifact(
                "artifact-1",
                "session-1",
                "report.pdf",
                Artifact.ArtifactType.DOCUMENT,
                "application/pdf",
                42,
                "private/path/report.pdf",
                Instant.now());
        JsonNode json = MAPPER.readTree("{\"eligible\":true}");
        ToolOutput output = new ToolOutput("tool result", List.of(artifact), json);

        JsonNode modelContent = MAPPER.readTree(output.modelContent());
        ToolOutput restored = ToolOutput.fromMessageBlocks(output.toMessageBlocks());

        assertThat(modelContent.get("text").asText()).isEqualTo("tool result");
        assertThat(modelContent.at("/artifacts/0/id").asText()).isEqualTo("artifact-1");
        assertThat(modelContent.get("json")).isEqualTo(json);
        assertThat(modelContent.toString()).doesNotContain("private/path");
        assertThat(restored.text()).isEqualTo("tool result");
        assertThat(restored.artifacts()).extracting(Artifact::id)
                .containsExactly("artifact-1");
        assertThat(restored.json()).isEqualTo(json);
    }

    @Test
    void keepsSingleTextAndJsonChannelsNaturalForModelContext() throws Exception {
        assertThat(ToolOutput.text("plain result").modelContent())
                .isEqualTo("plain result");
        assertThat(ToolOutput.json(MAPPER.readTree("{\"value\":1}")).modelContent())
                .isEqualTo("{\"value\":1}");
        assertThat(ToolOutput.empty().modelContent()).isEmpty();
    }

    @Test
    void traceJsonOmitsDerivedEmptyPropertyAndReadsExistingRowsThatContainIt()
            throws Exception {
        String serialized = MAPPER.writeValueAsString(ToolOutput.empty());
        ToolOutput restored = MAPPER.readValue(
                """
                        {
                          "text": "legacy trace result",
                          "artifacts": [],
                          "json": null,
                          "empty": false
                        }
                        """,
                ToolOutput.class);

        assertThat(serialized).doesNotContain("\"empty\"");
        assertThat(restored.text()).isEqualTo("legacy trace result");
        assertThat(restored.isEmpty()).isFalse();
    }

    @Test
    void memoryModelTextIncludesStructuredDataInsteadOfDroppingIt() throws Exception {
        MemoryMessage message = new MemoryMessage(
                1,
                "session-1",
                "assistant",
                ToolOutput.json(MAPPER.readTree("{\"items\":[1,2]}")).toMessageBlocks(),
                false,
                Instant.now());

        assertThat(message.text()).isEmpty();
        assertThat(message.modelText()).isEqualTo("{\"items\":[1,2]}");
    }
}
