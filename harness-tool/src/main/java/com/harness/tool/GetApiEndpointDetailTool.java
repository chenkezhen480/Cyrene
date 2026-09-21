package com.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ApiEndpoint;
import com.harness.core.model.ProjectApiConfig;
import com.harness.core.model.ResultStatus;
import com.harness.core.model.ToolExecutionOutcome;
import com.harness.core.model.ToolOutput;
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
        endpointIdProp.put("description", "接口 ID，如 ep_0001。可通过 project_api 的 list 动作获取。");
        props.set("endpointId", endpointIdProp);

        ObjectNode params = mapper.createObjectNode();
        params.put("type", "object");
        params.set("properties", props);
        params.set("required", mapper.createArrayNode().add("endpointId"));

        return new ToolSpec(
                "get_api_endpoint_detail",
                "查询单个接口的完整定义（含参数 JSON Schema、鉴权模式、返回类型等）。",
                params,
                com.harness.core.model.ToolCapability.READ
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        return executeOutcome(arguments).content().modelContent();
    }

    @Override
    public ToolExecutionOutcome executeOutcome(JsonNode arguments) {
        String endpointId = arguments != null && arguments.has("endpointId")
                ? arguments.get("endpointId").asText() : "";
        if (endpointId.isBlank()) {
            throw new ToolExecutionException(
                    "get_api_endpoint_detail", "endpointId is required");
        }

        ProjectApiConfig config = configSupplier.get();
        if (config == null || config.endpoints() == null) {
            return outcome("No API endpoints configured.", ResultStatus.EMPTY);
        }

        for (ApiEndpoint ep : config.endpoints()) {
            if (ep.id().equals(endpointId)) {
                if (!ProjectApiPolicy.isCallable(ep)) {
                    return outcome(ProjectApiPolicy.rejectionReason(ep), ResultStatus.EMPTY);
                }
                ObjectNode json = endpointToJson(ep);
                // Show effective baseUrl (global config-level if endpoint doesn't have one)
                String effectiveBaseUrl = config.resolveBaseUrl(ep);
                if (effectiveBaseUrl != null && !effectiveBaseUrl.isBlank()) {
                    json.put("effectiveBaseUrl", effectiveBaseUrl);
                }
                return outcome(json.toString(), ResultStatus.AVAILABLE);
            }
        }
        return outcome(
                "Endpoint '" + endpointId
                        + "' not found. Call project_api with action=list to see available endpoints.",
                ResultStatus.EMPTY);
    }

    private static ToolExecutionOutcome outcome(String text, ResultStatus status) {
        return ToolExecutionOutcome.succeeded(ToolOutput.text(text), status);
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
