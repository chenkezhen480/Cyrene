package com.harness.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.graph.build.CanonicalJsonGraphDataConverter;
import com.harness.graph.build.GraphBuildPreviewResult;
import com.harness.graph.build.GraphBuildRequest;
import com.harness.graph.build.GraphBuildResult;
import com.harness.graph.build.GraphBuildService;
import com.harness.graph.build.GraphBuildSourceType;
import com.harness.graph.build.GraphDataConverter;
import com.harness.graph.build.GraphDataConverterRegistry;
import com.harness.graph.build.GraphMutationDraft;
import com.harness.graph.model.GraphMutationResult;
import com.harness.graph.model.GraphNode;
import com.harness.graph.store.KnowledgeGraphStore;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphBuildHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void authenticatesAndBuildsCanonicalJson() throws Exception {
        KnowledgeGraphStore graphStore = mock(KnowledgeGraphStore.class);
        when(graphStore.upsertBatch(any())).thenReturn(
                new GraphMutationResult("request-1", true, 1, 0));
        GraphBuildService buildService = new GraphBuildService(
                graphStore, GraphDataConverterRegistry.withDefaults(objectMapper));
        GraphRequestAuthenticator authenticator = mock(GraphRequestAuthenticator.class);
        GraphBuildHandler handler = new GraphBuildHandler(buildService, authenticator);
        Context context = mock(Context.class);
        GraphBuildRequest request = new GraphBuildRequest(
                "request-1",
                "graph-1",
                "student-capability-v1",
                GraphBuildSourceType.STRUCTURED,
                CanonicalJsonGraphDataConverter.CONVERTER_ID,
                objectMapper.readTree("""
                        {
                          "nodes": [
                            {
                              "nodeId": "student-1",
                              "labels": ["Student"],
                              "properties": {"name": "Xiaoming"}
                            }
                          ]
                        }
                        """)
        );
        when(context.bodyAsClass(GraphBuildRequest.class)).thenReturn(request);
        when(context.json(any())).thenReturn(context);

        handler.build(context);

        verify(authenticator).authenticate(context);
        ArgumentCaptor<Object> responseCaptor = ArgumentCaptor.forClass(Object.class);
        verify(context).json(responseCaptor.capture());
        assertThat(responseCaptor.getValue()).isInstanceOf(GraphBuildResult.class);
        assertThat(((GraphBuildResult) responseCaptor.getValue()).committed()).isTrue();
    }

    @Test
    void authenticatesAndPreviewsNaturalLanguageWithoutWriting() {
        KnowledgeGraphStore graphStore = mock(KnowledgeGraphStore.class);
        GraphDataConverter converter = mock(GraphDataConverter.class);
        when(converter.sourceType()).thenReturn(GraphBuildSourceType.NATURAL_LANGUAGE);
        when(converter.converterId()).thenReturn("llm-schema");
        when(converter.convert(any())).thenReturn(new GraphMutationDraft(
                List.of(new GraphNode(
                        "student-1", Set.of("Student"), Map.of("name", "Xiaoming"))),
                List.of()
        ));
        GraphBuildService buildService = new GraphBuildService(
                graphStore, new GraphDataConverterRegistry(List.of(converter)));
        GraphRequestAuthenticator authenticator = mock(GraphRequestAuthenticator.class);
        GraphBuildHandler handler = new GraphBuildHandler(buildService, authenticator);
        Context context = mock(Context.class);
        GraphBuildRequest request = new GraphBuildRequest(
                "request-2",
                "graph-1",
                "student-capability-v1",
                GraphBuildSourceType.NATURAL_LANGUAGE,
                "llm-schema",
                objectMapper.getNodeFactory().textNode("Student Xiaoming")
        );
        when(context.bodyAsClass(GraphBuildRequest.class)).thenReturn(request);
        when(context.json(any())).thenReturn(context);

        handler.preview(context);

        verify(authenticator).authenticate(context);
        ArgumentCaptor<Object> responseCaptor = ArgumentCaptor.forClass(Object.class);
        verify(context).json(responseCaptor.capture());
        assertThat(responseCaptor.getValue()).isInstanceOf(GraphBuildPreviewResult.class);
        assertThat(((GraphBuildPreviewResult) responseCaptor.getValue()).nodes())
                .extracting(GraphNode::nodeId)
                .containsExactly("student-1");
    }
}
