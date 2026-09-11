package com.harness.agent.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.agent.KnowledgeGraphTool;
import com.harness.agent.context.KnowledgeAccessService;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeHandle;
import com.harness.core.knowledge.KnowledgeHandleCodec;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.core.model.ResultStatus;
import com.harness.core.model.ToolExecutionOutcome;
import com.harness.core.model.ToolOutput;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;
import com.harness.tool.knowledge.KnowledgeContextData;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import com.harness.tool.protocol.ToolEnvelope;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Re-authorizes every untrusted knowledge handle and performs bounded reads. */
public final class KnowledgeReadTool implements Tool {

    public static final String TOOL_NAME = "knowledge_read";

    private final KnowledgeRepository repository;
    private final com.harness.tool.knowledge.index.KnowledgeProjectionStore projectionStore;
    private final KnowledgeHandleCodec handleCodec;
    private final KnowledgeAccessService documentExecutor;
    private final KnowledgeGraphTool graphExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public KnowledgeReadTool(
            KnowledgeRepository repository,
            com.harness.tool.knowledge.index.KnowledgeProjectionStore projectionStore,
            KnowledgeHandleCodec handleCodec,
            KnowledgeAccessService documentExecutor,
            KnowledgeGraphTool graphExecutor,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.projectionStore = Objects.requireNonNull(projectionStore, "projectionStore");
        this.handleCodec = Objects.requireNonNull(handleCodec, "handleCodec");
        this.documentExecutor = Objects.requireNonNull(documentExecutor, "documentExecutor");
        this.graphExecutor = graphExecutor;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ToolSpec spec() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("handle", objectMapper.createObjectNode()
                .put("type", "string").put("minLength", 1).put("maxLength", 4096));
        properties.set("before", integerProperty(0, documentExecutor.contextWindowMax()));
        properties.set("after", integerProperty(0, documentExecutor.contextWindowMax()));
        if (graphExecutor != null) {
            ObjectNode graphAction = objectMapper.createObjectNode()
                    .put("type", "string")
                    .put("description", "Graph route selected after reading a graph Wiki handle.");
            graphAction.putArray("enum")
                    .add("listGraphSpaces")
                    .add("findNodes")
                    .add("findNeighborhood");
            properties.set("graphAction", graphAction);
            properties.set("graphId", stringProperty());
            properties.set("name", stringProperty());
            properties.set("label", stringProperty());
            properties.set("subjectIds", stringArrayProperty());
            properties.set("cursor", stringProperty());
            properties.set("relationTypes", stringArrayProperty());
            properties.set("queryId", stringProperty());
            properties.set("maxDepth", integerProperty(1, Integer.MAX_VALUE));
            properties.set("limit", integerProperty(1, Integer.MAX_VALUE));
        }
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("handle");
        schema.put("additionalProperties", false);
        return new ToolSpec(
                TOOL_NAME,
                "Read one typed Wiki handle after rechecking its current revision, tenant, collection, and tool authorization.",
                schema,
                com.harness.core.model.ToolCapability.RETRIEVAL);
    }

    @Override
    public String execute(JsonNode arguments) {
        return executeOutcome(arguments).content().modelContent();
    }

    @Override
    public ToolExecutionOutcome executeOutcome(JsonNode arguments) {
        try {
            KnowledgeToolRuntimeContext context =
                    KnowledgeToolRuntimeContext.requireCurrent(TOOL_NAME);
            if (!context.authorizedTools().contains(TOOL_NAME)) {
                throw new SecurityException("knowledge_read is not authorized for this run");
            }
            KnowledgeHandle handle = handleCodec.decode(requiredText(arguments, "handle"));
            KnowledgeHead head = repository.findAuthorityById(handle.conceptId()).orElseThrow(
                    () -> new SecurityException("Knowledge handle does not resolve to a current Concept"));
            authorizeHead(handle, head, context);
            RoutedRead read = switch (head.routeType()) {
                case USER_MEMORY, OPERATION_MEMORY ->
                        new RoutedRead(readMemory(head), ResultStatus.AVAILABLE);
                case DOCUMENT -> new RoutedRead(
                        readDocument(handle, head, arguments, context), ResultStatus.AVAILABLE);
                case GRAPH -> readGraph(head, arguments);
            };
            Map<String, Object> metadata = Map.of(
                            "conceptId", head.concept().id(),
                            "revisionId", head.currentRevision().id(),
                            "knowledgeKind", head.concept().conceptType().name());
            ToolEnvelope<Object> envelope = read.resultStatus() == ResultStatus.EMPTY
                    ? ToolEnvelope.empty(read.data(), null, metadata)
                    : ToolEnvelope.success(read.data(), null, metadata);
            return ToolExecutionOutcome.succeeded(
                    ToolOutput.text(objectMapper.writeValueAsString(envelope)),
                    read.resultStatus());
        } catch (ToolExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ToolExecutionException(
                    TOOL_NAME, "Knowledge read failed: " + exception.getMessage());
        }
    }

    private void authorizeHead(
            KnowledgeHandle handle,
            KnowledgeHead head,
            KnowledgeToolRuntimeContext context
    ) {
        KnowledgeConcept concept = head.concept();
        if (head.currentRevision() == null
                || concept.status() != KnowledgeStatus.STABLE
                || concept.isStaleAt(clock.instant())
                || !Objects.equals(handle.revisionId(), concept.currentRevisionId())
                || handle.knowledgeKind() != concept.conceptType()
                || handle.routeTarget() != head.routeType()) {
            throw new SecurityException("Knowledge handle is stale or no longer current");
        }
        if ((concept.conceptType() == KnowledgeConceptType.GRAPH_SCHEMA
                || concept.conceptType() == KnowledgeConceptType.GRAPH_SPACE)
                && context.graphRequestContext() != null
                && !Objects.equals(
                graphSchemaId(head), context.graphRequestContext().schemaId())) {
            throw new SecurityException("Graph Schema handle exceeds the trusted graph scope");
        }
        if (concept.conceptType() == KnowledgeConceptType.GRAPH_SPACE
                && context.graphRequestContext() != null
                && !Objects.equals(head.routeText("graphId"),
                context.graphRequestContext().graphId())) {
            throw new SecurityException("Graph Space handle exceeds the trusted graph scope");
        }
        boolean scopeMatches = concept.conceptType() == KnowledgeConceptType.USER_EPISODE
                ? context.userId() != null
                && Objects.equals(concept.userId(), context.userId())
                && Objects.equals(concept.tenantId(), context.tenantId())
                : concept.userId() == null
                && (concept.tenantId() == null
                || Objects.equals(concept.tenantId(), context.tenantId()));
        if (!scopeMatches) {
            throw new SecurityException("Knowledge handle exceeds the current owner or tenant scope");
        }
    }

    private ConceptRead readMemory(KnowledgeHead head) {
        var block = projectionStore.findMemory(head.concept().conceptType(),
                head.routeText("memoryId"), head.currentVersion()).orElseThrow(
                () -> new IllegalStateException("Current memory block is not indexed yet"));
        if (!Objects.equals(block.conceptId(), head.concept().id())
                || !Objects.equals(block.revisionId(), head.concept().currentRevisionId())
                || block.conceptType() != head.concept().conceptType()
                || !Objects.equals(block.tenantId(), head.concept().tenantId())
                || !Objects.equals(block.userId(), head.concept().userId())) {
            throw new SecurityException("Memory block does not match the authorized Wiki revision");
        }
        return new ConceptRead(block.conceptType(), block.conceptId(), block.revisionId(),
                block.title(), block.description(), block.content(), false);
    }

    private Object readDocument(
            KnowledgeHandle handle,
            KnowledgeHead head,
            JsonNode arguments,
            KnowledgeToolRuntimeContext context
    ) {
        if (head.concept().conceptType() != KnowledgeConceptType.SOURCE_DOCUMENT
                || !Objects.equals(handle.collectionKey(), head.routeText("collectionKey"))
                || !Objects.equals(handle.documentId(), head.routeText("documentId"))
                || !context.allowsCollection(handle.collectionKey())
                || !context.allowsDocument(handle.documentId())) {
            throw new SecurityException("Document handle exceeds the authorized collection scope");
        }
        if (handle.chunkIndex() == null) {
            throw new IllegalArgumentException("Document handle is missing its chunk anchor; search again");
        }
        int before = integer(arguments, "before", 1);
        int after = integer(arguments, "after", 1);
        if (before < 0 || before > documentExecutor.contextWindowMax()
                || after < 0 || after > documentExecutor.contextWindowMax()) {
            throw new IllegalArgumentException("Document window exceeds configured bounds");
        }
        var chunks = documentExecutor.readContext(
                head.routeText("collectionKey"), head.routeText("documentId"), head.currentVersion(),
                handle.chunkIndex(), before, after);
        chunks.forEach(chunk -> {
            Object revisionId = chunk.metadata().get("revision_id");
            Object documentId = chunk.metadata().get("document_id");
            if (!handle.revisionId().equals(revisionId)
                    || !handle.documentId().equals(documentId)) {
                throw new SecurityException(
                        "Document window contains a different document revision");
            }
        });
        var window = KnowledgeContextData.from(handle.documentId(), handle.chunkIndex(), chunks);
        return new DocumentRead(window.documentId(), window.anchorChunkIndex(),
                window.chunks().stream().map(chunk -> new DocumentChunk(
                        chunk.chunkId(), chunk.fileName(), chunk.chunkIndex(), chunk.headingPath(),
                        chunk.content(), handleCodec.encode(KnowledgeHandle.document(
                        handle.knowledgeKind(), handle.conceptId(), handle.revisionId(),
                        handle.collectionKey(), handle.documentId(), chunk.chunkIndex())))).toList());
    }

    private RoutedRead readGraph(KnowledgeHead head, JsonNode arguments) throws Exception {
        KnowledgeConceptType conceptType = head.concept().conceptType();
        if (conceptType != KnowledgeConceptType.GRAPH_SCHEMA
                && conceptType != KnowledgeConceptType.GRAPH_SPACE) {
            throw new SecurityException(
                    "Graph handle must resolve to a Graph Schema or Graph Space Concept");
        }
        if (graphExecutor == null) {
            throw new IllegalStateException("Knowledge graph provider is unavailable");
        }
        String graphId = conceptType == KnowledgeConceptType.GRAPH_SPACE
                ? head.routeText("graphId") : null;
        String schemaId = graphSchemaId(head);
        if (conceptType == KnowledgeConceptType.GRAPH_SPACE && graphId == null) {
            throw new SecurityException("Graph Space Concept is missing graphId metadata");
        }
        ToolExecutionOutcome graphOutcome = graphExecutor.executeForWiki(
                schemaId, graphId, arguments);
        if (graphOutcome.executionStatus()
                != com.harness.core.model.ExecutionStatus.SUCCEEDED) {
            throw new IllegalStateException(graphOutcome.error());
        }
        return new RoutedRead(
                new GraphRead(
                        head.concept().id(),
                        head.currentRevision().id(),
                        schemaId,
                        graphId,
                        objectMapper.readTree(graphOutcome.content().modelContent())),
                graphOutcome.resultStatus());
    }

    private static String graphSchemaId(KnowledgeHead head) {
        return head.routeText("schemaId");
    }

    private ObjectNode integerProperty(int minimum, int maximum) {
        return objectMapper.createObjectNode().put("type", "integer")
                .put("minimum", minimum).put("maximum", maximum);
    }

    private ObjectNode stringProperty() {
        return objectMapper.createObjectNode().put("type", "string");
    }

    private ObjectNode stringArrayProperty() {
        return objectMapper.createObjectNode()
                .put("type", "array")
                .set("items", stringProperty());
    }

    private static String requiredText(JsonNode arguments, String field) {
        String value = optionalText(arguments, field);
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static String optionalText(JsonNode arguments, String field) {
        JsonNode value = arguments == null ? null : arguments.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return value.asText().trim();
    }

    private static int integer(JsonNode arguments, String field, int defaultValue) {
        JsonNode value = arguments == null ? null : arguments.get(field);
        if (value == null || value.isNull()) return defaultValue;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.intValue();
    }

    public record ConceptRead(
            KnowledgeConceptType knowledgeKind,
            String conceptId,
            String revisionId,
            String title,
            String description,
            String body,
            boolean truncated
    ) {
    }

    public record DocumentRead(String documentId, int anchorChunkIndex, List<DocumentChunk> chunks) { }

    public record DocumentChunk(String chunkId, String fileName, int chunkIndex,
                                List<String> headingPath, String content, String handle) { }

    public record GraphRead(
            String conceptId,
            String revisionId,
            String schemaId,
            String graphId,
            JsonNode graphResult
    ) {
    }

    private record RoutedRead(Object data, ResultStatus resultStatus) {
    }
}
