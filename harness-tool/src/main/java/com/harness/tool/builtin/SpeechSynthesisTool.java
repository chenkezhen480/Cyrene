package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.Artifact;
import com.harness.core.model.ToolOutput;
import com.harness.core.model.ToolSpec;
import com.harness.provider.VoiceModelProvider;
import com.harness.tool.TypedOutputTool;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Synthesizes text into a durable audio artifact through the voice provider. */
public final class SpeechSynthesisTool implements TypedOutputTool {

    public static final String TOOL_NAME = "synthesize_speech";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OUTPUT_MIME_TYPE = "audio/mpeg";

    @FunctionalInterface
    public interface ArtifactStorer {
        Artifact store(byte[] data, String name, String mimeType, String sessionId);
    }

    private final VoiceModelProvider provider;
    private final ArtifactStorer artifactStorer;

    public SpeechSynthesisTool(
            VoiceModelProvider provider,
            ArtifactStorer artifactStorer
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.artifactStorer = Objects.requireNonNull(artifactStorer, "artifactStorer");
    }

    @Override
    public ToolSpec spec() {
        ObjectNode parameters = MAPPER.createObjectNode().put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.set("text", MAPPER.createObjectNode()
                .put("type", "string")
                .put("description", "Text to synthesize into speech."));
        properties.set("voice", MAPPER.createObjectNode()
                .put("type", "string")
                .put("description", "Optional provider-specific voice name."));
        parameters.set("required", MAPPER.createArrayNode().add("text"));
        return new ToolSpec(
                TOOL_NAME,
                "Convert text to speech and return a downloadable audio artifact. "
                        + "Use this only when the user explicitly requests spoken audio.",
                parameters);
    }

    @Override
    public ToolOutput executeOutput(JsonNode arguments) {
        String text = requiredText(arguments, "text");
        if (!provider.capabilities().ttsAvailable()) {
            throw new ToolExecutionException(TOOL_NAME, "Speech synthesis is not configured");
        }
        String voice = optionalText(arguments, "voice", provider.defaultVoice());

        try {
            byte[] audio = provider.synthesize(text, voice);
            if (audio == null || audio.length == 0) {
                throw new ToolExecutionException(
                        TOOL_NAME, "Speech provider returned empty audio");
            }
            Artifact artifact = artifactStorer.store(
                    audio,
                    "speech-" + UUID.randomUUID() + ".mp3",
                    OUTPUT_MIME_TYPE,
                    null);
            return ToolOutput.artifacts("Speech synthesis completed.", List.of(artifact));
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException(
                    TOOL_NAME, "Speech synthesis failed: " + e.getMessage(), e);
        }
    }

    private static String requiredText(JsonNode arguments, String name) {
        JsonNode value = arguments != null ? arguments.get(name) : null;
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ToolExecutionException(
                    TOOL_NAME, "Missing required parameter: " + name);
        }
        return value.asText().trim();
    }

    private static String optionalText(JsonNode arguments, String name, String defaultValue) {
        JsonNode value = arguments != null ? arguments.get(name) : null;
        return value != null && value.isTextual() && !value.asText().isBlank()
                ? value.asText().trim()
                : defaultValue;
    }
}
