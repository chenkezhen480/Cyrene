package com.harness.server;

import com.harness.tool.knowledge.KnowledgeIngestPendingException;
import com.harness.tool.knowledge.KnowledgeIngestService;
import com.harness.trace.store.TraceStore;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeUploadHandlerTest {

    @Test
    void transientIngestFailureReturnsDurablePendingReceipt() {
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
        when(context.status(202)).thenReturn(context);
        when(context.json(any())).thenReturn(context);
        when(ingestService.ingest(
                any(byte[].class), anyString(), anyString(), anyString(), isNull(), isNull()))
                .thenThrow(new KnowledgeIngestPendingException(
                        "job-1", "document-1", "artifact-1", "manuals",
                        new IllegalStateException("parser unavailable")));

        new KnowledgeUploadHandler(ingestService, traceStore).handle(context);

        verify(context).status(202);
        ArgumentCaptor<Object> response = ArgumentCaptor.forClass(Object.class);
        verify(context).json(response.capture());
        assertThat(response.getValue()).isInstanceOf(Map.class);
        Map<?, ?> body = (Map<?, ?>) response.getValue();
        assertThat(body.get("status")).isEqualTo("pending");
        assertThat(body.get("jobId")).isEqualTo("job-1");
        assertThat(body.get("documentId")).isEqualTo("document-1");
        assertThat(body.get("sourceArtifactId")).isEqualTo("artifact-1");
        assertThat(body.get("collection")).isEqualTo("manuals");
    }
}
