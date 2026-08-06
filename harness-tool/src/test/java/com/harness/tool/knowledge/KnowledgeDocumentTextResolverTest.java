package com.harness.tool.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.input.multimodal.document.PdfPptxDocumentRegionRenderer;
import com.harness.input.multimodal.document.RuleBasedTextCorruptionDetector;
import com.harness.input.multimodal.document.ScannedPageDetector;
import com.harness.input.multimodal.document.StructuredDocumentExtractorRegistry;
import com.harness.provider.VisionModelProvider;
import dev.langchain4j.data.image.Image;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeDocumentTextResolverTest {

    @Test
    void scansCleanWatermarkPageAndRepairsMojibakePage() throws Exception {
        byte[] pdf = twoPageScannedAndCorruptPdf();

        KnowledgeDocumentTextResolver resolver = new KnowledgeDocumentTextResolver(
                StructuredDocumentExtractorRegistry.withDefaults(),
                new RuleBasedTextCorruptionDetector(0.45),
                new DocumentVisionTextService(
                        new StubVisionProvider(),
                        new PdfPptxDocumentRegionRenderer(144f, 1.5),
                        new RuleBasedTextCorruptionDetector(0.45),
                        new DocumentRepairCache(100),
                        new ObjectMapper(),
                        5,
                        0.99),
                new ScannedPageDetector(0.6),
                true);

        ResolvedDocumentText resolved = resolver.resolve(pdf, "scan.pdf", "application/pdf");

        assertThat(resolved.text())
                .contains("真实绘本文字")
                .contains("修复后的文本")
                .doesNotContain("Source: Example Resource");
        assertThat(resolved.ocrBlockCount()).isEqualTo(1);
        assertThat(resolved.repairedBlockCount()).isEqualTo(1);
        assertThat(resolved.ocrModel()).isEqualTo("test:ocr-test");
    }

    @Test
    void skipsOcrWhenVisionUnavailableAndKeepsNativeText() throws Exception {
        byte[] pdf = singleScannedPdf();

        ResolvedDocumentText resolved = resolverWith(new UnavailableVisionProvider())
                .resolve(pdf, "scan.pdf", "application/pdf");

        assertThat(resolved.text()).contains("Source: Example Resource");
        assertThat(resolved.ocrBlockCount()).isZero();
        assertThat(resolved.ocrModel()).isBlank();
    }

    @Test
    void keepsNativeTextWhenOcrFails() throws Exception {
        byte[] pdf = singleScannedPdf();

        ResolvedDocumentText resolved = resolverWith(new ThrowingVisionProvider())
                .resolve(pdf, "scan.pdf", "application/pdf");

        assertThat(resolved.text()).contains("Source: Example Resource");
        assertThat(resolved.ocrBlockCount()).isZero();
        assertThat(resolved.ocrModel()).isBlank();
    }

    private static KnowledgeDocumentTextResolver resolverWith(VisionModelProvider vision) {
        return new KnowledgeDocumentTextResolver(
                StructuredDocumentExtractorRegistry.withDefaults(),
                new RuleBasedTextCorruptionDetector(0.45),
                new DocumentVisionTextService(
                        vision,
                        new PdfPptxDocumentRegionRenderer(144f, 1.5),
                        new RuleBasedTextCorruptionDetector(0.45),
                        new DocumentRepairCache(100),
                        new ObjectMapper(),
                        5,
                        0.99),
                new ScannedPageDetector(0.6),
                true);
    }

    private static byte[] singleScannedPdf() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDImageXObject image = PDImageXObject.createFromByteArray(
                    document, pngBytes(900, 700), "scan-image");
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(image, 0, 0,
                        page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 720);
                content.showText("Source: Example Resource");
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] twoPageScannedAndCorruptPdf() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage scannedPage = new PDPage();
            document.addPage(scannedPage);
            PDImageXObject image = PDImageXObject.createFromByteArray(
                    document, pngBytes(900, 700), "scan-image");
            try (PDPageContentStream content = new PDPageContentStream(document, scannedPage)) {
                content.drawImage(image, 0, 0,
                        scannedPage.getMediaBox().getWidth(), scannedPage.getMediaBox().getHeight());
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 720);
                content.showText("Source: Example Resource");
                content.endText();
            }

            PDPage corruptPage = new PDPage();
            document.addPage(corruptPage);
            try (PDPageContentStream content = new PDPageContentStream(document, corruptPage)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Ã©Â® corrupt");
                content.endText();
            }

            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(220, 220, 220));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static final class StubVisionProvider implements VisionModelProvider {
        @Override
        public String analyze(String prompt, Image image) {
            return analyze(prompt, List.of(image));
        }

        @Override
        public String analyze(String prompt, List<Image> images) {
            if (prompt.contains("transcribing the visible content")) {
                return "{\"pages\":[{\"pageIndex\":0,\"text\":\"真实绘本文字\"}]}";
            }
            return "{\"repairs\":[{\"blockId\":\"pdf-page-2\",\"text\":\"修复后的文本\"}]}";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String providerName() {
            return "test";
        }

        @Override
        public String modelName() {
            return "ocr-test";
        }
    }

    private static final class UnavailableVisionProvider implements VisionModelProvider {
        @Override
        public String analyze(String prompt, Image image) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String analyze(String prompt, List<Image> images) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public String providerName() {
            return "test";
        }

        @Override
        public String modelName() {
            return "none";
        }
    }

    private static final class ThrowingVisionProvider implements VisionModelProvider {
        @Override
        public String analyze(String prompt, Image image) {
            throw new RuntimeException("vision failed");
        }

        @Override
        public String analyze(String prompt, List<Image> images) {
            throw new RuntimeException("vision failed");
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String providerName() {
            return "test";
        }

        @Override
        public String modelName() {
            return "vision-test";
        }
    }
}
