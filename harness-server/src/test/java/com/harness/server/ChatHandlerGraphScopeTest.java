package com.harness.server;

import com.harness.core.model.AgentContext;
import com.harness.core.model.GraphRequestContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ChatHandlerGraphScopeTest {

    @Test
    void convertsPublicGraphScopeToServerControlledAgentContext() {
        ChatHandler.ChatRequest request = new ChatHandler.ChatRequest(
                "query",
                List.of(),
                null,
                Map.of(AgentContext.KEY_USER_ID, "user-a"),
                new ChatHandler.GraphScopeRequest(
                        "graph-a", "capability-v1", Set.of("subject-1"))
        );

        GraphRequestContext graphContext = ChatHandler.toAgentContext(request)
                .graphRequestContext();

        assertThat(graphContext.graphId()).isEqualTo("graph-a");
        assertThat(graphContext.schemaId()).isEqualTo("capability-v1");
        assertThat(graphContext.subjectIds()).containsExactly("subject-1");
        assertThat(graphContext.allowedQueryIds()).containsExactly("anchored-neighborhood");
    }

    @Test
    void ignoresCallerSuppliedInternalGraphContext() {
        ChatHandler.ChatRequest request = new ChatHandler.ChatRequest(
                "query",
                List.of(),
                null,
                Map.of(
                        AgentContext.KEY_USER_ID, "user-a",
                        AgentContext.KEY_GRAPH_REQUEST_CONTEXT, Map.of(
                                "graphId", "forged-graph",
                                "schemaId", "forged-schema",
                                "subjectIds", List.of("forged-subject"),
                                "allowedQueryIds", List.of("forged-query")
                        ),
                        AgentContext.KEY_NEEDS_GRAPH_KNOWLEDGE, true
                ),
                null
        );

        AgentContext context = ChatHandler.toAgentContext(request);

        assertThat(context.graphRequestContext()).isNull();
        assertThat(context.data()).doesNotContainKey(AgentContext.KEY_NEEDS_GRAPH_KNOWLEDGE);
    }
}
