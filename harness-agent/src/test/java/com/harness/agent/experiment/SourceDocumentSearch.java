package com.harness.agent.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ToolExecutionOutcome;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;

final class SourceDocumentSearch implements Tool {
    private final Tool delegate;
    SourceDocumentSearch(Tool delegate) { this.delegate = delegate; }
    @Override public ToolSpec spec() { return delegate.spec(); }
    @Override public String execute(JsonNode arguments) { return executeOutcome(arguments).content().modelContent(); }
    @Override public ToolExecutionOutcome executeOutcome(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) throw new ToolExecutionException("knowledge_search", "Search arguments must be an object");
        if (arguments.path("recent").asBoolean()) throw new ToolExecutionException("knowledge_search", "The experiment permits source documents only");
        ObjectNode scoped = arguments.deepCopy();
        scoped.putArray("knowledgeTypes").add("SOURCE_DOCUMENT");
        return delegate.executeOutcome(scoped);
    }
}
