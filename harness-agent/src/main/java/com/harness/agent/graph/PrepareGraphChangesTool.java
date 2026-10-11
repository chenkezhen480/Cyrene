package com.harness.agent.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ToolSpec;
import com.harness.graph.build.*;
import com.harness.tool.Tool;
import com.harness.tool.protocol.ToolEnvelope;
import java.util.Map;
import java.util.function.Supplier;

/** Proposes changes only; the model has no application endpoint. */
public final class PrepareGraphChangesTool implements Tool {
    public static final String TOOL_NAME = "prepare_graph_changes";
    private final GraphChangeDraftService service;
    private final Supplier<GraphDraftScope> scope;
    private final ObjectMapper mapper;

    public PrepareGraphChangesTool(GraphChangeDraftService service, Supplier<GraphDraftScope> scope, ObjectMapper mapper) {
        this.service = java.util.Objects.requireNonNull(service);
        this.scope = java.util.Objects.requireNonNull(scope);
        this.mapper = java.util.Objects.requireNonNull(mapper);
    }

    @Override public ToolSpec spec() {
        ObjectNode properties = mapper.createObjectNode();
        for (String field : java.util.List.of("graphId", "schemaId", "sourceDraftId", "expectedSourceContentHash"))
            properties.set(field, mapper.createObjectNode().put("type", "string").put("minLength", 1));
        for (String field : java.util.List.of("nodes", "relations")) {
            ObjectNode array = mapper.createObjectNode().put("type", "array");
            array.set("items", mapper.createObjectNode().put("type", "object")); properties.set(field, array);
        }
        for (String field : java.util.List.of("deleteNodeIds", "deleteRelationIds", "discardChangeIds")) {
            ObjectNode array = mapper.createObjectNode().put("type", "array").put("uniqueItems", true);
            array.set("items", mapper.createObjectNode().put("type", "string").put("minLength", 1)); properties.set(field, array);
        }
        ObjectNode schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.set("properties", properties);
        return new ToolSpec(TOOL_NAME, "Create a persistent PENDING graph change draft for human console review. "
                + "Discover a concrete graphId/schemaId using existing graph read tools, then select that target on the first call. "
                + "For later edits supply current sourceDraftId and expectedSourceContentHash from read_graph_draft. "
                + "nodes use {nodeId,labels,properties}; relations use {relationId,sourceNodeId,targetNodeId,relationType,properties}. "
                + "Property keys patch the effective draft; explicit null removes a property. Omitted changes stay pending. "
                + "discardChangeIds uses node:ID/relation:ID to cancel pending changes. This tool never writes formal graph data. "
                + "Tell the user this is a draft awaiting human confirmation and include its console link.", schema);
    }

    @Override public String execute(JsonNode arguments) {
        try {
            if (arguments == null || !arguments.isObject()) throw new IllegalArgumentException("arguments must be an object");
            var fields = arguments.fieldNames();
            while (fields.hasNext()) if (!java.util.Set.of("graphId", "schemaId", "sourceDraftId", "expectedSourceContentHash", "nodes", "relations", "deleteNodeIds", "deleteRelationIds", "discardChangeIds").contains(fields.next()))
                throw new IllegalArgumentException("Unsupported graph draft argument");
            GraphDraftPrepareRequest request = mapper.treeToValue(arguments, GraphDraftPrepareRequest.class);
            GraphDraftView view = service.prepare(java.util.Objects.requireNonNull(scope.get(), "Trusted graph draft scope is missing"), request);
            return mapper.writeValueAsString(ToolEnvelope.success(view, null, Map.of("viewType", "DRAFT_PREVIEW", "requiresHumanConfirmation", true)));
        } catch (Exception e) {
            throw new ToolExecutionException(TOOL_NAME, "Unable to prepare graph change draft: " + e.getMessage(), e);
        }
    }

    @Override public com.harness.core.model.ToolOutput executeOutput(JsonNode arguments) {
        try {
            return com.harness.core.model.ToolOutput.json(mapper.readTree(execute(arguments)));
        } catch (java.io.IOException e) {
            throw new ToolExecutionException(TOOL_NAME, "Unable to encode graph draft result", e);
        }
    }
}
