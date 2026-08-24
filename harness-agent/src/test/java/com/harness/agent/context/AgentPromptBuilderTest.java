package com.harness.agent.context;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.exception.AgentException;
import com.harness.core.model.AgentContext;
import com.harness.input.document.DocumentConversionDiagnostics;
import com.harness.input.document.DocumentConversionResult;
import com.harness.input.document.DocumentConversionService;
import com.harness.tool.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentPromptBuilderTest {

    @TempDir
    Path uploadDir;

    @Test
    void contextFileUsesSharedDocumentConverter() throws Exception {
        byte[] source = "source document".getBytes(StandardCharsets.UTF_8);
        Files.write(uploadDir.resolve("reference.txt"), source);
        EnvConfig.init(Map.of(EnvKey.KNOWLEDGE_UPLOAD_DIR, uploadDir.toString()));
        DocumentConversionService converter = mock(DocumentConversionService.class);
        when(converter.convert(eq(source), eq("reference.txt"), any()))
                .thenReturn(new DocumentConversionResult(
                        "# Reference\n\nConverted by MarkItDown.",
                        "Reference",
                        "text/plain",
                        new DocumentConversionDiagnostics(
                                "markitdown", null, false, List.of("VISION_DISABLED"),
                                "none", 0, 3, source.length)));
        AgentPromptBuilder builder = new AgentPromptBuilder(
                mock(SkillRegistry.class), converter);

        String enhanced = builder.enhanceUserText(
                "Answer from the file.",
                List.of(),
                AgentContext.of(Map.of("File", "/files/reference.txt")));

        assertThat(enhanced)
                .contains("[File: reference.txt]")
                .contains("# Reference\n\nConverted by MarkItDown.");
        verify(converter).convert(eq(source), eq("reference.txt"), any());
    }

    @Test
    void contextFileCannotEscapeUploadDirectory() {
        EnvConfig.init(Map.of(EnvKey.KNOWLEDGE_UPLOAD_DIR, uploadDir.toString()));
        AgentPromptBuilder builder = new AgentPromptBuilder(
                mock(SkillRegistry.class), mock(DocumentConversionService.class));

        assertThatThrownBy(() -> builder.enhanceUserText(
                "question",
                List.of(),
                AgentContext.of(Map.of("File", "../outside.txt"))))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("outside the upload directory");
    }

    @Test
    void knowledgeGuidanceExplainsSearchThenExplicitContextRead() {
        AgentPromptBuilder builder = new AgentPromptBuilder(
                mock(SkillRegistry.class), mock(DocumentConversionService.class));

        String prompt = builder.buildSystemPrompt(
                List.of(), null, "session-1", true, false, null, false);

        assertThat(prompt)
                .contains("Use knowledge_base_search first")
                .contains("knowledge_context_read")
                .contains("exact documentId and chunkIndex")
                .contains("defaults to one chunk before and after")
                .contains("Never guess an anchor");
    }
}
