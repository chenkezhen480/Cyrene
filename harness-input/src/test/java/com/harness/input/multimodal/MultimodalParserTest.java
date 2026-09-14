package com.harness.input.multimodal;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.model.AgentMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultimodalParserTest {
    @BeforeEach
    void setUp() {
        EnvConfig.init(Map.of(EnvKey.MULTIMODAL_IMAGE_ENABLED, "true",
                EnvKey.MULTIMODAL_VIDEO_ENABLED, "false", EnvKey.MULTIMODAL_FILE_MAX_SIZE, "1"));
    }

    @Test
    void documentsOfAnyAcceptedSizeStayUnreadUntilTheFileToolRuns() {
        var parser = new MultimodalParser();
        var attachments = parser.parse(List.of(
                new MultimodalParser.RawAttachment("small.pdf", new byte[]{1}, "application/pdf", null),
                new MultimodalParser.RawAttachment("large.pdf", new byte[800_000], "application/pdf", null)));
        assertThat(attachments).hasSize(2).allSatisfy(parsed -> {
            assertThat(parsed.type()).isEqualTo(AgentMessage.Attachment.AttachmentType.FILE);
        });
        assertThat(attachments.getLast().data()).hasSize(800_000);
    }

    @Test
    void keepsImageBytesWithoutDocumentProcessing() {
        byte[] bytes = {1, 2, 3};
        var result = new MultimodalParser().parse(List.of(
                new MultimodalParser.RawAttachment("image.png", bytes, "image/png", null))).getFirst();
        assertThat(result.type()).isEqualTo(AgentMessage.Attachment.AttachmentType.IMAGE);
        assertThat(result.data()).containsExactly(bytes);
    }

    @Test
    void inputStagePreservesAttachmentsWithoutAddingTheirBodyToUserText() {
        var authenticator = org.mockito.Mockito.mock(com.harness.input.auth.Authenticator.class);
        org.mockito.Mockito.when(authenticator.authenticate(null)).thenReturn("user-1");
        var input = new com.harness.input.InputProcessor(authenticator, new MultimodalParser())
                .process(null, "Read the document", List.of(new MultimodalParser.RawAttachment(
                        "guide.md", "private source".getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/markdown", null)));
        assertThat(input.userId()).isEqualTo("user-1");
        assertThat(input.message().text()).isEqualTo("Read the document");
        assertThat(input.message().attachments()).hasSize(1);
    }

    @Test
    void rejectsEmptyOversizedAndDisabledVideoAttachments() {
        var parser = new MultimodalParser();
        assertThatThrownBy(() -> parser.parse(List.of(
                new MultimodalParser.RawAttachment("empty.txt", new byte[0], "text/plain", null))))
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> parser.parse(List.of(
                new MultimodalParser.RawAttachment("huge.txt", new byte[1024 * 1024 + 1], "text/plain", null))))
                .hasMessageContaining("exceeds max size");
        assertThatThrownBy(() -> parser.parse(List.of(
                new MultimodalParser.RawAttachment("video.mp4", new byte[]{1}, "video/mp4", null))))
                .hasMessageContaining("Video input is disabled");
    }
}
