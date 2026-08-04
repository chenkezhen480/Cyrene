package com.harness.agent.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.provider.ChatModelProvider;
import com.harness.graph.build.CanonicalJsonGraphDataConverter;
import com.harness.graph.build.GraphBuildRequest;
import com.harness.graph.build.GraphBuildSourceType;
import com.harness.graph.build.GraphDataConversionException;
import com.harness.graph.build.GraphDataConverter;
import com.harness.graph.build.GraphMutationDraft;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.model.GraphMutationBatch;
import com.harness.graph.model.GraphNode;
import com.harness.graph.model.GraphNodePageRequest;
import com.harness.graph.model.GraphRelation;
import com.harness.graph.schema.GraphNodeTypeDefinition;
import com.harness.graph.schema.GraphSchemaDefinition;
import com.harness.graph.schema.GraphSchemaRegistry;
import com.harness.graph.schema.GraphSchemaValidator;
import com.harness.graph.store.KnowledgeGraphStore;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Converts explicit natural-language facts into a Schema-constrained graph draft.
 * The draft is never persisted by this converter; callers must present it for review
 * and submit the confirmed canonical JSON through the structured build route.
 */
public final class LlmGraphDataConverter implements GraphDataConverter {

    public static final String CONVERTER_ID = "llm-schema";

    private static final String SYSTEM_PROMPT = """
            You extract explicit graph facts from natural language.
            Return exactly one JSON object and no Markdown or commentary:
            {"nodes":[],"relations":[]}

            Rules:
            1. Use only node labels, relation types, directions, and properties declared by the supplied Schema.
            2. Extract only facts explicitly stated in the source. Never infer, diagnose, generalize, or invent facts.
            3. Every new node must contain every required property declared by its node type.
            4. Every relation endpoint must be either a nodeId emitted in nodes or an exact nodeId from existingNodes.
            5. Reuse an existing nodeId only when the source clearly refers to that existing entity.
            6. Do not emit an existing node in nodes unless the source explicitly updates one of its properties.
            7. relationId and new nodeId must be non-blank, stable, and unique within this result. Unicode IDs are allowed.
            8. Preserve the Schema relation direction. If a fact cannot be represented safely, omit it.
            """;

    private final ChatModel chatModel;
    private final KnowledgeGraphStore graphStore;
    private final GraphSchemaRegistry schemaRegistry;
    private final GraphSchemaValidator schemaValidator;
    private final GraphSettings graphSettings;
    private final ObjectMapper objectMapper;
    private final CanonicalJsonGraphDataConverter canonicalConverter;

    public LlmGraphDataConverter(
            ChatModelProvider chatModelProvider,
            KnowledgeGraphStore graphStore,
            GraphSchemaRegistry schemaRegistry,
            GraphSettings graphSettings,
            ObjectMapper objectMapper
    ) {
        this.chatModel = Objects.requireNonNull(chatModelProvider, "chatModelProvider").chatModel();
        if (this.chatModel == null) {
            throw new IllegalArgumentException("chatModel is required");
        }
        this.graphStore = Objects.requireNonNull(graphStore, "graphStore");
        this.schemaRegistry = Objects.requireNonNull(schemaRegistry, "schemaRegistry");
        this.schemaValidator = new GraphSchemaValidator(schemaRegistry);
        this.graphSettings = Objects.requireNonNull(graphSettings, "graphSettings");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.canonicalConverter = new CanonicalJsonGraphDataConverter(objectMapper);
    }

    @Override
    public String converterId() {
        return CONVERTER_ID;
    }

    @Override
    public GraphBuildSourceType sourceType() {
        return GraphBuildSourceType.NATURAL_LANGUAGE;
    }

    @Override
    public GraphMutationDraft convert(GraphBuildRequest request) {
        if (request.sourceType() != sourceType()) {
            throw new IllegalArgumentException("llm-schema only accepts natural-language sources");
        }
        String sourceText = request.source().asText();
        if (sourceText.length() > graphSettings.contextMaxChars()) {
            throw new IllegalArgumentException(
                    "Natural-language graph source exceeds "
                            + graphSettings.contextMaxChars() + " characters");
        }

        GraphSchemaDefinition schema = schemaRegistry.require(request.schemaId());
        ExistingNodeContext existingNodeContext = loadExistingNodes(request);
        String userPrompt = buildUserPrompt(schema, existingNodeContext, sourceText);
        String responseText = callModel(userPrompt);
        GraphMutationDraft draft = parseDraft(request, responseText);
        validateDraft(request, draft, existingNodeContext.nodes());
        return draft;
    }

    private ExistingNodeContext loadExistingNodes(GraphBuildRequest request) {
        int limit = graphSettings.capLimit(graphSettings.contextMaxItems());
        var page = graphStore.listNodes(new GraphNodePageRequest(
                request.graphId(), request.schemaId(), "", "", limit, ""
        ));
        return new ExistingNodeContext(page.items(), page.pageInfo().hasMore());
    }

    private String buildUserPrompt(
            GraphSchemaDefinition schema,
            ExistingNodeContext existingNodeContext,
            String sourceText
    ) {
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("schema", schema);
            input.put("existingNodes", sanitizeExistingNodes(schema, existingNodeContext.nodes()));
            input.put("existingNodesTruncated", existingNodeContext.truncated());
            input.put("source", sourceText);
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new GraphDataConversionException("Failed to prepare graph extraction input", e);
        }
    }

    private List<GraphNode> sanitizeExistingNodes(
            GraphSchemaDefinition schema,
            List<GraphNode> existingNodes
    ) {
        List<GraphNode> sanitized = new ArrayList<>(existingNodes.size());
        for (GraphNode node : existingNodes) {
            Set<String> visibleProperties = new LinkedHashSet<>();
            for (String label : node.labels()) {
                GraphNodeTypeDefinition type = schema.nodeTypes().get(label);
                if (type == null) {
                    continue;
                }
                type.properties().values().stream()
                        .filter(property -> !property.sensitive())
                        .forEach(property -> visibleProperties.add(property.name()));
            }
            Map<String, Object> properties = new LinkedHashMap<>();
            node.properties().forEach((name, value) -> {
                if (visibleProperties.contains(name)) {
                    properties.put(name, value);
                }
            });
            sanitized.add(new GraphNode(node.nodeId(), node.labels(), properties));
        }
        return List.copyOf(sanitized);
    }

    private String callModel(String userPrompt) {
        try {
            var response = chatModel.chat(ChatRequest.builder()
                    .messages(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(userPrompt))
                    .build());
            String text = response == null || response.aiMessage() == null
                    ? null
                    : response.aiMessage().text();
            if (text == null || text.isBlank()) {
                throw new GraphDataConversionException("The model returned an empty graph draft");
            }
            return unwrapJsonFence(text);
        } catch (GraphDataConversionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new GraphDataConversionException(
                    "Natural-language graph parsing failed: " + e.getMessage(), e);
        }
    }

    private GraphMutationDraft parseDraft(GraphBuildRequest request, String responseText) {
        try {
            JsonNode source = objectMapper.readTree(responseText);
            return canonicalConverter.convert(new GraphBuildRequest(
                    request.requestId(),
                    request.graphId(),
                    request.schemaId(),
                    GraphBuildSourceType.STRUCTURED,
                    CanonicalJsonGraphDataConverter.CONVERTER_ID,
                    source
            ));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new GraphDataConversionException(
                    "The model returned invalid graph JSON: " + e.getMessage(), e);
        }
    }

    private void validateDraft(
            GraphBuildRequest request,
            GraphMutationDraft draft,
            List<GraphNode> existingNodes
    ) {
        Map<String, GraphNode> knownNodes = new LinkedHashMap<>();
        existingNodes.forEach(node -> knownNodes.put(node.nodeId(), node));
        draft.nodes().forEach(node -> knownNodes.put(node.nodeId(), node));
        Set<String> draftNodeIds = new LinkedHashSet<>();
        draft.nodes().forEach(node -> draftNodeIds.add(node.nodeId()));

        Set<String> referencedExistingNodeIds = new LinkedHashSet<>();
        for (GraphRelation relation : draft.relations()) {
            requireKnownEndpoint(knownNodes, relation.sourceNodeId(), relation.relationId(), "source");
            requireKnownEndpoint(knownNodes, relation.targetNodeId(), relation.relationId(), "target");
            if (!draftNodeIds.contains(relation.sourceNodeId())) {
                referencedExistingNodeIds.add(relation.sourceNodeId());
            }
            if (!draftNodeIds.contains(relation.targetNodeId())) {
                referencedExistingNodeIds.add(relation.targetNodeId());
            }
        }

        List<GraphNode> validationNodes = new ArrayList<>(draft.nodes());
        referencedExistingNodeIds.forEach(nodeId -> validationNodes.add(knownNodes.get(nodeId)));
        try {
            schemaValidator.validate(new GraphMutationBatch(
                    request.requestId(), request.graphId(), request.schemaId(),
                    validationNodes, draft.relations()
            ));
        } catch (IllegalArgumentException e) {
            throw new GraphDataConversionException(
                    "The parsed graph does not satisfy Schema '" + request.schemaId() + "': "
                            + e.getMessage(), e);
        }
    }

    private static void requireKnownEndpoint(
            Map<String, GraphNode> knownNodes,
            String nodeId,
            String relationId,
            String endpointName
    ) {
        if (!knownNodes.containsKey(nodeId)) {
            throw new GraphDataConversionException(
                    "Relation '" + relationId + "' references unknown "
                            + endpointName + " nodeId '" + nodeId + "'");
        }
    }

    private static String unwrapJsonFence(String value) {
        String trimmed = value.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        int closingFence = trimmed.lastIndexOf("```");
        if (firstLineEnd < 0 || closingFence <= firstLineEnd) {
            throw new GraphDataConversionException("The model returned an incomplete JSON code fence");
        }
        if (!trimmed.substring(closingFence + 3).trim().isEmpty()) {
            throw new GraphDataConversionException("The model returned text after the JSON code fence");
        }
        return trimmed.substring(firstLineEnd + 1, closingFence).trim();
    }

    private record ExistingNodeContext(List<GraphNode> nodes, boolean truncated) {
        private ExistingNodeContext {
            nodes = List.copyOf(nodes);
        }
    }
}
