package com.harness.tool.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.input.multimodal.document.CorruptionFinding;
import com.harness.input.multimodal.document.DocumentBlock;
import com.harness.input.multimodal.document.DocumentRegionRenderer;
import com.harness.input.multimodal.document.ExtractedDocument;
import com.harness.input.multimodal.document.RenderedDocumentRegion;
import com.harness.input.multimodal.document.RuleBasedTextCorruptionDetector;
import com.harness.provider.VisionModelProvider;
import dev.langchain4j.data.image.Image;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentVisionTextServiceTest {

    private static final double BLANK_THRESHOLD = 0.99;

    private static DocumentVisionTextService service(
            VisionModelProvider vision,
            DocumentRegionRenderer renderer
    ) {
        return new DocumentVisionTextService(
                vision,
                renderer,
                new RuleBasedTextCorruptionDetector(0.45),
                new DocumentRepairCache(100),
                new ObjectMapper(),
                5,
                BLANK_THRESHOLD);
    }

    // ===================== repair =====================

    @Test
    void repairsOnlyRequestedBlockAndCachesIdenticalRegion() {
        AtomicInteger calls = new AtomicInteger();
        DocumentVisionTextService service = service(new StubVisionProvider(calls), new StubRenderer(Set.of()));
        ExtractedDocument document = new ExtractedDocument(
                "sample.pdf",
                "application/pdf",
                new byte[]{9},
                List.of(
                        new DocumentBlock("bad", 0, 0, "���", false),
                        new DocumentBlock("good", 0, 1, "保持原文", false)));
        List<CorruptionFinding> findings = new RuleBasedTextCorruptionDetector(0.45)
                .detect(document.blocks());

        DocumentVisionTextService.RepairOutcome first = service.repair(document, findings);
        DocumentVisionTextService.RepairOutcome second = service.repair(document, findings);

        assertThat(first.document().blocks()).extracting(DocumentBlock::text)
                .containsExactly("恢复文本", "保持原文");
        assertThat(second.repairedBlockCount()).isEqualTo(1);
        assertThat(calls).hasValue(1);
    }

    // ===================== scan OCR =====================

    @Test
    void batchesPagesIntoGroupsOfFive() {
        AtomicInteger calls = new AtomicInteger();
        DocumentVisionTextService service = service(new StubVisionProvider(calls), new StubRenderer(Set.of()));
        ExtractedDocument document = scannedDocument(6);

        DocumentVisionTextService.OcrOutcome outcome =
                service.ocrPages(document, List.of(0, 1, 2, 3, 4, 5));

        assertThat(calls).hasValue(2);
        assertThat(outcome.ocrBlockCount()).isEqualTo(6);
        assertThat(outcome.skippedBlankPages()).isZero();
        assertThat(outcome.document().blocks()).extracting(DocumentBlock::text)
                .containsExactly("OCR-page-0", "OCR-page-1", "OCR-page-2",
                        "OCR-page-3", "OCR-page-4", "OCR-page-5");
    }

    @Test
    void skipsNearBlankPages() {
        AtomicInteger calls = new AtomicInteger();
        DocumentVisionTextService service = service(new StubVisionProvider(calls), new StubRenderer(Set.of(1)));
        ExtractedDocument document = scannedDocument(3);

        DocumentVisionTextService.OcrOutcome outcome =
                service.ocrPages(document, List.of(0, 1, 2));

        assertThat(calls).hasValue(1);
        assertThat(outcome.ocrBlockCount()).isEqualTo(2);
        assertThat(outcome.skippedBlankPages()).isEqualTo(1);
        assertThat(outcome.document().blocks()).extracting(DocumentBlock::text)
                .containsExactly("OCR-page-0", "", "OCR-page-2");
    }

    @Test
    void cachesIdenticalOcrRegion() {
        AtomicInteger calls = new AtomicInteger();
        DocumentVisionTextService service = service(new StubVisionProvider(calls), new StubRenderer(Set.of()));
        ExtractedDocument document = scannedDocument(2);

        service.ocrPages(document, List.of(0, 1));
        service.ocrPages(document, List.of(0, 1));

        assertThat(calls).hasValue(1);
    }

    // ===================== helpers =====================

    private static ExtractedDocument scannedDocument(int pageCount) {
        List<DocumentBlock> blocks = new ArrayList<>();
        for (int i = 0; i < pageCount; i++) {
            blocks.add(new DocumentBlock("pdf-page-" + (i + 1), i, i, "水印", false));
        }
        return new ExtractedDocument("scan.pdf", "application/pdf", new byte[]{9}, blocks);
    }

    private static final class StubRenderer implements DocumentRegionRenderer {
        private final Set<Integer> blankPages;

        private StubRenderer(Set<Integer> blankPages) {
            this.blankPages = blankPages;
        }

        @Override
        public boolean supports(String mimeType) {
            return "application/pdf".equals(mimeType);
        }

        @Override
        public RenderedDocumentRegion render(ExtractedDocument document, int pageIndex) {
            Color color = blankPages.contains(pageIndex) ? Color.WHITE : new Color(200, 200, 200);
            return new RenderedDocumentRegion(pageIndex, pngBytes(120, 120, color), "image/png");
        }
    }

    private static final class StubVisionProvider implements VisionModelProvider {
        private static final Pattern PAGE_INDICES =
                Pattern.compile("pageIndex values: \\[([^\\]]*)\\]");
        private final AtomicInteger calls;

        private StubVisionProvider(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public String analyze(String prompt, Image image) {
            return analyze(prompt, List.of(image));
        }

        @Override
        public String analyze(String prompt, List<Image> images) {
            calls.incrementAndGet();
            if (prompt.contains("transcribing the visible content")) {
                return ocrJson(prompt);
            }
            return "{\"repairs\":[{\"blockId\":\"bad\",\"text\":\"恢复文本\"}]}";
        }

        private static String ocrJson(String prompt) {
            Matcher matcher = PAGE_INDICES.matcher(prompt);
            if (!matcher.find()) {
                throw new IllegalStateException("prompt does not list page indices");
            }
            String[] indices = matcher.group(1).trim().split("\\s*,\\s*");
            StringBuilder response = new StringBuilder("{\"pages\":[");
            for (int i = 0; i < indices.length; i++) {
                if (i > 0) {
                    response.append(',');
                }
                response.append("{\"pageIndex\":").append(indices[i])
                        .append(",\"text\":\"OCR-page-").append(indices[i]).append("\"}");
            }
            return response.append("]}").toString();
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

    private static byte[] pngBytes(int width, int height, Color color) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setColor(color);
                graphics.fillRect(0, 0, width, height);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create stub PNG", e);
        }
    }
}
