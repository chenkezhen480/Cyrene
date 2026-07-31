package com.harness.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.graph.build.CanonicalJsonGraphDataConverter;
import com.harness.graph.build.GraphBuildRequest;
import com.harness.graph.build.GraphBuildResult;
import com.harness.graph.build.GraphBuildService;
import com.harness.graph.build.GraphBuildSourceType;
import com.harness.graph.build.GraphDataConverterRegistry;
import com.harness.graph.model.GraphMutationResult;
import com.harness.graph.store.KnowledgeGraphStore;
import com.harness.server.api.ApiError;
import com.harness.server.api.ApiErrorCode;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
                              "properties": {"name": "小明"}
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
    void returnsBadRequestWhenNaturalLanguageConverterIsNotRegistered() {
        KnowledgeGraphStore graphStore = mock(KnowledgeGraphStore.class);
        GraphBuildService buildService = new GraphBuildService(
                graphStore, GraphDataConverterRegistry.withDefaults(objectMapper));
        GraphRequestAuthenticator authenticator = mock(GraphRequestAuthenticator.class);
        GraphBuildHandler handler = new GraphBuildHandler(buildService, authenticator);
        Context context = mock(Context.class);
        GraphBuildRequest request = new GraphBuildRequest(
                "request-2",
                "graph-1",
                "student-capability-v1",
                GraphBuildSourceType.NATURAL_LANGUAGE,
                "llm-extraction",
                objectMapper.getNodeFactory().textNode("小明正在练习表达需求")
        );
        when(context.bodyAsClass(GraphBuildRequest.class)).thenReturn(request);
        when(context.status(400)).thenReturn(context);
        when(context.json(any())).thenReturn(context);

        handler.build(context);

        verify(context).status(400);
        ArgumentCaptor<Object> responseCaptor = ArgumentCaptor.forClass(Object.class);
        verify(context).json(responseCaptor.capture());
        assertThat(responseCaptor.getValue()).isEqualTo(ApiError.of(
                ApiErrorCode.INVALID_REQUEST,
                "No graph data converter registered for natural-language/llm-extraction"));
    }
}
