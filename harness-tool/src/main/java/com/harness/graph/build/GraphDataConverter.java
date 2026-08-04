package com.harness.graph.build;

/**
 * Converts one explicit source representation into provider-neutral graph data.
 * Implementations must not persist data; validation and transactional storage
 * are owned by {@link GraphBuildService}.
 */
public interface GraphDataConverter {

    String converterId();

    GraphBuildSourceType sourceType();

    GraphMutationDraft convert(GraphBuildRequest request);
}
