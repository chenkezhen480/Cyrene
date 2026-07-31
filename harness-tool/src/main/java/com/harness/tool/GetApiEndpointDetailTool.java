package com.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.model.ApiEndpoint;
import com.harness.core.model.ProjectApiConfig;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;

import java.util.function.Supplier;

/**
 * Tool that returns the full definition of a single API endpoint,
 * including parameters JSON Schema, authMode, tokenInjection, returnType, etc.
 */
public class GetApiEndpointDetailTool implements Tool {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final Supplier<ProjectApiConfig> configSupplier;

    public GetApiEndpointDetailTool(Supplier<ProjectApiConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    @Override
    public ToolSpec spec() {
        ObjectNode props = mapper.createObjectNode();
        ObjectNode endpointIdProp = mapper.createObjectNode();
        endpointIdProp.put("type", "string");
        endpointIdProp.put("description", "接口 ID，如 ep_0001。可通过 list_api_endpoints 获取。");
        props.set("endpointId", endpointIdProp);

        ObjectNode params = mapper.createObjectNode();
        params.put("type", "object");
        params.set("properties", props);
        params.set("required", mapper.createArrayNode().add("endpointId"));

        return new ToolSpec(
                "get_api_endpoint_detail",
                "查询单个接口的完整定义（含参数 JSON Schema、鉴权模式、返回类型等）。",
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

        for (ApiEndpoint ep : config.endpoints()) {
            if (ep.id().equals(endpointId)) {
                if (!ProjectApiPolicy.isCallable(ep)) {
                    ToolResult.setCurrentStatus(ToolResult.ResultStatus.EMPTY);
                    return "Error: " + ProjectApiPolicy.rejectionReason(ep);
                }
                ObjectNode json = endpointToJson(ep);
                // Show effective baseUrl (global config-level if endpoint doesn't have one)
                String effectiveBaseUrl = config.resolveBaseUrl(ep);
                if (effectiveBaseUrl != null && !effectiveBaseUrl.isBlank()) {
                    json.put("effectiveBaseUrl", effectiveBaseUrl);
                }
                ToolResult.setCurrentStatus(ToolResult.ResultStatus.SUCCESS);
                return json.toString();
            }
        }
        ToolResult.setCurrentStatus(ToolResult.ResultStatus.EMPTY);
        return "Error: Endpoint '" + endpointId + "' not found. Call list_api_endpoints() to see available endpoints.";
    }

    /**
     * Serialize an ApiEndpoint to a detailed JSON object.
     */
    static ObjectNode endpointToJson(ApiEndpoint ep) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", ep.id());
        node.put("name", ep.name());
        node.put("description", ep.description());
        node.put("method", ep.method());
        node.put("path", ep.path());
        node.put("baseUrl", ep.baseUrl());
        node.put("source", ep.source());
        node.put("authMode", ep.authMode() != null ? ep.authMode().name() : null);
        node.put("credentialKey", ep.credentialKey());
        if (ep.tokenInjection() != null) {
            ObjectNode ti = mapper.createObjectNode();
            ti.put("location", ep.tokenInjection().location());
            ti.put("name", ep.tokenInjection().name());
            ti.put("prefix", ep.tokenInjection().prefix());
            node.set("tokenInjection", ti);
        }
        if (ep.parameters() != null) {
            node.set("parameters", ep.parameters());
        }
        node.put("confirmed", ep.confirmed());
        node.put("riskAcknowledged", ep.riskAcknowledged());
        return node;
    }
}
