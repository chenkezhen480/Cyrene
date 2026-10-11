package com.harness.agent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.graph.build.*;
import com.harness.graph.model.GraphNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReadGraphDraftToolTest {
    final ObjectMapper mapper = new ObjectMapper();
    final GraphChangeDraftService service = mock(GraphChangeDraftService.class);
    final GraphDraftScope scope = new GraphDraftScope("tenant", "user", "session", "run", "trace", null, null);
    final ReadGraphDraftTool tool = new ReadGraphDraftTool(service, () -> scope, mapper);

    @Test void readsFixedHashEffectiveValuesAndExplicitDeletionMarkers() throws Exception {
        GraphNode node = new GraphNode("new", Set.of("Person"), Map.of("name", "latest"));
        when(service.readPreview(scope, "draft", "hash", 1, "")).thenReturn(new PageResponse<>(List.of(
                new GraphDraftPreviewItem("DRAFT_PREVIEW", "draft", "hash", "node:new", "NODE", "ADD", null, node)), new PageInfo(1, "cursor", true)));
        var output = tool.executeOutput(mapper.readTree("{\"draftId\":\"draft\",\"expectedContentHash\":\"hash\",\"limit\":1}")).json();
        assertThat(output.path("data").get(0).path("after").path("properties").path("name").asText()).isEqualTo("latest");
        assertThat(output.path("meta").path("viewType").asText()).isEqualTo("DRAFT_PREVIEW");
        assertThat(output.path("pageInfo").path("hasMore").asBoolean()).isTrue();
        verify(service, never()).apply(any(), anyString(), anyString());
    }

    @Test void staleReferencesSurfaceConflictAndHashIsRequired() throws Exception {
        when(service.readPreview(any(), anyString(), anyString(), anyInt(), anyString())).thenThrow(new IllegalStateException("Draft version is no longer current"));
        assertThatThrownBy(() -> tool.execute(mapper.readTree("{\"draftId\":\"draft\",\"expectedContentHash\":\"old\"}")))
                .isInstanceOf(ToolExecutionException.class).hasMessageContaining("no longer current");
        assertThatThrownBy(() -> tool.execute(mapper.readTree("{\"draftId\":\"draft\"}")))
                .isInstanceOf(ToolExecutionException.class).hasMessageContaining("expectedContentHash");
    }

    @Test void authorizedVersionRecoverySurvivesTheToolErrorBoundary() throws Exception {
        var conflict = new GraphDraftConflictException("Draft version is no longer current", "current-draft", "current-hash");
        when(service.readPreview(any(), anyString(), anyString(), anyInt(), anyString())).thenThrow(conflict);
        assertThatThrownBy(() -> tool.execute(mapper.readTree("{\"draftId\":\"draft\",\"expectedContentHash\":\"old\"}")))
                .isInstanceOf(ToolExecutionException.class).hasCause(conflict)
                .hasMessageContaining("currentDraftId=current-draft")
                .hasMessageContaining("currentContentHash=current-hash");
    }
}
