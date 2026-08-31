package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ToolOutput;
import com.harness.core.model.ToolSpec;
import com.harness.provider.VoiceCapabilities;
import com.harness.provider.VoiceModelProvider;
import com.harness.tool.TypedOutputTool;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Objects;

/** Transcribes one uploaded or previously generated audio file through the voice provider. */
public final class AudioTranscriptionTool implements TypedOutputTool {

    public static final String TOOL_NAME = "transcribe_audio";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @FunctionalInterface
    public interface AudioSourceLoader {
        AudioSource load(String reference);
    }

    public record AudioSource(byte[] data, String name, String mimeType) {
        public AudioSource {
            data = data != null ? data.clone() : new byte[0];
            name = name != null ? name : "audio";
            mimeType = normalizeMimeType(mimeType);
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    private final VoiceModelProvider provider;
    private final AudioSourceLoader sourceLoader;

    public AudioTranscriptionTool(
            VoiceModelProvider provider,
            AudioSourceLoader sourceLoader
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.sourceLoader = Objects.requireNonNull(sourceLoader, "sourceLoader");
    }

    @Override
    public ToolSpec spec() {
        ObjectNode parameters = MAPPER.createObjectNode().put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.set("file", MAPPER.createObjectNode()
                .put("type", "string")
                .put("description", "Audio reference from the conversation, such as "
                        + "/files/input/example.webm or /api/artifacts/{id}."));
        parameters.set("required", MAPPER.createArrayNode().add("file"));
        return new ToolSpec(
                TOOL_NAME,
                "Transcribe an uploaded or generated audio file into text. "
                        + "Use the exact file reference shown in the conversation.",
                parameters);
    }

    @Override
    public ToolOutput executeOutput(JsonNode arguments) {
        String reference = requiredText(arguments, "file");
        VoiceCapabilities capabilities = provider.capabilities();
        if (!capabilities.asrAvailable()) {
            throw new ToolExecutionException(TOOL_NAME, "Audio transcription is not configured");
        }

        try {
            AudioSource source = sourceLoader.load(reference);
            byte[] audio = source.data();
            if (audio.length == 0) {
                throw new ToolExecutionException(TOOL_NAME, "Audio file is empty: " + reference);
            }
            if (audio.length > provider.maxTranscriptionSizeBytes()) {
                throw new ToolExecutionException(
                        TOOL_NAME,
                        "Audio file exceeds the configured transcription size limit");
            }
            if (!capabilities.acceptedInputMimeTypes().contains(source.mimeType())) {
                throw new ToolExecutionException(
                        TOOL_NAME,
                        "Unsupported audio type: " + source.mimeType());
            }
            String text = provider.transcribe(
                    new ByteArrayInputStream(audio), source.mimeType());
            return ToolOutput.text(text != null ? text : "");
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException(
                    TOOL_NAME, "Audio transcription failed: " + e.getMessage(), e);
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

    private static String normalizeMimeType(String mimeType) {
        return mimeType == null || mimeType.isBlank()
                ? "application/octet-stream"
                : mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }
}
