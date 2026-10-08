package com.harness.server;

import com.harness.agent.graph.GraphSpaceAccessService;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.security.RequestPrincipal;
import com.harness.server.security.RequestPrincipalResolver;
import com.harness.tool.knowledge.okf.OkfBundleExporter;
import com.harness.tool.knowledge.okf.OkfBundleScope;
import com.harness.tool.knowledge.okf.OkfImportService;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

class KnowledgeOkfHandlerTest {
    @Test
    void globalExchangeAndGraphWritesRequireManagementScope() {
        EnvConfig.init(Map.of(EnvKey.AUTH_MODE, "none", EnvKey.INTERNAL_API_ADMIN_TENANT_ID, "management"));
        var exporter = mock(OkfBundleExporter.class);
        var importer = mock(OkfImportService.class);
        var handler = new KnowledgeOkfHandler(exporter, importer, mock(GraphSpaceAccessService.class), true);
        for (String kind : List.of("global_operation", "graph")) {
            var context = context("tenant-a", kind);
            handler.reviewImport(context);
            handler.commitImport(context);
            verify(context, times(2)).status(403);
        }
        var globalExport = context("tenant-a", "global_operation");
        handler.exportBundle(globalExport);
        verify(globalExport).status(403);
        verifyNoInteractions(importer, exporter);
        handler.exportBundle(context("tenant-a", "graph"));
        verify(exporter).export(OkfBundleScope.graph("tenant-a", "graph-1", "schema-1"));
        handler.commitImport(context("management", "global_operation"));
        verify(importer).importApproved(OkfBundleScope.globalOperation(), Map.of(), List.of());
    }

    private static Context context(String tenantId, String kind) {
        var context = mock(Context.class);
        when(context.attribute(RequestPrincipalResolver.PRINCIPAL_ATTRIBUTE)).thenReturn(new RequestPrincipal(
                "user-a", tenantId, "manager", RequestPrincipal.AuthenticationType.JWT));
        when(context.bodyAsClass(KnowledgeOkfHandler.ExchangeRequest.class)).thenReturn(
                new KnowledgeOkfHandler.ExchangeRequest(kind, null, null, null, "graph-1", "schema-1", Map.of(), List.of()));
        when(context.status(anyInt())).thenReturn(context);
        return context;
    }
}
