package com.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ResultStatus;
import com.harness.core.model.ToolCall;
import com.harness.core.model.ToolCapability;
import com.harness.core.model.ToolExecutionOutcome;
import com.harness.core.model.ToolOutput;
import com.harness.core.model.ToolSpec;
import com.harness.tool.confirmation.ConfirmationManager;
import com.harness.tool.web.BrowserControlTool;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolGroupTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void snapshotsNarrowDefinitionsHelpAndExecutionWithoutWideningChildren() {
        Tool search = tool("web_search");
        Map<String, Tool> injected = new LinkedHashMap<>();
        injected.put("search", search);
        injected.put("read", tool("read_url_content"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ToolGroup("web", "Web access", injected, List.of()));
        RunToolCatalog parent = registry.snapshot();
        injected.put("browser", tool("browser_control"));
        registry.replace(new ToolGroup("web", "New web access", injected, List.of()));

        assertThat(parent.contains("browser_control")).isFalse();
        assertThat(parent.get("web_search")).isNull();
        for (String denied : List.of("web.search", "web_search")) {
            RunToolCatalog child = parent.excluding(Set.of(denied)).allowing(Set.of("web"));
            ToolGroup group = (ToolGroup) child.get("web");
            assertThat(group.availableActions()).containsExactly("read", "help");
            assertThat(group.spec().parameters().path("properties").path("action").path("enum"))
                    .extracting(JsonNode::asText).containsExactly("read", "help");
            assertThat(group.executeOutcome(args("help")).content().json().path("actions"))
                    .extracting(JsonNode::asText).containsExactly("read");
            ObjectNode help = args("help");
            ((ObjectNode) help.get("input")).put("action", "search");
            assertThatThrownBy(() -> group.executeOutcome(help)).isInstanceOf(ToolExecutionException.class);
            assertThatThrownBy(() -> group.executeOutcome(args("search")))
                    .isInstanceOf(ToolExecutionException.class);
            assertThat(child.allowing(Set.of("web_search")).size()).isZero();
        }
        assertThat(parent.allowing(Set.of("web_search")).contains("web.read")).isFalse();
        assertThat(parent.excluding(Set.of("web")).size()).isZero();
        assertThat(parent.excluding(Set.of("web_search", "read_url_content")).size()).isZero();
        // The filesystem's legacy 'read' must never grant web.read.
        assertThat(parent.allowing(Set.of("read")).size()).isZero();
        assertThat(parent.canonicalName("web_search")).isEqualTo("web.search");
    }

    @Test
    void preservesDelegateOutcomeAndRejectsMalformedInput() {
        ToolGroup group = new ToolGroup("web", "Web access", Map.of("search", tool("web_search")), List.of());
        var result = group.executeOutcome(args("search"));
        assertThat(result.resultStatus()).isEqualTo(ResultStatus.EMPTY);
        assertThat(result.content().json().path("matched").asBoolean()).isFalse();
        assertThatThrownBy(() -> group.executeOutcome(MAPPER.createObjectNode().put("action", "search")))
                .isInstanceOf(ToolExecutionException.class).hasMessageContaining("input");
        assertThatThrownBy(() -> group.executeOutcome(args("unknown")))
                .isInstanceOf(ToolExecutionException.class).hasMessageContaining("unavailable");
    }

    @Test
    void browserRejectsControlActionsAndHonorsConfiguredConfirmation() {
        BrowserControlTool browser = new BrowserControlTool(new OkHttpClient(), "http://localhost:8081", "token");
        ToolGroup group = new ToolGroup("web", "Web access", Map.of("browser", browser), List.of());
        ObjectNode click = args("browser");
        ((ObjectNode) click.get("input")).put("url", "https://example.com")
                .put("action", "click").put("ref", "button1");
        ToolExecutor executor = new ToolExecutor(new ConfirmationManager(Duration.ofSeconds(5)));
        assertThat(executor.executeAuthorized(ToolCall.of("web", click), group, null).error())
                .contains("URL reading only");
        assertThat(group.requiresConfirmation(click)).isFalse();
        assertThat(executor.executeAuthorized(ToolCall.of("web",
                MAPPER.createObjectNode().put("action", "browser")), group, null).error())
                .contains("input must be a JSON object");
        assertThat(group.delegate(click)).isSameAs(browser);
        assertThat(group.delegate(args("help"))).isNull();
        assertThat(group.requiresConfirmation(args("help"))).isFalse();
        for (String configured : List.of("web", "web.browser", "browser_control")) {
            ToolGroup guarded = new ToolGroup("web", "Web access", Map.of("browser", browser), List.of(configured));
            ObjectNode open = args("browser");
            ((ObjectNode) open.get("input")).put("url", "https://example.com");
            assertThat(guarded.requiresConfirmation(open)).isTrue();
        }
    }

    private static ObjectNode args(String action) {
        ObjectNode args = MAPPER.createObjectNode().put("action", action);
        args.putObject("input");
        return args;
    }

    private static Tool tool(String name) {
        return new Tool() {
            public ToolSpec spec() {
                return new ToolSpec(name, name, MAPPER.createObjectNode().put("type", "object"), ToolCapability.READ);
            }
            public String execute(JsonNode args) { throw new AssertionError("must preserve typed outcome"); }
            public ToolExecutionOutcome executeOutcome(JsonNode args) {
                return ToolExecutionOutcome.succeeded(
                        ToolOutput.json(MAPPER.createObjectNode().put("matched", false)), ResultStatus.EMPTY);
            }
        };
    }
}
