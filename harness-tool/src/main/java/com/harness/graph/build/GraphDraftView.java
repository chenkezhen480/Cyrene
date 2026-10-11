package com.harness.graph.build;

public record GraphDraftView(String rootDraftId, String draftId, String sourceDraftId,
                            String contentHash, String graphId, String schemaId,
                            String tenantId, String userId, String sessionId, String runId,
                            String traceId, String taskId, String requestId, String status,
                            int nodeCount, int relationCount, int deleteNodeCount,
                            int deleteRelationCount, String createdAt, String viewUrl,
                            GraphMutationCommitter.Failure failure) { }
