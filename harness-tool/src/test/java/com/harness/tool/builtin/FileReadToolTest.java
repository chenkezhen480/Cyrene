package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.model.Artifact;
import com.harness.core.model.ArtifactStore;
import com.harness.core.text.UnicodeAwareTextTokenEstimator;
import com.harness.input.document.DocumentConversionDiagnostics;
import com.harness.input.document.DocumentConversionResult;
import com.harness.input.document.DocumentConversionService;
import com.harness.input.document.DocumentSummarizer;
import com.harness.provider.ChatModelProvider;
import com.harness.tool.artifact.ArtifactStorageService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FileReadToolTest {
    @TempDir Path directory;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void readsWithANewModelRequestAndReturnsOnlyABoundedResult() throws Exception {
        EnvConfig.init(Map.of(EnvKey.LARGE_FILE_CONTEXT_RATIO, "0.6"));
        ArtifactStore store = mock(ArtifactStore.class);
        Artifact source = new ArtifactStorageService(store, directory, 10)
                .store(new byte[]{1, 2}, "report.pdf", "application/pdf", "session-1");
        when(store.get(source.id())).thenReturn(Optional.of(source));
        DocumentConversionService converter = mock(DocumentConversionService.class);
        when(converter.convert(any(byte[].class), eq("report.pdf"), eq("application/pdf")))
                .thenReturn(new DocumentConversionResult("# Source\n\nprivate document body", "Source", "application/pdf",
                        new DocumentConversionDiagnostics("markitdown", null, false, java.util.List.of(), "none", 0, 0, 2)));
        AtomicReference<ChatRequest> request = new AtomicReference<>();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest value) {
                request.set(value);
                return ChatResponse.builder().aiMessage(AiMessage.from("The document covers student organization.")).build();
            }
        };
        ChatModelProvider provider = new ChatModelProvider() {
            @Override public ChatModel chatModel() { return model; }
            @Override public String providerName() { return "test"; }
            @Override public String modelName() { return "primary-model"; }
            @Override public int contextWindow() { return 100_000; }
        };
        FileReadTool template = new FileReadTool(store, converter,
                new DocumentSummarizer(() -> provider, UnicodeAwareTextTokenEstimator.INSTANCE), null);
        var args = MAPPER.createObjectNode().put("file", source.downloadUrl()).put("task", "What subjects does this cover?");
        var result = MAPPER.readTree(template.forSession("session-1").execute(args));
        assertThat(result.path("data").path("summary").asText()).contains("student organization");
        assertThat(result.path("meta").path("summaryCalls").asInt()).isEqualTo(1);
        assertThat(result.toString()).doesNotContain("private document body");
        assertThat(request.get().messages()).hasSize(2);
        assertThat(((UserMessage) request.get().messages().getLast()).singleText()).contains("private document body");
        assertThatThrownBy(() -> template.forSession("another-session").execute(args))
                .hasMessageContaining("another session");
        assertThatThrownBy(() -> template.execute(args)).hasMessageContaining("authorized session");
        verify(converter, times(1)).convert(any(byte[].class), any(), any());
    }

    @Test
    void storingSourceRollsBackItsFileWhenMetadataPersistenceFails() throws Exception {
        ArtifactStore store = mock(ArtifactStore.class);
        doThrow(new IllegalStateException("metadata unavailable")).when(store).save(any());
        assertThatThrownBy(() -> new ArtifactStorageService(store, directory, 10)
                .store(new byte[]{1}, "document.md", "text/markdown", "session-1"))
                .hasMessageContaining("Failed to store artifact");
        try (var files = java.nio.file.Files.list(directory)) {
            assertThat(files.toList()).isEmpty();
        }
        verify(store).delete(anyString());
    }

    @Test
    void rejectsInventedPathsUnknownArgumentsAndUnsafeStorageNames() {
        var store = mock(ArtifactStore.class);
        var converter = mock(DocumentConversionService.class);
        var summarizer = mock(DocumentSummarizer.class);
        var tool = new FileReadTool(store, converter, summarizer, "session-1");
        assertThatThrownBy(() -> tool.execute(MAPPER.createObjectNode().put("file", "../../secret.txt")))
                .hasMessageContaining("exact document artifact reference");
        assertThatThrownBy(() -> tool.execute(MAPPER.createObjectNode().put("file", "/api/artifacts/example").put("path", "secret")))
                .hasMessageContaining("Only file and task");
        assertThatThrownBy(() -> new ArtifactStorageService(store, directory, 10)
                .store(new byte[]{1}, "../secret.txt", "text/plain", "session-1"))
                .hasMessageContaining("single file name");
        verifyNoInteractions(store, converter, summarizer);
    }
}
