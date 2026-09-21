package com.harness.tool.filesystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ResultStatus;
import com.harness.core.model.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeWorkspaceToolTest {

    @TempDir
    Path root;

    private CodeWorkspaceTool tool(String... confirmActions) {
        return CodeWorkspaceTool.of(policy(), FileSystemWorkspace.host(root), List.of(confirmActions));
    }

    private FileSystemAccessPolicy policy() {
        return FileSystemAccessPolicy.host(root.toString(), List.of(),
                FileSystemAccessPolicy.Settings.defaults());
    }

    private static ObjectNode args(String action) {
        ObjectNode node = ToolArguments.MAPPER.createObjectNode();
        node.put("action", action);
        return node;
    }

    private static Set<String> propertiesOf(ToolSpec spec) {
        Set<String> names = new LinkedHashSet<>();
        spec.parameters().path("properties").fieldNames()
                .forEachRemaining(names::add);
        return names;
    }

    /** The action property's description, which spells out each action's parameters. */
    private static String actionGuide(ToolSpec spec) {
        return spec.parameters().path("properties").path("action").path("description").asText();
    }

    private static List<String> actionEnum(ToolSpec spec) {
        List<String> values = new ArrayList<>();
        spec.parameters().path("properties").path("action").path("enum")
                .forEach(node -> values.add(node.asText()));
        return values;
    }

    @Test
    void publishesOneToolNamedForTheWorkspace() {
        assertThat(tool().spec().name()).isEqualTo(CodeWorkspaceTool.TOOL_NAME);
        assertThat(tool().spec().name()).isNotIn(CodeWorkspaceTool.ACTIONS);
    }

    @Test
    void writableWorkspaceOffersEveryActionAndReadOnlyOffersOnlyTheReadingOnes() {
        FileSystemAccessPolicy policy = policy();

        assertThat(actionEnum(tool().spec()))
                .containsExactlyElementsOf(CodeWorkspaceTool.ACTIONS);

        CodeWorkspaceTool readOnly = CodeWorkspaceTool.of(
                policy, FileSystemWorkspace.readOnly(root), List.of());
        assertThat(actionEnum(readOnly.spec()))
                .containsExactly("read", "glob", "grep", "tree", "help");
        assertThat(readOnly.writable()).isFalse();
    }

    @Test
    void publishesOnlyActionAndInputAndLoadsDelegateSchemaThroughHelp() {
        ToolSpec spec = tool().spec();

        assertThat(propertiesOf(spec)).containsExactly("action", "input");
        assertThat(spec.parameters().path("required")).extracting(JsonNode::asText)
                .containsExactly("action", "input");

        ObjectNode helpInput = ToolArguments.MAPPER.createObjectNode().put("action", "read");
        ObjectNode help = ToolArguments.MAPPER.createObjectNode().put("action", "help");
        help.set("input", helpInput);
        JsonNode json = tool().executeOutcome(help).content().json();
        assertThat(json.path("action").asText()).isEqualTo("read");
        assertThat(json.path("inputSchema").path("properties").has("file_path")).isTrue();
    }

    /** A narrowed variant must not advertise what it cannot do. */
    @Test
    void descriptionNamesTheAvailableActionsAndNoOthers() {
        assertThat(actionGuide(tool().spec())).contains("edit").contains("write");

        CodeWorkspaceTool readOnly = tool().denying(Set.of("edit", "write", "patch"));
        ToolSpec narrowed = readOnly.spec();

        assertThat(actionEnum(narrowed)).doesNotContain("edit", "write", "patch");
        // The guide is what the model reads to pick an action; leaving `edit` in it would send the
        // model at an action the enum no longer offers.
        assertThat(actionGuide(narrowed)).contains("read").doesNotContain("edit")
                .doesNotContain("write").doesNotContain("patch");
        assertThat(narrowed.description()).doesNotContain("edit").doesNotContain("write")
                .doesNotContain("patch");
    }

    @Test
    void dispatchesEachActionToItsOwnImplementation() throws IOException {
        Files.writeString(root.resolve("App.java"), "class App {}\n");

        assertThat(tool().execute(args("read").put("file_path", root.resolve("App.java").toString())))
                .contains("class App");
        assertThat(tool().execute(args("tree"))).contains("App.java");
        assertThat(tool().execute(args("glob").put("pattern", "**/*.java")))
                .contains("App.java");
        assertThat(tool().execute(args("grep").put("pattern", "class App")))
                .contains("class App");
        assertThat(tool().execute(args("write")
                .put("file_path", root.resolve("New.java").toString())
                .put("content", "fresh\n"))).isNotBlank();
        assertThat(tool().execute(args("edit")
                .put("file_path", root.resolve("New.java").toString())
                .put("old_string", "fresh")
                .put("new_string", "edited"))).isNotBlank();

        ObjectNode patchInput = ToolArguments.MAPPER.createObjectNode().put("patch", """
                *** Update File: New.java
                @@
                -edited
                +patched
                *** End Patch
                """);
        ObjectNode patch = ToolArguments.MAPPER.createObjectNode().put("action", "patch");
        patch.set("input", patchInput);
        assertThat(tool().execute(patch)).contains("Patched");

        assertThat(Files.readString(root.resolve("New.java"))).isEqualTo("patched\n");
    }

    /**
     * The Inspector tells a search that matched nothing from a search that is broken, and the whole
     * reflection path depends on that distinction surviving the merge.
     */
    @Test
    void passesTheDelegatesResultStatusThroughUntouched() throws IOException {
        assertThat(tool().executeOutcome(args("grep").put("pattern", "definitely-not-present"))
                .resultStatus()).isEqualTo(ResultStatus.EMPTY);

        assertThat(tool().executeOutcome(args("tree")).resultStatus()).isEqualTo(ResultStatus.EMPTY);

        Files.writeString(root.resolve("App.java"), "class App {}\n");
        assertThat(tool().executeOutcome(args("tree")).resultStatus())
                .isEqualTo(ResultStatus.AVAILABLE);
    }

    /** {@code additionalProperties} is schema-only; a sibling action's leftovers must stay inert. */
    @Test
    void ignoresFieldsThatBelongToAnotherAction() throws IOException {
        Files.writeString(root.resolve("App.java"), "class App {}\n");

        String output = tool().execute(args("read")
                .put("file_path", root.resolve("App.java").toString())
                .put("pattern", "unused-regex")
                .put("replace_all", true));

        assertThat(output).contains("class App");
    }

    @Test
    void rejectsAMissingOrUnknownActionAndSaysWhatIsAvailable() {
        assertThatThrownBy(() -> tool().executeOutcome(ToolArguments.MAPPER.createObjectNode()))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("action");

        assertThatThrownBy(() -> tool().executeOutcome(args("teleport")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("teleport")
                .hasMessageContaining("read")
                .hasMessageContaining("write");
    }

    /**
     * The flat schema marks every field optional, so a wrong call is caught by the implementation
     * behind the action rather than by the provider. It has to say which field is missing.
     */
    @Test
    void stillReportsTheMissingArgumentOfTheChosenAction() throws IOException {
        assertThatThrownBy(() -> tool().executeOutcome(args("read")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("file_path");

        assertThatThrownBy(() -> tool().executeOutcome(args("grep")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("pattern");

        Files.writeString(root.resolve("App.java"), "class App {}\n");
        assertThatThrownBy(() -> tool().executeOutcome(
                args("edit").put("file_path", root.resolve("App.java").toString())))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("old_string");
    }

    @Test
    void aDeniedActionLeavesTheEnumAndTheLookup() {
        CodeWorkspaceTool denied = tool().denying(Set.of("code_workspace.edit"));

        assertThat(actionEnum(denied.spec())).doesNotContain("edit").contains("read");
        assertThat(denied.availableActions()).doesNotContain("edit");
        assertThatThrownBy(() -> denied.executeOutcome(args("edit").put("file_path", "x")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("edit");
    }

    /** Stored profiles and env lists predate the merge, so they name the actions the old way. */
    @Test
    void thePreMergeActionNamesDenyAndAllowTheSameThing() {
        assertThat(tool().denying(Set.of("edit")).availableActions())
                .isEqualTo(tool().denying(Set.of("code_workspace.edit")).availableActions());

        assertThat(tool().allowing(Set.of("read")).availableActions())
                .isEqualTo(tool().allowing(Set.of("code_workspace.read")).availableActions());
    }

    @Test
    void denyingEverythingLeavesNothingToPublish() {
        CodeWorkspaceTool emptied = tool().denying(new LinkedHashSet<>(CodeWorkspaceTool.ACTIONS));

        assertThat(emptied.hasActions()).isFalse();
    }

    /** A denylist naming tools that have nothing to do with this one must cost nothing to apply. */
    @Test
    void namesUnrelatedToTheWorkspaceChangeNothing() {
        CodeWorkspaceTool untouched = tool();

        assertThat(untouched.denying(Set.of("shell", "web_search", "update_project_api")))
                .isSameAs(untouched);
        assertThat(untouched.availableActions()).containsExactlyElementsOf(CodeWorkspaceTool.ACTIONS);
    }

    @Test
    void allowingKeepsOnlyTheNamedActionsAndTreatsTheBareNameAsEverything() {
        assertThat(tool().allowing(Set.of("code_workspace")).availableActions())
                .containsExactlyElementsOf(CodeWorkspaceTool.ACTIONS);
        assertThat(tool().allowing(Set.of("read", "tree")).availableActions())
                .containsExactly("read", "tree", "help");
        assertThat(tool().allowing(Set.of("nothing-like-this")).hasActions()).isFalse();
    }

    /**
     * {@code HARNESS_RISK_CONFIRM_TOOLS} matches on the tool name. After the merge the name it can
     * match is {@code code_workspace}, so an entry naming {@code edit} has to be honoured here or
     * an operator's confirmation requirement disappears without a word.
     */
    @Test
    void honoursConfirmationRequirementsInBothSpellings() {
        CodeWorkspaceTool legacy = tool("edit", "write");

        assertThat(legacy.requiresConfirmation(args("edit"))).isTrue();
        assertThat(legacy.requiresConfirmation(args("write"))).isTrue();
        assertThat(legacy.requiresConfirmation(args("read"))).isFalse();

        CodeWorkspaceTool modern = tool("code_workspace.read");
        assertThat(modern.requiresConfirmation(args("read"))).isTrue();
        assertThat(modern.requiresConfirmation(args("tree"))).isFalse();

        assertThat(tool().requiresConfirmation(args("edit"))).isFalse();
        assertThat(tool("code_workspace").requiresConfirmation(args("read"))).isTrue();
    }

    @Test
    void confirmationSummaryNamesTheActionAndItsTarget() {
        JsonNode arguments = args("edit")
                .put("file_path", "/srv/app/Main.java")
                .put("old_string", "a")
                .put("new_string", "b");

        assertThat(tool("edit").confirmationSummary(arguments))
                .contains("edit")
                .contains("/srv/app/Main.java");
    }

    @Test
    void supportsNamesTheActionsItOffersInEitherSpelling() {
        CodeWorkspaceTool denied = tool().denying(Set.of("edit"));

        assertThat(denied.supports("code_workspace.read")).isTrue();
        assertThat(denied.supports("read")).isTrue();
        assertThat(denied.supports("code_workspace.edit")).isFalse();
        assertThat(denied.supports("edit")).isFalse();
        assertThat(denied.supports("shell")).isFalse();
    }

    /** One capability tag is ToolSpec's hard limit, so the merged spec declares the stronger one. */
    @Test
    void thePublishedCapabilityFollowsWhetherWritingIsOffered() {
        assertThat(tool().spec().capability().name()).isEqualTo("MUTATION");
        assertThat(tool().denying(Set.of("edit", "write", "patch")).spec().capability().name())
                .isEqualTo("RETRIEVAL");
    }
}
