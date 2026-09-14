package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.Artifact;
import com.harness.core.model.ArtifactStore;
import com.harness.core.model.ToolCapability;
import com.harness.core.model.ToolSpec;
import com.harness.input.document.DocumentConversionService;
import com.harness.input.document.DocumentSummarizer;
import com.harness.tool.Tool;
import com.harness.tool.protocol.ToolEnvelope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Reads a session-owned document using isolated model requests, returning only a bounded result. */
public final class FileReadTool implements Tool {

    public static final String TOOL_NAME = "read_file";
    private static final String ARTIFACT_PREFIX = "/api/artifacts/";
    private static final int MAX_OUTPUT_TOKENS = 2048;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ArtifactStore artifactStore;
    private final DocumentConversionService converter;
    private final DocumentSummarizer summarizer;
    private final String sessionId;

    public FileReadTool(ArtifactStore artifactStore, DocumentConversionService converter,
                        DocumentSummarizer summarizer, String sessionId) {
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.converter = Objects.requireNonNull(converter, "converter");
        this.summarizer = Objects.requireNonNull(summarizer, "summarizer");
        this.sessionId = sessionId;
    }

    public FileReadTool forSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("Session is required");
        return new FileReadTool(artifactStore, converter, summarizer, sessionId);
    }

    @Override
    public ToolSpec spec() {
        ObjectNode schema = MAPPER.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("file").put("type", "string").put("minLength", 1).put("maxLength", 256)
                .put("description", "Exact /api/artifacts/{id} document reference from this conversation.");
        properties.putObject("task").put("type", "string").put("minLength", 1).put("maxLength", 2048)
                .put("description", "Standalone question or reading task; only this task, without chat history, goes to the file model.");
        schema.putArray("required").add("file");
        return new ToolSpec(TOOL_NAME,
                "Read and analyze an uploaded document in separate primary-model requests without chat history. "
                        + "Uses the configured document context budget, batching and merging when necessary. "
                        + "Returns a bounded summary or task answer, never the full source document. "
                        + "Use task to request specific details from the file; do not guess unread file contents.",
                schema, ToolCapability.READ);
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            if (sessionId == null) throw new IllegalStateException("File reading requires an authorized session");
            if (arguments == null || !arguments.isObject()
                    || arguments.size() > (arguments.has("task") ? 2 : 1)) {
                throw new IllegalArgumentException("Only file and task parameters are accepted");
            }
            String reference = text(arguments, "file", 256);
            if (!reference.startsWith(ARTIFACT_PREFIX)) throw new IllegalArgumentException("Use an exact document artifact reference");
            String id = reference.substring(ARTIFACT_PREFIX.length());
            java.util.UUID.fromString(id);
            Artifact source = artifactStore.get(id).orElseThrow(() -> new IllegalArgumentException("File not found"));
            if (!sessionId.equals(source.sessionId())) throw new IllegalArgumentException("File belongs to another session");
            if (source.type() == Artifact.ArtifactType.AUDIO || source.type() == Artifact.ArtifactType.VIDEO) {
                throw new IllegalArgumentException("Use the appropriate media tool for this file");
            }
            Path path = Path.of(source.filePath());
            long maxBytes = Math.multiplyExact(EnvConfig.get().getLong(EnvKey.MULTIMODAL_FILE_MAX_SIZE, 50), 1024L * 1024L);
            if (Files.size(path) > maxBytes) throw new IllegalArgumentException("File exceeds the configured size limit");
            String markdown = converter.convert(Files.readAllBytes(path), source.name(), source.mimeType()).markdown();
            String task = arguments.has("task") ? text(arguments, "task", 2048)
                    : "Summarize this document's subjects, key facts, conclusions and limitations.";
            DocumentSummarizer.Summary result = summarizer.summarize(markdown, task, MAX_OUTPUT_TOKENS);
            return MAPPER.writeValueAsString(ToolEnvelope.success(
                    new FileResult(reference, source.name(), result.text()), null,
                    Map.of("model", result.model(), "inputBlocks", result.inputBlocks(), "summaryCalls", result.calls())));
        } catch (Exception e) {
            throw new ToolExecutionException(TOOL_NAME, "File reading failed: " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode arguments, String key, int limit) {
        JsonNode value = arguments.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank() || value.asText().length() > limit) {
            throw new IllegalArgumentException(key + " must be nonblank text within " + limit + " characters");
        }
        return value.asText().strip();
    }

    public record FileResult(String file, String name, String summary) {}
}
