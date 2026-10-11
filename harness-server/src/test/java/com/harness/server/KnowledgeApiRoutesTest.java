package com.harness.server;

import com.harness.agent.AgentOrchestrator;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.core.text.UnicodeAwareTextTokenEstimator;
import com.harness.input.document.DocumentConversionService;
import com.harness.input.document.DocumentSummarizer;
import com.harness.provider.EmbeddingModelProvider;
import com.harness.server.api.ApiError;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.security.InternalApiRouteRegistry;
import com.harness.tool.knowledge.authority.KnowledgeArtifactRepository;
import com.harness.tool.knowledge.authority.MysqlKnowledgeRepository;
import com.harness.tool.knowledge.index.KnowledgeVectorRuntime;
import com.harness.tool.rag.VectorStore;
import com.harness.trace.store.TraceStore;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.harness.core.security.ApiEndpointDescriptor.ResourcePolicy.GLOBAL_MANAGEMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KnowledgeApiRoutesTest {
    @TempDir Path uploadDir;

    @Test
    void disabledVectorsRegisterExplicitErrorsWithoutConstructingVectorServices() throws Exception {
        EnvConfig.init(Map.of(EnvKey.RAG_PROVIDER, "none"));
        var agent = mock(AgentOrchestrator.class);
        var artifacts = mock(KnowledgeArtifactRepository.class);
        when(agent.knowledgeRepository()).thenReturn(mock(MysqlKnowledgeRepository.class));
        when(agent.knowledgeVectorRuntime()).thenReturn(new KnowledgeVectorRuntime(mock(EmbeddingModelProvider.class)));
        var app = mock(Javalin.class);
        var handlers = captureHandlers(app);
        var registry = new InternalApiRouteRegistry();

        var wiki = KnowledgeApiRoutes.register(app, registry, agent, artifacts, mock(TraceStore.class), uploadDir);

        assertThat(wiki).isNotNull();
        assertThat(handlers).hasSize(7);
        for (var route : handlers.entrySet()) {
            var context = mock(Context.class);
            when(context.status(503)).thenReturn(context);
            route.getValue().handle(context);
            var response = ArgumentCaptor.forClass(Object.class);
            verify(context).json(response.capture());
            assertThat(response.getValue()).isInstanceOfSatisfying(ApiError.class, error -> {
                assertThat(error.code()).isEqualTo(ApiErrorCode.CAPABILITY_DISABLED);
                assertThat(error.message()).contains("HARNESS_RAG_PROVIDER=none");
            });
            String[] key = route.getKey().split(" ", 2);
            assertThat(registry.resolve(key[0], key[1]).resourcePolicy()).isEqualTo(GLOBAL_MANAGEMENT);
        }
        var gate = ArgumentCaptor.forClass(Handler.class);
        verify(app).before(eq("/api/knowledge/*"), gate.capture());
        var okfContext = mock(Context.class);
        gate.getValue().handle(okfContext);
        verifyNoInteractions(okfContext, artifacts);
        verify(agent, never()).embeddingModel();
        verify(agent, never()).documentConversionService();
        verify(agent, never()).documentSummarizer();
        assertThat(uploadDir.resolve("objects")).doesNotExist();
    }

    @Test
    void enabledVectorsRetainManagementHandlersAndReadinessGate() throws Exception {
        EnvConfig.init(Map.of(EnvKey.RAG_PROVIDER, "milvus"));
        var agent = mock(AgentOrchestrator.class);
        var vectors = mock(VectorStore.class);
        var embedding = mock(EmbeddingModelProvider.class);
        when(embedding.tokenEstimator()).thenReturn(UnicodeAwareTextTokenEstimator.INSTANCE);
        when(agent.vectorStore()).thenReturn(vectors);
        when(agent.embeddingModel()).thenReturn(embedding);
        when(agent.knowledgeRepository()).thenReturn(mock(MysqlKnowledgeRepository.class));
        when(agent.documentConversionService()).thenReturn(mock(DocumentConversionService.class));
        when(agent.documentSummarizer()).thenReturn(mock(DocumentSummarizer.class));
        when(agent.knowledgeVectorRuntime()).thenReturn(new KnowledgeVectorRuntime(embedding));
        var app = mock(Javalin.class);
        var handlers = captureHandlers(app);

        KnowledgeApiRoutes.register(app, new InternalApiRouteRegistry(), agent,
                mock(KnowledgeArtifactRepository.class), mock(TraceStore.class), uploadDir);

        var page = new PageResponse<>(List.of("manuals"), new PageInfo(50, "", false));
        when(vectors.listCollections(50, null)).thenReturn(page);
        var context = mock(Context.class);
        handlers.get("GET /api/knowledge").handle(context);
        verify(context).json(page);
        verify(vectors).listCollections(50, null);
        var gate = ArgumentCaptor.forClass(Handler.class);
        verify(app).before(eq("/api/knowledge/*"), gate.capture());
        var pendingContext = mock(Context.class);
        when(pendingContext.status(503)).thenReturn(pendingContext);
        gate.getValue().handle(pendingContext);
        var response = ArgumentCaptor.forClass(Object.class);
        verify(pendingContext).json(response.capture());
        assertThat(((ApiError) response.getValue()).code()).isEqualTo(ApiErrorCode.KNOWLEDGE_NOT_READY);
        verify(pendingContext).skipRemainingHandlers();
    }

    private static Map<String, Handler> captureHandlers(Javalin app) {
        Map<String, Handler> handlers = new LinkedHashMap<>();
        doAnswer(call -> {
            handlers.put(call.getArgument(0, HandlerType.class).name() + " " + call.getArgument(1, String.class),
                    call.getArgument(2, Handler.class));
            return app;
        }).when(app).addHttpHandler(any(HandlerType.class), anyString(), any(Handler.class));
        return handlers;
    }
}
