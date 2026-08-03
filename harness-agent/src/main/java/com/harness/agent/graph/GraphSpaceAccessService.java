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
}
