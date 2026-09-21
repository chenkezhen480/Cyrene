package com.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.model.ToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.tool.filesystem.CodeWorkspaceTool;
import com.harness.tool.filesystem.FileSystemAccessPolicy;
import com.harness.tool.filesystem.FileSystemWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryTest {

    ToolRegistry registry;
    static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
    }

    private Tool createTool(String name, String description) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("type", "object");
        ToolSpec spec = new ToolSpec(name, description, params);
        return new Tool() {
            @Override
            public ToolSpec spec() { return spec; }

            @Override
            public String execute(JsonNode arguments) { return "ok"; }
        };
    }

    @Test
    void register_thenGet_returnsTool() {
        Tool tool = createTool("search", "Search tool");
        registry.register(tool);

        assertThat(registry.get("search")).isSameAs(tool);
    }

    @Test
    void get_notRegistered_returnsNull() {
        assertThat(registry.get("nonexistent")).isNull();
    }

    @Test
    void contains_registered_returnsTrue() {
        registry.register(createTool("search", "desc"));
        assertThat(registry.contains("search")).isTrue();
    }

    @Test
    void contains_notRegistered_returnsFalse() {
        assertThat(registry.contains("search")).isFalse();
    }

    @Test
    void size_empty_returnsZero() {
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    void size_afterRegistrations_returnsCorrectCount() {
        registry.register(createTool("a", "desc a"));
        registry.register(createTool("b", "desc b"));
        registry.register(createTool("c", "desc c"));

        assertThat(registry.size()).isEqualTo(3);
    }

    @Test
    void register_duplicateName_isRejected() {
        Tool first = createTool("search", "first version");
        Tool second = createTool("search", "second version");
        registry.register(first);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> registry.register(second))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("search");
        assertThat(registry.get("search")).isSameAs(first);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void replace_explicitlyReplacesExistingTool() {
        Tool first = createTool("search", "first version");
        Tool second = createTool("search", "second version");
        registry.register(first);

        registry.replace(second);

        assertThat(registry.get("search")).isSameAs(second);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void getAll_returnsAllSpecs() {
        registry.register(createTool("alpha", "Alpha tool"));
        registry.register(createTool("beta", "Beta tool"));

        List<ToolSpec> specs = registry.getAll();
        assertThat(specs).hasSize(2);
        assertThat(specs).extracting(ToolSpec::name).containsExactly("alpha", "beta");
    }

    @Test
    void getAll_empty_returnsEmptyList() {
        assertThat(registry.getAll()).isEmpty();
    }

    @Test
    void runCatalog_excludesToolFromDefinitionsAndLookup() {
        registry.register(createTool("allowed", "Allowed tool"));
        registry.register(createTool("blocked", "Blocked tool"));
        RunToolCatalog filtered = registry.snapshot().excluding(Set.of("blocked"));

        assertThat(filtered.getAll())
                .extracting(ToolSpec::name)
                .containsExactly("allowed");
        assertThat(filtered.get("blocked")).isNull();
        assertThat(filtered.contains("blocked")).isFalse();
        assertThat(filtered.size()).isEqualTo(1);
    }

    /**
     * The tenant/identity permission store and the management UI both key on tool name, so what a
     * name removes is part of the contract. The code tools publish one tool, and a name either
     * removes that tool or one of the actions behind it — never nothing, which is what the pre-merge
     * spellings would do if {@code excluding} only matched registered names.
     */
    @Test
    void runCatalog_removesTheCodeWorkspaceWhollyOrOneActionAtATime() {
        FileSystemAccessPolicy policy = FileSystemAccessPolicy.host(
                Path.of(".").toAbsolutePath().toString(), List.of(),
                FileSystemAccessPolicy.Settings.defaults());
        FileSystemWorkspace workspace = FileSystemWorkspace.host(policy.searchRoot());
        registry.register(CodeWorkspaceTool.of(policy, workspace, List.of()));

        RunToolCatalog whole = registry.snapshot().excluding(Set.of(CodeWorkspaceTool.TOOL_NAME));
        assertThat(whole.get(CodeWorkspaceTool.TOOL_NAME)).isNull();
        assertThat(whole.contains(CodeWorkspaceTool.TOOL_NAME)).isFalse();

        for (String actionName : List.of("code_workspace.edit", "edit")) {
            RunToolCatalog narrowed = registry.snapshot().excluding(Set.of(actionName));
            assertThat(narrowed.contains(CodeWorkspaceTool.TOOL_NAME))
                    .as("%s must narrow rather than remove the tool", actionName)
                    .isTrue();
            assertThat(narrowed.getAll()).extracting(ToolSpec::name)
                    .containsExactly(CodeWorkspaceTool.TOOL_NAME);
            assertThat(((CodeWorkspaceTool) narrowed.get(CodeWorkspaceTool.TOOL_NAME))
                    .availableActions())
                    .containsExactly("read", "glob", "grep", "tree", "write", "patch", "help");
        }

        // Denying the write actions still leaves a tool worth publishing.
        assertThat(registry.snapshot().excluding(Set.of("edit", "write"))
                .contains(CodeWorkspaceTool.TOOL_NAME)).isTrue();

        // Denying every action leaves nothing worth publishing, so the tool goes rather than
        // staying as an empty enum the provider has to make sense of.
        RunToolCatalog emptied = registry.snapshot().excluding(CodeWorkspaceTool.ACTIONS);
        assertThat(emptied.contains(CodeWorkspaceTool.TOOL_NAME)).isFalse();
    }

    /**
     * A sub-agent's allowlist is written by the model at spawn time and validated against the
     * parent catalog, so it may name one action — or the pre-merge spelling of one — rather than
     * the whole tool.
     */
    @Test
    void runCatalog_allowsTheCodeWorkspaceWhollyOrOneActionAtATime() {
        FileSystemAccessPolicy policy = FileSystemAccessPolicy.host(
                Path.of(".").toAbsolutePath().toString(), List.of(),
                FileSystemAccessPolicy.Settings.defaults());
        registry.register(CodeWorkspaceTool.of(
                policy, FileSystemWorkspace.host(policy.searchRoot()), List.of()));

        RunToolCatalog whole = registry.snapshot().allowing(Set.of(CodeWorkspaceTool.TOOL_NAME));
        assertThat(whole.allowing(Set.of(CodeWorkspaceTool.TOOL_NAME))).isSameAs(whole);

        RunToolCatalog partial = registry.snapshot().allowing(Set.of("code_workspace.read", "tree"));
        assertThat(((CodeWorkspaceTool) partial.get(CodeWorkspaceTool.TOOL_NAME)).availableActions())
                .containsExactly("read", "tree", "help");
        assertThat(partial.contains("read")).isTrue();
        assertThat(partial.contains("edit")).isFalse();

        assertThat(registry.snapshot().allowing(Set.of("nothing-like-this"))
                .contains(CodeWorkspaceTool.TOOL_NAME)).isFalse();
    }

    @Test
    void runCatalog_containsAnswersForActionsAsWellAsTools() {
        FileSystemAccessPolicy policy = FileSystemAccessPolicy.host(
                Path.of(".").toAbsolutePath().toString(), List.of(),
                FileSystemAccessPolicy.Settings.defaults());
        registry.register(CodeWorkspaceTool.of(
                policy, FileSystemWorkspace.host(policy.searchRoot()), List.of()));

        RunToolCatalog catalog = registry.snapshot();

        assertThat(catalog.contains(CodeWorkspaceTool.TOOL_NAME)).isTrue();
        assertThat(catalog.contains("code_workspace.read")).isTrue();
        assertThat(catalog.contains("read")).isTrue();
        assertThat(catalog.contains("code_workspace.teleport")).isFalse();
        assertThat(catalog.contains("shell")).isFalse();
    }

    @Test
    void runCatalog_filteringReusesSnapshotSpecifications() {
        AtomicInteger specificationCalls = new AtomicInteger();
        Tool counted = new Tool() {
            @Override
            public ToolSpec spec() {
                specificationCalls.incrementAndGet();
                return createTool("counted", "Counted tool").spec();
            }

            @Override
            public String execute(JsonNode arguments) {
                return "ok";
            }
        };
        registry.register(counted);
        registry.register(createTool("blocked", "Blocked tool"));
        RunToolCatalog snapshot = registry.snapshot();
        int callsAfterSnapshot = specificationCalls.get();

        RunToolCatalog filtered = snapshot.excluding(Set.of("blocked"));

        assertThat(specificationCalls).hasValue(callsAfterSnapshot);
        assertThat(filtered.getAll())
                .extracting(ToolSpec::name)
                .containsExactly("counted");
    }

    @Test
    void runCatalog_emptyExclusionReturnsOriginalSnapshot() {
        registry.register(createTool("allowed", "Allowed tool"));
        RunToolCatalog snapshot = registry.snapshot();

        assertThat(snapshot.excluding(Set.of())).isSameAs(snapshot);
    }

    @Test
    void runCatalog_isUnaffectedByLaterRegistrationAndReplacement() {
        Tool original = createTool("search", "original");
        registry.register(original);
        RunToolCatalog snapshot = registry.snapshot();

        registry.register(createTool("late", "registered later"));
        registry.replace(createTool("search", "replacement"));

        assertThat(snapshot.getAll())
                .extracting(ToolSpec::name)
                .containsExactly("search");
        assertThat(snapshot.get("search")).isSameAs(original);
        assertThat(snapshot.get("late")).isNull();
    }

    @Test
    void runCatalog_replacingCreatesAnIndependentRequestScopedCatalog() {
        Tool original = createTool("structured_output", "chat block");
        Tool terminal = createTool("structured_output", "terminal contract");
        registry.register(original);
        RunToolCatalog snapshot = registry.snapshot();

        RunToolCatalog replaced = snapshot.replacing(terminal);

        assertThat(snapshot.get("structured_output")).isSameAs(original);
        assertThat(replaced.get("structured_output")).isSameAs(terminal);
        assertThat(replaced.getAll())
                .extracting(ToolSpec::description)
                .containsExactly("terminal contract");
        assertThat(replaced.version()).isEqualTo(snapshot.version());
    }

    @Test
    void allowlist_doesNotAdmitToolsRegisteredAfterSnapshot() {
        registry.register(createTool("allowed", "Allowed tool"));
        RunToolCatalog allowed = registry.snapshot().allowing(Set.of("allowed", "late"));

        registry.register(createTool("late", "Late tool"));

        assertThat(allowed.getAll())
                .extracting(ToolSpec::name)
                .containsExactly("allowed");
        assertThat(allowed.get("late")).isNull();
    }

    @Test
    void emptyAllowlist_remainsEmptyAfterRegistryChanges() {
        RunToolCatalog noTools = registry.snapshot().allowing(Set.of());

        registry.register(createTool("late", "Late tool"));

        assertThat(noTools.getAll()).isEmpty();
        assertThat(noTools.get("late")).isNull();
    }
}
