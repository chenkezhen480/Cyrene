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

    @Test
    void exposesGroupAndActionPermissionsUsingTheSameSaveVocabulary() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(codeWorkspace());

        List<ToolPermissionHandler.ToolView> rows =
                new ToolPermissionHandler(ToolPermissionStub.empty().service(), registry,
                        new ApiRequestAuthenticator()).registeredTools();

        assertThat(rows).extracting(ToolPermissionHandler.ToolView::name)
                .containsExactly("code_workspace", "code_workspace.read", "code_workspace.glob",
                        "code_workspace.grep", "code_workspace.tree", "code_workspace.edit",
                        "code_workspace.write", "code_workspace.patch");
        assertThat(rows).noneMatch(row -> row.name().endsWith(".help"));
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
                .contains("code_workspace", "code_workspace.read", "read_class_hierarchy")
                .doesNotHaveDuplicates();
    }

    private CodeWorkspaceTool codeWorkspace() {
        FileSystemAccessPolicy policy = FileSystemAccessPolicy.host(
                root.toString(), List.of(), FileSystemAccessPolicy.Settings.defaults());
        return CodeWorkspaceTool.of(
                policy, FileSystemWorkspace.host(policy.searchRoot()), List.of());
    }
}
