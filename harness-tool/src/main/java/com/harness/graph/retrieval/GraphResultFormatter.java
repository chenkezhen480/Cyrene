package com.harness.graph.retrieval;

import com.harness.graph.model.GraphRouteResult;

public interface GraphResultFormatter {

    String schemaId();

    String format(GraphRouteResult result);
}
