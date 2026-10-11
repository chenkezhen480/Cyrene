package com.harness.agent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.ToolExecutionException;
import com.harness.graph.build.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PrepareGraphChangesToolTest {
    final ObjectMapper mapper = new ObjectMapper();
    final GraphChangeDraftService service = mock(GraphChangeDraftService.class);
    final GraphDraftScope scope = new GraphDraftScope("tenant", "user", "session", "run", "trace", null, null);
    final PrepareGraphChangesTool tool = new PrepareGraphChangesTool(service, () -> scope, mapper);

    @Test void ordinaryChatCanProposeSelectedTargetWithTrustedScopeAndReviewLink() throws Exception {
        GraphDraftView view = new GraphDraftView("root", "draft", null, "hash", "graph", "schema", "tenant", "user", "session", "run", "trace", null,
                "request", "PENDING", 1, 0, 0, 0, "now", "/api/graph/change-drafts/draft", null);
        when(service.prepare(eq(scope), any())).thenReturn(view);
        var output = tool.executeOutput(mapper.readTree("""
                {"graphId":"graph","schemaId":"schema","nodes":[{"nodeId":"new","labels":["Person"],"properties":{"name":"new"}}]}
                """)).json();
        assertThat(output.path("data").path("draftId").asText()).isEqualTo("draft");
        assertThat(output.path("data").path("status").asText()).isEqualTo("PENDING");
        assertThat(output.path("meta").path("requiresHumanConfirmation").asBoolean()).isTrue();
        verify(service).prepare(eq(scope), argThat(request -> request.graphId().equals("graph")));
        verify(service, never()).apply(any(), anyString(), anyString());
    }

    @Test void ownershipInjectionAndUnauthorizedTargetsAreExplicitErrors() throws Exception {
        assertThatThrownBy(() -> tool.execute(mapper.readTree("{\"userId\":\"other\"}")))
                .isInstanceOf(ToolExecutionException.class).hasMessageContaining("Unsupported");
        when(service.prepare(any(), any())).thenThrow(new SecurityException("trusted scope cannot widen"));
        assertThatThrownBy(() -> tool.execute(mapper.readTree("{\"graphId\":\"other\",\"schemaId\":\"schema\"}")))
                .isInstanceOf(ToolExecutionException.class).hasMessageContaining("cannot widen");
        verify(service, never()).apply(any(), anyString(), anyString());
    }

    @Test void staleEditReturnsTheAuthorizedCurrentReferenceInTheToolError() throws Exception {
        when(service.prepare(any(), any())).thenThrow(
                new GraphDraftConflictException("Draft version is no longer current", "current-draft", "current-hash"));
        assertThatThrownBy(() -> tool.execute(mapper.readTree("{\"sourceDraftId\":\"old-draft\",\"expectedSourceContentHash\":\"old-hash\"}")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("currentDraftId=current-draft")
                .hasMessageContaining("currentContentHash=current-hash");
        verify(service, never()).apply(any(), anyString(), anyString());
    }
}
