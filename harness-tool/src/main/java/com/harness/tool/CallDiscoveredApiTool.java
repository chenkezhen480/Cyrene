package com.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.model.ApiEndpoint;
import com.harness.core.model.ProjectApiConfig;
import com.harness.core.model.ToolSpec;

import java.util.function.Supplier;

/**
 * Tool that executes a discovered API endpoint by its ID.
 * Looks up the endpoint from the in-memory {@link ProjectApiConfig},
 * then delegates to {@link HttpApiTool} for actual HTTP execution.
 *
 * <p>The LLM first calls {@link ListApiEndpointsTool} to discover available endpoints,
 * then {@link GetApiEndpointDetailTool} to understand parameters,
 * then this tool to actually call the API.
 */
public class CallDiscoveredApiTool implements Tool {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final Supplier<ProjectApiConfig> configSupplier;

    public CallDiscoveredApiTool(Supplier<ProjectApiConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    @Override
    public ToolSpec spec() {
        ObjectNode props = mapper.createObjectNode();

        ObjectNode endpointIdProp = mapper.createObjectNode();
        endpointIdProp.put("type", "string");
        endpointIdProp.put("description", "接口 ID，如 ep_0001。可通过 list_api_endpoints 获取。");
        props.set("endpointId", endpointIdProp);

        ObjectNode paramsProp = mapper.createObjectNode();
        paramsProp.put("type", "object");
        paramsProp.put("description", "调用参数（键值对）。路径参数会自动替换到 URL 中，其余作为 query 参数或 request body。");
        props.set("params", paramsProp);

        ObjectNode params = mapper.createObjectNode();
        params.put("type", "object");
        params.set("properties", props);
        params.set("required", mapper.createArrayNode().add("endpointId"));

        return new ToolSpec(
                "call_discovered_api",
                "调用一个已发现的内部接口。先通过 list_api_endpoints 和 get_api_endpoint_detail 了解接口定义。",
                params
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        String endpointId = arguments != null && arguments.has("endpointId")
                ? arguments.get("endpointId").asText() : "";
        if (endpointId.isBlank()) {
            return "Error: endpointId is required. Call list_api_endpoints() first.";
        }

        ProjectApiConfig config = configSupplier.get();
        if (config == null || config.endpoints() == null) {
            return "Error: No API endpoints configured.";
        }

        // Find endpoint by ID
        ApiEndpoint target = null;
        for (ApiEndpoint ep : config.endpoints()) {
            if (ep.id().equals(endpointId)) {
                target = ep;
                break;
            }
        }
        if (target == null) {
            return "Error: Endpoint '" + endpointId + "' not found. Call list_api_endpoints() to see available endpoints.";
        }

        // Resolve baseUrl: endpoint-level overrides global config-level
        String resolvedBaseUrl = config.resolveBaseUrl(target);
        if (resolvedBaseUrl == null || resolvedBaseUrl.isBlank()) {
            return "Error: No baseUrl configured for endpoint '" + endpointId + "'. Set global baseUrl in config or endpoint-level baseUrl.";
        }

        // Create endpoint with resolved baseUrl
        ApiEndpoint resolved = new ApiEndpoint(target.id(), target.name(), target.description(),
                target.method(), target.path(), resolvedBaseUrl, target.source(),
                target.authMode(), target.credentialKey(), target.tokenInjection(),
                target.parameters(), target.confirmed(), target.riskAcknowledged());

        // Extract params (the actual API call parameters)
        JsonNode params = arguments != null ? arguments.get("params") : null;

        // Delegate to HttpApiTool for actual HTTP execution
        HttpApiTool httpTool = new HttpApiTool(resolved);
        return httpTool.execute(params);
    }
}
