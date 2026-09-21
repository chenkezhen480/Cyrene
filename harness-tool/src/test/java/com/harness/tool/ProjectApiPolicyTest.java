package com.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ApiEndpoint;
import com.harness.core.model.AuthMode;
import com.harness.core.model.ProjectApiConfig;
import com.harness.core.model.TokenInjection;
import com.harness.tool.discovery.UpdateProjectApiTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectApiPolicyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void metaTools_exposeOnlyConfirmedCallableEndpoints() throws Exception {
        ProjectApiConfig config = config(
                endpoint("safe", "GET", AuthMode.USER_PASSTHROUGH, true, false),
                endpoint("draft", "GET", AuthMode.USER_PASSTHROUGH, false, false),
                endpoint("risky", "POST", AuthMode.BOT, true, false));

        JsonNode result = MAPPER.readTree(
                new ListApiEndpointsTool(() -> config).execute(MAPPER.createObjectNode()));

        assertThat(result.path("total").asInt()).isEqualTo(1);
        assertThat(result.path("endpoints").get(0).path("id").asText()).isEqualTo("safe");
    }

    @Test
    void endpointDetail_rejectsUnacknowledgedHighRiskEndpoint() {
        ProjectApiConfig config = config(
                endpoint("risky", "POST", AuthMode.BOT, true, false));
        var args = MAPPER.createObjectNode().put("endpointId", "risky");

        String result = new GetApiEndpointDetailTool(() -> config).execute(args);

        assertThat(result).contains("high-risk").contains("risk acknowledgement");
    }

    @Test
    void callDiscoveredApi_rejectsUnconfirmedEndpointBeforeNetworkCall() {
        ProjectApiConfig config = config(
                endpoint("draft", "GET", AuthMode.USER_PASSTHROUGH, false, false));
        var args = MAPPER.createObjectNode().put("endpointId", "draft");

        assertThatThrownBy(() -> new CallDiscoveredApiTool(() -> config).execute(args))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("has not been confirmed");
    }

    @Test
    void updateProjectApi_requiresConfirmationAndCannotSelfApprove() {
        ToolRegistry registry = new ToolRegistry();
        registry.loadFromConfig(config(
                endpoint("draft", "GET", AuthMode.USER_PASSTHROUGH, false, false)));
        UpdateProjectApiTool tool = new UpdateProjectApiTool(registry);
        var args = MAPPER.createObjectNode()
                .put("action", "update")
                .put("id", "draft")
                .put("confirmed", true);

        assertThat(tool.spec().requiresConfirmation()).isTrue();
        assertThat(tool.spec().parameters().path("required").isArray()).isTrue();
        assertThat(tool.spec().parameters()
                .path("properties").path("riskAcknowledged").path("type").asText())
                .isEqualTo("boolean");
        assertThatThrownBy(() -> tool.execute(args))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("cannot confirm endpoints");
    }

    @Test
    void groupedApiKeepsConfigurationSnapshotAndPolicyChecks() {
        ToolRegistry registry = new ToolRegistry();
        registry.loadFromConfig(config(endpoint("old", "GET", AuthMode.USER_PASSTHROUGH, true, false)));
        RunToolCatalog oldRun = registry.snapshot();
        registry.loadFromConfig(config(endpoint("new", "GET", AuthMode.USER_PASSTHROUGH, true, false),
                endpoint("draft", "GET", AuthMode.USER_PASSTHROUGH, false, false)));
        var list = MAPPER.createObjectNode().put("action", "list");
        list.putObject("input");
        assertThat(oldRun.get("project_api").execute(list)).contains("old").doesNotContain("new");
        assertThat(registry.get("project_api").execute(list)).contains("new").doesNotContain("old", "draft");
        assertThat(registry.getAll()).extracting(com.harness.core.model.ToolSpec::name)
                .containsExactly("project_api", "update_project_api");
        var call = MAPPER.createObjectNode().put("action", "call");
        call.putObject("input").put("endpointId", "draft");
        assertThatThrownBy(() -> registry.get("project_api").execute(call))
                .isInstanceOf(ToolExecutionException.class).hasMessageContaining("has not been confirmed");
        ToolGroup narrowed = (ToolGroup) registry.snapshot().excluding(java.util.Set.of("call_discovered_api"))
                .get("project_api");
        assertThat(narrowed.availableActions()).doesNotContain("call");
    }

    private ProjectApiConfig config(ApiEndpoint... endpoints) {
        return new ProjectApiConfig(
                "2026-07-27T00:00:00Z",
                "test",
                "https://api.example.com",
                ".",
                List.of(endpoints));
    }

    private ApiEndpoint endpoint(String id, String method, AuthMode authMode,
                                 boolean confirmed, boolean riskAcknowledged) {
        return new ApiEndpoint(
                id,
                id,
                id,
                method,
                "/" + id,
                null,
                "test",
                authMode,
                "testToken",
                new TokenInjection("header", "Authorization", "Bearer "),
                MAPPER.createObjectNode().put("type", "object"),
                confirmed,
                riskAcknowledged);
    }
}
