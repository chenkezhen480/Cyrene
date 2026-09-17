package com.harness.tool.knowledge;

import com.harness.graph.model.GraphChangeSet;
import com.harness.graph.model.GraphMutationResult;

/** Creates the searchable Wiki entry for a concrete Neo4j graph space. */
public interface GraphSpaceWikiCompiler {

    String synchronize(GraphChangeSet changeSet, GraphMutationResult mutationResult);

    /**
     * Marks the space's Wiki entry discontinued, because the Graph Space no longer exists.
     *
     * <p>A Graph Space card follows its space lifecycle; it is never deleted on its own.</p>
     */
    void deprecate(String graphId, String schemaId);
}
