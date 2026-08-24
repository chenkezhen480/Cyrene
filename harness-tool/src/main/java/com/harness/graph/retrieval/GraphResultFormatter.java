package com.harness.graph.retrieval;

import com.harness.graph.model.GraphRouteResult;
import com.harness.tool.protocol.ToolEnvelope;

public interface GraphResultFormatter {

    String schemaId();

    ToolEnvelope<GraphToolData> format(String graphId, GraphRouteResult result);
}
