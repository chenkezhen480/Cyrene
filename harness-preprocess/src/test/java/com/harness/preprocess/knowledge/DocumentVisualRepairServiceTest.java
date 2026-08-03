package com.harness.preprocess.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.ai.model.VisionModelProvider;
import com.harness.input.multimodal.document.CorruptionFinding;
import com.harness.input.multimodal.document.DocumentBlock;
import com.harness.input.multimodal.document.DocumentRegionRenderer;
import com.harness.input.multimodal.document.ExtractedDocument;
import com.harness.input.multimodal.document.RenderedDocumentRegion;
import com.harness.input.multimodal.document.RuleBasedTextCorruptionDetector;
import dev.langchain4j.data.image.Image;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentVisualRepairServiceTest {

    @Test
    void repairsOnlyRequestedBlockAndCachesIdenticalRegion() {
        AtomicInteger calls = new AtomicInteger();
        VisionModelProvider vision = new StubVisionProvider(calls);
        DocumentRegionRenderer renderer = new StubRenderer();
        RuleBasedTextCorruptionDetector detector = new RuleBasedTextCorruptionDetector(0.45);
        DocumentVisualRepairService service = new DocumentVisualRepairService(
                vision,
                renderer,
                detector,
                new DocumentRepairCache(10),
                new ObjectMapper());
        ExtractedDocument document = new ExtractedDocument(
                "sample.pdf",
                "application/pdf",
                new byte[]{9},
                List.of(
                        new DocumentBlock("bad", 0, 0, "���", false),
                        new DocumentBlock("good", 0, 1, "保持原文", false)));
        List<CorruptionFinding> findings = detector.detect(document.blocks());

        DocumentVisualRepairService.RepairOutcome first = service.repair(document, findings);
        DocumentVisualRepairService.RepairOutcome second = service.repair(document, findings);

        assertThat(first.document().blocks()).extracting(DocumentBlock::text)
                .containsExactly("恢复文本", "保持原文");
        assertThat(second.repairedBlockCount()).isEqualTo(1);
        assertThat(calls).hasValue(1);
    }

    private static final class StubRenderer implements DocumentRegionRenderer {
        @Override
        public boolean supports(String mimeType) {
            return "application/pdf".equals(mimeType);
        }

        @Override
        public RenderedDocumentRegion render(ExtractedDocument document, int pageIndex) {
            return new RenderedDocumentRegion(pageIndex, new byte[]{1, 2, 3}, "image/png");
        }
    }

    private static final class StubVisionProvider implements VisionModelProvider {
        private final AtomicInteger calls;

        private StubVisionProvider(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public String analyze(String prompt, Image image) {
            calls.incrementAndGet();
            return "{\"repairs\":[{\"blockId\":\"bad\",\"text\":\"恢复文本\"}]}";
        }

        @Override
        public String analyze(String prompt, List<Image> images) {
            throw new UnsupportedOperationException();
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
