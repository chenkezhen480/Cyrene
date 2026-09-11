package com.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.model.ApiEndpoint;
import com.harness.core.model.ProjectApiConfig;
import com.harness.core.model.ResultStatus;
import com.harness.core.model.ToolExecutionOutcome;
import com.harness.core.model.ToolOutput;
import com.harness.core.model.ToolSpec;

import java.util.function.Supplier;

/**
 * Tool that returns a compact directory of all discovered API endpoints.
 * Returns only id, name, description, method, path — no full parameter schema.
 * The LLM uses this to discover available endpoints, then calls
 * {@link GetApiEndpointDetailTool} for full details when needed.
 */
public class ListApiEndpointsTool implements Tool {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final Supplier<ProjectApiConfig> configSupplier;

    public ListApiEndpointsTool(Supplier<ProjectApiConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    @Override
    public ToolSpec spec() {
        ObjectNode params = mapper.createObjectNode();
        params.put("type", "object");
        params.set("properties", mapper.createObjectNode());
        return new ToolSpec(
                "list_api_endpoints",
                "列出已发现的内部接口目录（返回 id、名称、描述、方法、路径）。" +
                "如需完整参数定义，请用 get_api_endpoint_detail(endpointId) 查询。",
                params,
                com.harness.core.model.ToolCapability.RETRIEVAL
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        return executeOutcome(arguments).content().modelContent();
    }

    @Override
    public ToolExecutionOutcome executeOutcome(JsonNode arguments) {
        ProjectApiConfig config = configSupplier.get();
        var endpoints = ProjectApiPolicy.callableEndpoints(config);
        if (endpoints.isEmpty()) {
            return outcome("No confirmed API endpoints are available.", ResultStatus.EMPTY);
        }

        ArrayNode arr = mapper.createArrayNode();
        for (ApiEndpoint ep : endpoints) {
            ObjectNode node = mapper.createObjectNode();
            node.put("id", ep.id());
            node.put("name", ep.name());
            node.put("description", ep.description());
            node.put("method", ep.method());
            node.put("path", ep.path());
            arr.add(node);
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("total", arr.size());
        result.put("projectDescription", config.projectDescription());
        result.set("endpoints", arr);
        return outcome(result.toString(), ResultStatus.AVAILABLE);
    }

    private static ToolExecutionOutcome outcome(String text, ResultStatus status) {
        return ToolExecutionOutcome.succeeded(ToolOutput.text(text), status);
    }
}
