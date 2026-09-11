package com.harness.tool.knowledge;

import com.harness.graph.model.GraphChangeSet;
import com.harness.graph.model.GraphMutationResult;

/** Creates the searchable Wiki entry for a concrete Neo4j graph space. */
@FunctionalInterface
public interface GraphSpaceWikiCompiler {
    String synchronize(GraphChangeSet changeSet, GraphMutationResult mutationResult);
}
