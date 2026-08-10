package com.harness.input.multimodal;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.model.AgentMessage;
import com.harness.core.model.ParsedContent;
import com.harness.input.document.DocumentConversionDiagnostics;
import com.harness.input.document.DocumentConversionException;
import com.harness.input.document.DocumentConversionRequest;
import com.harness.input.document.DocumentConversionResult;
import com.harness.input.document.DocumentConversionService;
import com.harness.provider.ChatModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultimodalParserTest {

    @TempDir
    Path uploadDirectory;

    @BeforeEach
    void setUp() {
        EnvConfig.init(Map.of(
                EnvKey.KNOWLEDGE_UPLOAD_DIR, uploadDirectory.toString(),
                EnvKey.MULTIMODAL_IMAGE_ENABLED, "true",
                EnvKey.MULTIMODAL_VIDEO_ENABLED, "false",
                EnvKey.MULTIMODAL_FILE_MAX_SIZE, "50",
                EnvKey.INPUT_FILE_SIZE_THRESHOLD_KB, "100",
                EnvKey.LARGE_FILE_CONTEXT_RATIO, "0.4",
                EnvKey.LARGE_FILE_SUMMARY_CONCURRENCY, "1"));
    }

    @Test
    void convertsSmallFileOnceAndUsesCanonicalMarkdownAsParsedContent() {
        byte[] fileData = "pdf-bytes".getBytes(StandardCharsets.UTF_8);
        RecordingDocumentConversionService conversionService =
                new RecordingDocumentConversionService(conversionResult(fileData.length));
        MultimodalParser parser = parser(conversionService);

        List<MultimodalParser.ParsedAttachment> parsed = parser.parseWithContent(
                List.of(new MultimodalParser.RawAttachment(
                        "report.pdf", fileData, "application/pdf", null)));

        assertThat(conversionService.calls).isEqualTo(1);
        assertThat(conversionService.lastRequest.fileName()).isEqualTo("report.pdf");
        assertThat(conversionService.lastRequest.mimeType()).isEqualTo("application/pdf");
        assertThat(conversionService.lastRequest.fileData()).containsExactly(fileData);

        assertThat(parsed).hasSize(1);
        MultimodalParser.ParsedAttachment parsedAttachment = parsed.getFirst();
        assertThat(parsedAttachment.attachment().type())
                .isEqualTo(AgentMessage.Attachment.AttachmentType.FILE);
        assertThat(parsedAttachment.parsedContent()).isNotNull();
        assertThat(parsedAttachment.parsedContent().text())
                .isEqualTo("# Canonical Markdown\n\nDocument body");
        assertThat(parsedAttachment.parsedContent().strategy())
                .isEqualTo(ParsedContent.ParseStrategy.DIRECT);
        assertThat(parsedAttachment.parsedContent().metadata())
                .containsEntry("source_format", "markdown")
                .containsEntry("document_converter", "markitdown")
                .containsEntry("document_vision_calls", 1)
                .containsEntry("document_vision_model", "vision-model");
    }

    @Test
    void keepsImageAttachmentWithoutDocumentConversion() {
        byte[] imageData = new byte[]{1, 2, 3, 4};
        RecordingDocumentConversionService conversionService =
                new RecordingDocumentConversionService(conversionResult(imageData.length));
        MultimodalParser parser = parser(conversionService);

        List<MultimodalParser.ParsedAttachment> parsed = parser.parseWithContent(
                List.of(new MultimodalParser.RawAttachment(
                        "diagram.png", imageData, "image/png", null)));

        assertThat(conversionService.calls).isZero();
        assertThat(parsed).hasSize(1);
        MultimodalParser.ParsedAttachment parsedAttachment = parsed.getFirst();
        assertThat(parsedAttachment.parsedContent()).isNull();
        assertThat(parsedAttachment.attachment().type())
                .isEqualTo(AgentMessage.Attachment.AttachmentType.IMAGE);
        assertThat(parsedAttachment.attachment().name()).isEqualTo("diagram.png");
        assertThat(parsedAttachment.attachment().mimeType()).isEqualTo("image/png");
        assertThat(parsedAttachment.attachment().data()).containsExactly(imageData);
    }

    @Test
    void propagatesDocumentConversionFailure() {
        DocumentConversionException expected =
                new DocumentConversionException("document parser unavailable");
        DocumentConversionService conversionService = request -> {
            throw expected;
        };
        MultimodalParser parser = parser(conversionService);

        assertThatThrownBy(() -> parser.parseWithContent(
                List.of(new MultimodalParser.RawAttachment(
                        "report.docx",
                        "docx-bytes".getBytes(StandardCharsets.UTF_8),
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        null))))
                .isSameAs(expected);
    }

    private MultimodalParser parser(DocumentConversionService conversionService) {
        return new MultimodalParser(new TestChatModelProvider(), conversionService);
    }

    private static DocumentConversionResult conversionResult(long inputBytes) {
        return new DocumentConversionResult(
                "# Canonical Markdown\n\nDocument body",
                "Canonical Markdown",
                "application/pdf",
                new DocumentConversionDiagnostics(
                        "markitdown",
                        "vision-model",
                        true,
                        List.of(),
                        "vision",
                        1,
                        12,
                        inputBytes));
    }

    private static final class RecordingDocumentConversionService
            implements DocumentConversionService {

        private final DocumentConversionResult result;
        private int calls;
        private DocumentConversionRequest lastRequest;

        private RecordingDocumentConversionService(DocumentConversionResult result) {
            this.result = result;
        }

        @Override
        public DocumentConversionResult convert(DocumentConversionRequest request) {
            calls++;
            lastRequest = request;
            return result;
        }
    }

    private static final class TestChatModelProvider implements ChatModelProvider {

        @Override
        public ChatModel chatModel() {
            return null;
        }

        @Override
        public String providerName() {
            return "test";
        }

        @Override
        public String modelName() {
            return "test-model";
        }

        @Override
        public int contextWindow() {
            return 128_000;
        }
    }
}
