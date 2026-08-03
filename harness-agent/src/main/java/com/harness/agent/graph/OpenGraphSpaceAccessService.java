package com.harness.agent.graph;

import com.harness.core.model.PageResponse;
import com.harness.core.model.AgentContext;
import com.harness.graph.model.GraphSpacePageRequest;
import com.harness.graph.store.KnowledgeGraphStore;

import java.util.Objects;

/**
 * Default standalone mode: every graph space in the configured graph store is readable.
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

    private static void requireStandaloneTenant(String tenantId) {
        if (!AgentContext.DEFAULT_TENANT_ID.equals(tenantId)) {
            throw new GraphSpaceAccessException(
                    "The optional graph-space binding table is required for tenant " + tenantId
            );
        }
    }

}
