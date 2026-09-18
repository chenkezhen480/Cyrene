package com.harness.server;

import com.harness.tool.ToolRegistry;
import com.harness.tool.filesystem.CodeWorkspaceTool;
import com.harness.tool.filesystem.FileSystemAccessPolicy;
import com.harness.tool.filesystem.FileSystemWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolPermissionHandlerTest {

    @TempDir
    Path root;

    /**
     * The permission store keys on one name per tool, and a merged tool is managed as one entry like
     * any other. Expanding it into its actions would hand the page a vocabulary it cannot save back,
     * and would let a tenant be denied an action the runtime then has to explain.
     */
    @Test
    void managesAMergedToolAsOneEntryLikeAnyOtherTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(codeWorkspace());

        List<ToolPermissionHandler.ToolView> rows =
                new ToolPermissionHandler(ToolPermissionStub.empty().service(), registry,
                        new ApiRequestAuthenticator()).registeredTools();

        assertThat(rows).extracting(ToolPermissionHandler.ToolView::name)
                .containsExactly(CodeWorkspaceTool.TOOL_NAME);
        assertThat(rows).noneMatch(row -> row.name().startsWith(CodeWorkspaceTool.TOOL_NAME + "."));
        assertThat(rows.get(0).description()).isNotBlank();
    }

    @Test
    void listsEveryRegisteredToolOnce() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(codeWorkspace());
        registry.register(new com.harness.tool.discovery.ReadClassHierarchyTool(
                Path.of(".").toAbsolutePath()));

        List<ToolPermissionHandler.ToolView> rows =
                new ToolPermissionHandler(ToolPermissionStub.empty().service(), registry,
                        new ApiRequestAuthenticator()).registeredTools();

        assertThat(rows).extracting(ToolPermissionHandler.ToolView::name)
                .containsExactly(CodeWorkspaceTool.TOOL_NAME, "read_class_hierarchy");
    }

    private CodeWorkspaceTool codeWorkspace() {
        FileSystemAccessPolicy policy = FileSystemAccessPolicy.host(
                root.toString(), List.of(), FileSystemAccessPolicy.Settings.defaults());
        return CodeWorkspaceTool.of(
                policy, FileSystemWorkspace.host(policy.searchRoot()), List.of());
    }
}
