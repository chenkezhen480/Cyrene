package com.harness.server;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.harness.agent.graph.GraphSpaceAccessService;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.server.api.ApiError;
import com.harness.server.api.ApiErrorCode;
import com.harness.tool.knowledge.KnowledgeWikiService;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeWikiHandlerTest {
    @Test
    void malformedJsonReturnsExplicitBadRequest() {
        EnvConfig.init(Map.of(EnvKey.AUTH_MODE, "none"));
        var service = mock(KnowledgeWikiService.class);
        var context = mock(Context.class);
        when(context.queryParam("userId")).thenReturn("test-user");
        when(context.status(400)).thenReturn(context);
        when(context.json(any())).thenReturn(context);
        // Javalin's Kotlin mapper can propagate Jackson's checked exception.
        doAnswer(call -> { throw new JsonParseException((JsonParser) null, "invalid JSON"); })
                .when(context).bodyAsClass(KnowledgeWikiHandler.WikiUpdate.class);
        new KnowledgeWikiHandler(service, mock(GraphSpaceAccessService.class)).update(context);
        var result = ArgumentCaptor.forClass(Object.class);
        verify(context).json(result.capture());
        assertThat(result.getValue()).isInstanceOf(ApiError.class);
        assertThat(((ApiError) result.getValue()).code()).isEqualTo(ApiErrorCode.INVALID_REQUEST);
        verifyNoInteractions(service);
    }
    @Test
    void globalExportIgnoresUiFiltersAndReturnsOneMarkdownFile() {
        EnvConfig.init(Map.of(EnvKey.AUTH_MODE, "none"));
        var service = mock(KnowledgeWikiService.class);
        var context = mock(Context.class);
        when(context.queryParam("userId")).thenReturn("alice");
        when(service.exportMarkdown()).thenReturn("# LLM Wiki\n\nAll authorized cards");
        new KnowledgeWikiHandler(service, mock(GraphSpaceAccessService.class)).exportAll(context);
        verify(context).contentType("text/markdown; charset=utf-8");
        verify(context).header("Content-Disposition", "attachment; filename=\"llm-wiki.md\"");
        verify(context).result("# LLM Wiki\n\nAll authorized cards");
        verify(context, never()).queryParam("collection");
        verify(context, never()).queryParam("type");
    }

}
