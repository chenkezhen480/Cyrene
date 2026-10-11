package com.harness.graph.build;

import com.harness.graph.model.GraphChangeSet;
import com.harness.graph.model.GraphMutationResult;

@FunctionalInterface
public interface GraphMutationCommitter {
    record Failure(String message, boolean graphCommitted) { }
    GraphMutationResult commit(GraphChangeSet changeSet);

    default java.util.Optional<GraphMutationResult> findCommitted(String requestId) {
        return java.util.Optional.empty();
    }

    default java.util.Optional<Failure> findFailure(String requestId) {
        return java.util.Optional.empty();
    }
}
