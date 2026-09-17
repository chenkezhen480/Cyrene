package com.harness.agent.graph;

import com.harness.core.model.PageResponse;

/**
 * Lists and authorizes graph spaces for a trusted external scope.
 */
public interface GraphSpaceAccessService {

    PageResponse<GraphSpaceReference> listReadable(
            String tenantId,
            int limit,
            String cursor
    );

    void requireReadable(
            String tenantId,
            String graphId,
            String schemaId
    );

    int deleteBindings(String graphId, String schemaId);

    /**
     * Deletes every binding that grants access to any graph space built on one Schema.
     *
     * <p>Deleting a Schema must clean up bindings for spaces that hold no graph nodes, because
     * those spaces are never enumerated from the graph store. Like {@link #deleteBindings}, this is
     * not tenant scoped: the rows it removes describe spaces that no longer exist.</p>
     */
    int deleteBindingsBySchema(String schemaId);

    /**
     * Grants one tenant access to one Graph Space, if no binding for it exists yet.
     *
     * <p>An existing binding is left untouched: an operator who narrowed the permission or disabled
     * the row outranks an automatic grant. Without this row a space is invisible to every tenant once
     * the binding table exists, including to {@code query_graph}.</p>
     */
    void registerBinding(String tenantId, String graphId, String schemaId);
}
