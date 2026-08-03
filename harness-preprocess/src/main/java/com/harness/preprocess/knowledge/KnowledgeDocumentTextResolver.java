package com.harness.preprocess.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.ai.model.VisionModelProvider;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.input.multimodal.document.CorruptionFinding;
import com.harness.input.multimodal.document.DocumentBlock;
import com.harness.input.multimodal.document.ExtractedDocument;
import com.harness.input.multimodal.document.PdfPptxDocumentRegionRenderer;
import com.harness.input.multimodal.document.RuleBasedTextCorruptionDetector;
import com.harness.input.multimodal.document.StructuredDocumentExtractorRegistry;
import com.harness.input.multimodal.document.StructuredDocumentFormatter;
import com.harness.input.multimodal.document.TextCorruptionDetector;
import com.harness.input.multimodal.impl.TextExtractorRegistry;

import java.util.List;

/**
 * Native extraction -> deterministic corruption detection -> optional local-region vision repair.
 */
public final class KnowledgeDocumentTextResolver {

    private final StructuredDocumentExtractorRegistry extractorRegistry;
    private final TextCorruptionDetector corruptionDetector;
    private final DocumentVisualRepairService visualRepairService;
    private final boolean imageParsingEnabled;

    public KnowledgeDocumentTextResolver(VisionModelProvider visionModelProvider) {
        EnvConfig config = EnvConfig.get();
        this.extractorRegistry = StructuredDocumentExtractorRegistry.withDefaults();
        this.corruptionDetector = new RuleBasedTextCorruptionDetector(
                config.getDouble(EnvKey.KNOWLEDGE_CORRUPTION_THRESHOLD, 0.45));
        this.visualRepairService = new DocumentVisualRepairService(
                visionModelProvider,
                new PdfPptxDocumentRegionRenderer(
                        (float) config.getDouble(EnvKey.KNOWLEDGE_VISUAL_RENDER_DPI, 144),
                        config.getDouble(EnvKey.KNOWLEDGE_VISUAL_PPT_SCALE, 1.5)),
                corruptionDetector,
                new DocumentRepairCache(config.getInt(
                        EnvKey.KNOWLEDGE_VISUAL_REPAIR_CACHE_MAX_ENTRIES, 500)),
                new ObjectMapper());
        this.imageParsingEnabled = config.getBool(EnvKey.MULTIMODAL_IMAGE_ENABLED, true);
    }

    KnowledgeDocumentTextResolver(
            StructuredDocumentExtractorRegistry extractorRegistry,
            TextCorruptionDetector corruptionDetector,
            DocumentVisualRepairService visualRepairService,
            boolean imageParsingEnabled
    ) {
        this.extractorRegistry = java.util.Objects.requireNonNull(
                extractorRegistry, "extractorRegistry");
        this.corruptionDetector = java.util.Objects.requireNonNull(
                corruptionDetector, "corruptionDetector");
        this.visualRepairService = java.util.Objects.requireNonNull(
                visualRepairService, "visualRepairService");
        this.imageParsingEnabled = imageParsingEnabled;
    }

    public ResolvedDocumentText resolve(
            byte[] fileData,
            String fileName,
            String mimeType
    ) {
        String effectiveMimeType = effectiveMimeType(fileName, mimeType);
        ExtractedDocument document = extractorRegistry.supports(effectiveMimeType)
                ? extractorRegistry.extract(fileData, fileName, effectiveMimeType)
                : flatDocument(fileData, fileName, effectiveMimeType);

        List<CorruptionFinding> findings = corruptionDetector.detect(document.blocks());
        if (findings.isEmpty()) {
            return new ResolvedDocumentText(StructuredDocumentFormatter.format(document), 0, "");
        }
        if (!imageParsingEnabled) {
            throw new IllegalStateException(
                    "Corrupted document text detected while image parsing is disabled");
        }

        DocumentVisualRepairService.RepairOutcome outcome =
                visualRepairService.repair(document, findings);
        return new ResolvedDocumentText(
                StructuredDocumentFormatter.format(outcome.document()),
                outcome.repairedBlockCount(),
                outcome.repairModel());
    }

    private static ExtractedDocument flatDocument(
            byte[] fileData,
            String fileName,
            String mimeType
    ) {
        String text = TextExtractorRegistry.extract(fileData, fileName, mimeType);
        return new ExtractedDocument(
                fileName,
                mimeType,
                fileData,
                List.of(new DocumentBlock("flat-1", 0, 0, text, false)));
    }

    private static String effectiveMimeType(String fileName, String mimeType) {
        if (mimeType != null && !mimeType.isBlank()
                && !"application/octet-stream".equalsIgnoreCase(mimeType)) {
            return mimeType.toLowerCase();
        }
        String guessed = TextExtractorRegistry.guessMimeType(fileName);
        return guessed != null ? guessed : "application/octet-stream";
    }
}
