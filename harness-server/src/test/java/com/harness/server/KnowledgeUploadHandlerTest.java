package com.harness.server;

import com.harness.input.document.DocumentConversionException;
import com.harness.server.api.ApiError;
import com.harness.tool.knowledge.KnowledgeIngestService;
import com.harness.trace.store.TraceStore;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeUploadHandlerTest {

    @Test
    void parserFailureReturnsExplicitErrorWithoutPendingReceipt() {
        KnowledgeIngestService ingestService = mock(KnowledgeIngestService.class);
        TraceStore traceStore = mock(TraceStore.class);
        Context context = mock(Context.class);
        UploadedFile uploadedFile = mock(UploadedFile.class);
        byte[] content = "# retry later".getBytes(StandardCharsets.UTF_8);

        when(context.uploadedFile("file")).thenReturn(uploadedFile);
        when(context.formParam("collection")).thenReturn("manuals");
        when(context.formParam("documentId")).thenReturn(null);
        when(uploadedFile.content()).thenReturn(new ByteArrayInputStream(content));
        when(uploadedFile.filename()).thenReturn("manual.md");
        when(uploadedFile.contentType()).thenReturn("text/markdown");
        when(context.status(503)).thenReturn(context);
        when(context.json(any())).thenReturn(context);
        when(ingestService.ingest(
                any(byte[].class), anyString(), anyString(), anyString(), isNull(), isNull()))
                .thenThrow(new DocumentConversionException("parser unavailable"));

        new KnowledgeUploadHandler(ingestService, traceStore).handle(context);

        verify(context).status(503);
        ArgumentCaptor<Object> response = ArgumentCaptor.forClass(Object.class);
        verify(context).json(response.capture());
        assertThat(response.getValue()).isInstanceOf(ApiError.class);
        assertThat(((ApiError) response.getValue()).message()).isEqualTo("parser unavailable");
    }
}
