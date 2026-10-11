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

public final class ReadGraphDraftTool implements Tool {
    public static final String TOOL_NAME = "read_graph_draft";
    private final GraphChangeDraftService service;
    private final Supplier<GraphDraftScope> scope;
    private final ObjectMapper mapper;

    public ReadGraphDraftTool(GraphChangeDraftService service, Supplier<GraphDraftScope> scope, ObjectMapper mapper) {
        this.service = java.util.Objects.requireNonNull(service);
        this.scope = java.util.Objects.requireNonNull(scope);
        this.mapper = java.util.Objects.requireNonNull(mapper);
    }

    @Override public ToolSpec spec() {
        ObjectNode properties = mapper.createObjectNode();
        for (String field : java.util.List.of("draftId", "expectedContentHash", "cursor"))
            properties.set(field, mapper.createObjectNode().put("type", "string"));
        properties.set("limit", mapper.createObjectNode().put("type", "integer").put("minimum", 1));
        ObjectNode schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.set("properties", properties); schema.putArray("required").add("draftId").add("expectedContentHash");
        return new ToolSpec(TOOL_NAME, "Read the fixed current graph draft's paginated DRAFT_PREVIEW before/after objects. "
                + "Use after values for later edits; deleted objects have after=null. query_graph reads COMMITTED data, "
                + "so it does not contain pending additions or property changes. A stale hash/version or changed formal baseline is a conflict.", schema,
                com.harness.core.model.ToolCapability.RETRIEVAL);
    }

    @Override public String execute(JsonNode arguments) {
        try {
            if (arguments == null || !arguments.isObject()) throw new IllegalArgumentException("arguments must be an object");
            var fields = arguments.fieldNames();
            while (fields.hasNext()) if (!java.util.Set.of("draftId", "expectedContentHash", "limit", "cursor").contains(fields.next()))
                throw new IllegalArgumentException("Unsupported graph draft argument");
            String draftId = text(arguments, "draftId"); String hash = text(arguments, "expectedContentHash");
            int limit = arguments.has("limit") ? arguments.get("limit").intValue() : 0;
            if (arguments.has("limit") && (!arguments.get("limit").isIntegralNumber() || limit < 1)) throw new IllegalArgumentException("limit must be a positive integer");
            String cursor = arguments.has("cursor") && arguments.get("cursor").isTextual() ? arguments.get("cursor").asText() : "";
            if (arguments.has("cursor") && !arguments.get("cursor").isTextual()) throw new IllegalArgumentException("cursor must be text");
            var page = service.readPreview(java.util.Objects.requireNonNull(scope.get(), "Trusted graph draft scope is missing"), draftId, hash, limit, cursor);
            return mapper.writeValueAsString(ToolEnvelope.success(page.items(), page.pageInfo(), Map.of("viewType", "DRAFT_PREVIEW", "draftId", draftId, "contentHash", hash)));
        } catch (Exception e) {
            throw new ToolExecutionException(TOOL_NAME, "Unable to read graph draft: " + e.getMessage(), e);
        }
    }
    private static String text(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.asText();
    }

    @Override public com.harness.core.model.ToolOutput executeOutput(JsonNode arguments) {
        try {
            return com.harness.core.model.ToolOutput.json(mapper.readTree(execute(arguments)));
        } catch (java.io.IOException e) {
            throw new ToolExecutionException(TOOL_NAME, "Unable to encode graph draft preview", e);
        }
    }
}
