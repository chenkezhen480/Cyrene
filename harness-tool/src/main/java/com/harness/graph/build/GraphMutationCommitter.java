package com.harness.graph.build;

import com.harness.graph.model.GraphChangeSet;
import com.harness.graph.model.GraphMutationResult;

@FunctionalInterface
public interface GraphMutationCommitter {
    GraphMutationResult commit(GraphChangeSet changeSet);
}
