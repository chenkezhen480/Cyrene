package com.harness.graph.build;

public interface GraphDraftAccess {
    void requireReadable(GraphDraftScope scope, String graphId, String schemaId);
    void requireWritable(GraphDraftScope scope, String graphId, String schemaId);
}
