package com.harness.graph.retrieval;

import com.harness.core.model.GraphRequestContext;
import com.harness.graph.model.GraphRouteResult;

import java.util.Set;

/**
 * Independent graph retrieval route. Implementations must not use vector search or reranking.
 */
public interface GraphKnowledgeRetriever {

    GraphRouteResult retrieve(
            GraphRequestContext requestContext,
            String queryId,
            Set<String> relationTypes,
            int maxDepth,
            int limit
    );
}
