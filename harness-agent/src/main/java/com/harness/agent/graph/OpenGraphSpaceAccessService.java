package com.harness.agent.graph;

import com.harness.core.model.PageResponse;
import com.harness.core.model.AgentContext;
import com.harness.graph.model.GraphSpacePageRequest;
import com.harness.graph.store.KnowledgeGraphStore;

import java.util.Objects;

/**
 * Standalone mode: every graph space in the configured graph store is readable.
 *
 * <p>Reached when the graph provider is {@code none}, when MySQL storage is disabled, or when the
 * database has not had the current {@code sql/schema-mysql.sql} applied and so has no binding table.
 * A database created from that schema always has the table, which means it runs in the enforced
 * {@link MysqlGraphSpaceAccessService} mode instead.</p>
 */
public final class OpenGraphSpaceAccessService implements GraphSpaceAccessService {

    private final KnowledgeGraphStore graphStore;

    public OpenGraphSpaceAccessService(KnowledgeGraphStore graphStore) {
        this.graphStore = Objects.requireNonNull(graphStore, "graphStore");
    }

    @Override
    public PageResponse<GraphSpaceReference> listReadable(
            String tenantId,
            int limit,
            String cursor
    ) {
        requireStandaloneTenant(tenantId);
        var page = graphStore.listGraphSpaces(new GraphSpacePageRequest(limit, cursor));
        return new PageResponse<>(
                page.items().stream()
                        .map(item -> new GraphSpaceReference(item.graphId(), item.schemaId()))
                        .toList(),
                page.pageInfo()
        );
    }

    @Override
    public void requireReadable(
            String tenantId,
            String graphId,
            String schemaId
    ) {
        requireStandaloneTenant(tenantId);
        // Standalone mode intentionally has no relational access boundary.
    }

    @Override
    public int deleteBindings(String graphId, String schemaId) {
        return 0;
    }

    @Override
    public int deleteBindingsBySchema(String schemaId) {
        return 0;
    }

    @Override
    public void registerBinding(String tenantId, String graphId, String schemaId) {
        // No binding table: every tenant already reads every graph space in the store.
    }

    /**
     * Rejects a caller that names a tenant other than the standalone default.
     *
     * <p>A missing tenant resolves to {@link AgentContext#DEFAULT_TENANT_ID}, the same way
     * {@code AgentContext.tenantId()} resolves it everywhere else — an unscoped standalone caller
     * is the default tenant, not a separate case. Only a caller that actually identifies as some
     * other tenant needs the binding table to be told what it may read.</p>
     */
    private static void requireStandaloneTenant(String tenantId) {
        if (!AgentContext.DEFAULT_TENANT_ID.equals(normalizeTenant(tenantId))) {
            throw new GraphSpaceAccessException(
                    "The graph-space binding table is required for tenant " + tenantId
            );
        }
    }

    /** Blank and absent both mean the standalone default, exactly as AgentContext does it. */
    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank()
                ? AgentContext.DEFAULT_TENANT_ID
                : tenantId.trim();
    }
}
