package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.agent.context.KnowledgeAccessService;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;
import com.harness.tool.knowledge.KnowledgeContextData;
import com.harness.tool.protocol.ToolEnvelope;
import com.harness.tool.protocol.ToolEnvelopeStatus;
import com.harness.tool.rag.RagRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads one explicit bounded context window around a knowledge-search anchor. */
public final class KnowledgeContextReadTool implements Tool {

    public static final String TOOL_NAME = "knowledge_context_read";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeContextReadTool.class);
    private static final ObjectMapper SPEC_MAPPER = new ObjectMapper();

    private final KnowledgeAccessService knowledgeAccess;
    private final ObjectMapper objectMapper;

    public KnowledgeContextReadTool(KnowledgeAccessService knowledgeAccess) {
        this(knowledgeAccess, new ObjectMapper());
    }

    KnowledgeContextReadTool(
            KnowledgeAccessService knowledgeAccess,
            ObjectMapper objectMapper
    ) {
        this.knowledgeAccess = Objects.requireNonNull(knowledgeAccess, "knowledgeAccess");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ToolSpec spec() {
        ObjectNode properties = SPEC_MAPPER.createObjectNode();
        properties.set("documentId", stringProperty(
                "Stable documentId returned by knowledge_base_search."));
        properties.set("anchorChunkIndex", integerProperty(0, Integer.MAX_VALUE,
                "Stable chunkIndex returned by knowledge_base_search."));
        properties.set("before", integerProperty(0, knowledgeAccess.contextWindowMax(),
                "Chunks before the anchor index; defaults to 1."));
        properties.set("after", integerProperty(0, knowledgeAccess.contextWindowMax(),
                "Chunks after the anchor index; defaults to 1."));
        if (!knowledgeAccess.hasTrustedCollection()) {
            properties.set("collection", stringProperty(
                    "Optional logical collection. Defaults to the configured collection."));
        }

        ObjectNode schema = SPEC_MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("documentId").add("anchorChunkIndex");
        schema.put("additionalProperties", false);

        return new ToolSpec(
                TOOL_NAME,
                "Read a bounded document window around one anchor returned by knowledge_base_search. "
                        + "Use this only when the search hit lacks a required definition, prerequisite, or following step. "
                        + "Do not guess documentId or chunkIndex and do not repeat the same window.",
                schema);
    }

    @Override
    public String execute(JsonNode arguments) {
        String documentId = textArgument(arguments, "documentId");
        if (documentId == null) {
            throw new ToolExecutionException(TOOL_NAME,
                    "documentId is required. If the search hit has no documentId, re-ingest that document first.");
        }
        int anchorChunkIndex = requiredNonNegativeInteger(arguments, "anchorChunkIndex");
        int before = integerArgument(arguments, "before", 1);
        int after = integerArgument(arguments, "after", 1);
        validateWindowSize("before", before);
        validateWindowSize("after", after);
        knowledgeAccess.requireAuthorizedDocument(TOOL_NAME, documentId);

        String requestedCollection = textArgument(arguments, "collection");
        String collection = knowledgeAccess.effectiveCollection(requestedCollection);
        try {
            List<RagRetriever.RagDocument> documents = knowledgeAccess.readContext(
                    collection, documentId, anchorChunkIndex, before, after);
            validateContextDocuments(documents, documentId, anchorChunkIndex);
            ToolResult.setCurrentStatus(ToolResult.ResultStatus.SUCCESS);
            return objectMapper.writeValueAsString(new ToolEnvelope<>(
                    ToolEnvelopeStatus.SUCCESS,
                    KnowledgeContextData.from(documentId, anchorChunkIndex, documents),
                    null,
                    Map.of(
                            "collection", collection,
                            "before", before,
                            "after", after,
                            "windowSize", documents.size())));
        } catch (ToolExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("[KnowledgeContextReadTool] failed: {}", exception.getMessage(), exception);
            throw new ToolExecutionException(
                    TOOL_NAME, "Knowledge context read failed: " + exception.getMessage());
        }
    }

    private void validateWindowSize(String name, int value) {
        if (value < 0 || value > knowledgeAccess.contextWindowMax()) {
            throw new ToolExecutionException(
                    TOOL_NAME,
                    name + " must be between 0 and " + knowledgeAccess.contextWindowMax());
        }
    }

    private static void validateContextDocuments(
            List<RagRetriever.RagDocument> documents,
            String documentId,
            int anchorChunkIndex
    ) {
        if (documents.isEmpty()) {
            throw new ToolExecutionException(
                    TOOL_NAME, "No authorized context found for documentId and anchorChunkIndex");
        }
        boolean anchorFound = false;
        int previousIndex = -1;
        for (RagRetriever.RagDocument document : documents) {
            Object storedDocumentId = document.metadata().get("document_id");
            if (!(storedDocumentId instanceof String storedId) || storedId.isBlank()) {
                throw new ToolExecutionException(
                        TOOL_NAME,
                        "Knowledge document has no stable documentId and must be re-ingested");
            }
            if (!documentId.equals(storedId)) {
                throw new ToolExecutionException(
                        TOOL_NAME,
                        "Knowledge store returned a chunk outside the requested document");
            }
            if (document.chunkIndex() < previousIndex) {
                throw new ToolExecutionException(
                        TOOL_NAME, "Knowledge context chunks are not stably ordered");
            }
            previousIndex = document.chunkIndex();
            anchorFound |= document.chunkIndex() == anchorChunkIndex;
        }
        if (!anchorFound) {
            throw new ToolExecutionException(
                    TOOL_NAME, "anchorChunkIndex does not exist in the authorized document");
        }
    }

    private static String textArgument(JsonNode arguments, String name) {
        JsonNode value = arguments == null ? null : arguments.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new ToolExecutionException(TOOL_NAME, name + " must be a non-blank string");
        }
        return value.asText().trim();
    }

    private static int integerArgument(JsonNode arguments, String name, int defaultValue) {
        JsonNode value = arguments == null ? null : arguments.get(name);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new ToolExecutionException(TOOL_NAME, name + " must be an integer");
        }
        return value.intValue();
    }

    private static int requiredNonNegativeInteger(JsonNode arguments, String name) {
        JsonNode value = arguments == null ? null : arguments.get(name);
        if (value == null || value.isNull()) {
            throw new ToolExecutionException(TOOL_NAME, "Missing required parameter: " + name);
        }
        int parsed = integerArgument(arguments, name, -1);
        if (parsed < 0) {
            throw new ToolExecutionException(TOOL_NAME, name + " cannot be negative");
        }
        return parsed;
    }

    private static ObjectNode stringProperty(String description) {
        return SPEC_MAPPER.createObjectNode()
                .put("type", "string")
                .put("description", description);
    }

    private static ObjectNode integerProperty(int minimum, int maximum, String description) {
        return SPEC_MAPPER.createObjectNode()
                .put("type", "integer")
                .put("minimum", minimum)
                .put("maximum", maximum)
                .put("description", description);
    }
}
