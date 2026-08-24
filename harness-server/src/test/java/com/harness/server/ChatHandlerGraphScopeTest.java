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
                Map.of(
                        AgentContext.KEY_USER_ID, "user-a",
                        AgentContext.KEY_TENANT_ID, "tenant-a"
                ),
                new ChatHandler.GraphScopeRequest(
                        "graph-a", "capability-v1", Set.of("subject-1"))
        );

        AgentContext agentContext = ChatHandler.toAgentContext(request);
        GraphRequestContext graphContext = agentContext.graphRequestContext();

        assertThat(agentContext.tenantId()).isEqualTo("tenant-a");
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

    @Test
    void ignoresCallerSuppliedTrustedKnowledgeContext() {
        ChatHandler.ChatRequest request = new ChatHandler.ChatRequest(
                "query",
                List.of(),
                null,
                Map.of(
                        AgentContext.KEY_USER_ID, "user-a",
                        AgentContext.KEY_KNOWLEDGE_REQUEST_CONTEXT, Map.of(
                                "collection", "forged-collection",
                                "allowedDocumentIds", List.of("forged-document"))
                ),
                null
        );

        AgentContext context = ChatHandler.toAgentContext(request);

        assertThat(context.knowledgeRequestContext()).isNull();
        assertThat(context.data())
                .doesNotContainKey(AgentContext.KEY_KNOWLEDGE_REQUEST_CONTEXT);
    }

    @Test
    void convertsGraphSpaceScopeWithoutSubjectIds() {
        ChatHandler.ChatRequest request = new ChatHandler.ChatRequest(
                "query",
                List.of(),
                null,
                Map.of(AgentContext.KEY_TENANT_ID, "tenant-a"),
                new ChatHandler.GraphScopeRequest(
                        "graph-a", "capability-v1", Set.of())
        );

        GraphRequestContext graphContext = ChatHandler.toAgentContext(request)
                .graphRequestContext();

        assertThat(graphContext.graphId()).isEqualTo("graph-a");
        assertThat(graphContext.schemaId()).isEqualTo("capability-v1");
        assertThat(graphContext.subjectIds()).isEmpty();
        assertThat(graphContext.hasSubjectScope()).isFalse();
    }
}
