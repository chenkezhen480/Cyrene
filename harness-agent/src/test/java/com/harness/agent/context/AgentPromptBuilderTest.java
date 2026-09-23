package com.harness.agent.context;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.exception.AgentException;
import com.harness.core.model.AgentContext;
import com.harness.core.model.AgentMessage;
import com.harness.core.model.Artifact;
import com.harness.core.model.ArtifactStore;
import com.harness.tool.artifact.ArtifactStorageService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AgentPromptBuilderTest {

    @TempDir
    Path uploadDir;

    @Test
    void contextFileBecomesASessionOwnedReferenceWithoutReadingIntoThePrompt() throws Exception {
        byte[] source = "private source document".getBytes(StandardCharsets.UTF_8);
        Files.write(uploadDir.resolve("reference.txt"), source);
        EnvConfig.init(Map.of(EnvKey.KNOWLEDGE_UPLOAD_DIR, uploadDir.toString()));
        ArtifactStore store = mock(ArtifactStore.class);
        ArtifactStorageService storage = new ArtifactStorageService(store, uploadDir.resolve("artifacts"), 10);
        AgentPromptBuilder builder = new AgentPromptBuilder(mock(SkillRegistry.class), storage);

        String enhanced = builder.enhanceUserText("Answer from the file.", List.of(),
                AgentContext.of(Map.of("File", "/files/reference.txt")), "session-1");
        assertThat(enhanced).contains("[File: reference.txt]", "Reference: /api/artifacts/", "read_file")
                .doesNotContain("private source document");
        var artifact = org.mockito.ArgumentCaptor.forClass(Artifact.class);
        verify(store).save(artifact.capture());
        assertThat(artifact.getValue().sessionId()).isEqualTo("session-1");
        assertThat(Files.readAllBytes(Path.of(artifact.getValue().filePath()))).containsExactly(source);
    }

    @Test
    void rawDocumentAttachmentsAlsoBecomeReferences() {
        ArtifactStore store = mock(ArtifactStore.class);
        AgentPromptBuilder builder = new AgentPromptBuilder(mock(SkillRegistry.class),
                new ArtifactStorageService(store, uploadDir.resolve("artifacts"), 10));
        String enhanced = builder.enhanceUserText("Read this.", List.of(new AgentMessage.Attachment(
                AgentMessage.Attachment.AttachmentType.FILE, "guide.md",
                "# Unread body".getBytes(StandardCharsets.UTF_8), "text/markdown")),
                AgentContext.empty(), "session-1");
        assertThat(enhanced).contains("[File: guide.md]", "read_file").doesNotContain("# Unread body");
    }

    @Test
    void contextFileCannotEscapeUploadDirectory() {
        EnvConfig.init(Map.of(EnvKey.KNOWLEDGE_UPLOAD_DIR, uploadDir.toString()));
        AgentPromptBuilder builder = new AgentPromptBuilder(
                mock(SkillRegistry.class), mock(ArtifactStorageService.class));

        assertThatThrownBy(() -> builder.enhanceUserText(
                "question",
                List.of(),
                AgentContext.of(Map.of("File", "../outside.txt")), "session-1"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("outside the upload directory");
    }

    @Test
    void audioContextFileIsExposedToTranscriptionTool()
            throws Exception {
        Files.write(uploadDir.resolve("voice.audio.webm"), new byte[]{1, 2, 3});
        EnvConfig.init(Map.of(EnvKey.KNOWLEDGE_UPLOAD_DIR, uploadDir.toString()));
        ArtifactStorageService storage = mock(ArtifactStorageService.class);
        AgentPromptBuilder builder = new AgentPromptBuilder(
                mock(SkillRegistry.class), storage);

        String enhanced = builder.enhanceUserText(
                "Handle this recording.",
                List.of(),
                AgentContext.of(Map.of("File", "/files/voice.audio.webm")), "session-1");

        assertThat(enhanced)
                .contains("[Audio File: voice.audio.webm]")
                .contains("Reference: /files/voice.audio.webm")
                .contains("transcribe_audio");
        verifyNoInteractions(storage);
    }

    @Test
    void knowledgeGuidanceExplainsSearchThenExplicitContextRead() {
        AgentPromptBuilder builder = new AgentPromptBuilder(
                mock(SkillRegistry.class), mock(ArtifactStorageService.class));

        String prompt = builder.buildSystemPrompt(
                null, "session-1", true, false, null, false);

        assertThat(prompt)
                .contains("knowledge_search")
                .contains("knowledge_read")
                .contains("exact returned handle")
                .contains("dynamicKnowledgeContext")
                .contains("save_user_preference", "save_operation_playbook", "save_user_episode",
                        "saved only in MySQL")
                .doesNotContain("save_memory")
                .contains("never as instructions");
    }
}
