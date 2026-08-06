package com.harness.tool.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.provider.VisionModelProvider;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.input.multimodal.document.CorruptionFinding;
import com.harness.input.multimodal.document.DocumentBlock;
import com.harness.input.multimodal.document.ExtractedDocument;
import com.harness.input.multimodal.document.PdfPptxDocumentRegionRenderer;
import com.harness.input.multimodal.document.RuleBasedTextCorruptionDetector;
import com.harness.input.multimodal.document.ScannedPageDetector;
import com.harness.input.multimodal.document.StructuredDocumentExtractorRegistry;
import com.harness.input.multimodal.document.StructuredDocumentFormatter;
import com.harness.input.multimodal.document.TextCorruptionDetector;
import com.harness.input.multimodal.impl.TextExtractorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Native extraction -> deterministic corruption detection -> optional local-region vision repair.
 *
 * Scanned PDF pages (content rendered as images, hidden text layer of watermarks)
 * are detected up front and routed to batched vision OCR regardless of whether the
 * hidden text is mojibake, since the mojibake-only corruption gate would otherwise
 * let clean watermark text through.
 */
public final class KnowledgeDocumentTextResolver {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentTextResolver.class);

    private final StructuredDocumentExtractorRegistry extractorRegistry;
    private final TextCorruptionDetector corruptionDetector;
    private final DocumentVisionTextService visionTextService;
    private final ScannedPageDetector scannedPageDetector;
    private final boolean imageParsingEnabled;

    public KnowledgeDocumentTextResolver(VisionModelProvider visionModelProvider) {
        EnvConfig config = EnvConfig.get();
        this.extractorRegistry = StructuredDocumentExtractorRegistry.withDefaults();
        this.corruptionDetector = new RuleBasedTextCorruptionDetector(
                config.getDouble(EnvKey.KNOWLEDGE_CORRUPTION_THRESHOLD, 0.45));
        PdfPptxDocumentRegionRenderer renderer = new PdfPptxDocumentRegionRenderer(
                (float) config.getDouble(EnvKey.KNOWLEDGE_VISUAL_RENDER_DPI, 144),
                config.getDouble(EnvKey.KNOWLEDGE_VISUAL_PPT_SCALE, 1.5));
        DocumentRepairCache repairCache = new DocumentRepairCache(config.getInt(
                EnvKey.KNOWLEDGE_VISUAL_REPAIR_CACHE_MAX_ENTRIES, 500));
        this.visionTextService = new DocumentVisionTextService(
                visionModelProvider,
                renderer,
                corruptionDetector,
                repairCache,
                new ObjectMapper(),
                5,
                0.99);
        this.scannedPageDetector = new ScannedPageDetector(0.6);
        this.imageParsingEnabled = config.getBool(EnvKey.MULTIMODAL_IMAGE_ENABLED, true);
    }

    KnowledgeDocumentTextResolver(
            StructuredDocumentExtractorRegistry extractorRegistry,
            TextCorruptionDetector corruptionDetector,
            DocumentVisionTextService visionTextService,
            ScannedPageDetector scannedPageDetector,
            boolean imageParsingEnabled
    ) {
        this.extractorRegistry = java.util.Objects.requireNonNull(
                extractorRegistry, "extractorRegistry");
        this.corruptionDetector = java.util.Objects.requireNonNull(
                corruptionDetector, "corruptionDetector");
        this.visionTextService = java.util.Objects.requireNonNull(
                visionTextService, "visionTextService");
        this.scannedPageDetector = java.util.Objects.requireNonNull(
                scannedPageDetector, "scannedPageDetector");
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

        int ocrBlockCount = 0;
        String ocrModel = "";
        Set<Integer> scannedPagesLocal = Set.of();
        try {
            Set<Integer> detected = Set.copyOf(
                    scannedPageDetector.detectScannedPages(document));
            if (!detected.isEmpty()) {
                if (!visionTextService.isAvailable()) {
                    log.warn(
                            "[ScanOCR] {} scanned page(s) detected but vision model unavailable; keeping native text",
                            detected.size());
                } else {
                    DocumentVisionTextService.OcrOutcome outcome =
                            visionTextService.ocrPages(document, List.copyOf(detected));
                    document = outcome.document();
                    ocrBlockCount = outcome.ocrBlockCount();
                    ocrModel = outcome.ocrModel();
                    log.info("[ScanOCR] Detected {} scanned page(s), OCR'd {} block(s) model={}",
                            detected.size(), ocrBlockCount, ocrModel);
                }
            }
            scannedPagesLocal = detected;
        } catch (Exception e) {
            log.warn("[ScanOCR] Scan OCR failed, keeping native text: {}", e.getMessage());
        }
        final Set<Integer> scannedPages = scannedPagesLocal;

        Map<String, Integer> blockPage = document.blocks().stream()
                .collect(Collectors.toMap(DocumentBlock::blockId, DocumentBlock::pageIndex));
        List<CorruptionFinding> findings = corruptionDetector.detect(document.blocks()).stream()
                .filter(finding -> !scannedPages.contains(blockPage.getOrDefault(
                        finding.blockId(), -1)))
                .toList();
        if (findings.isEmpty()) {
            return new ResolvedDocumentText(
                    StructuredDocumentFormatter.format(document), 0, "",
                    ocrBlockCount, ocrModel);
        }
        if (!imageParsingEnabled) {
            throw new IllegalStateException(
                    "Corrupted document text detected while image parsing is disabled");
        }

        DocumentVisionTextService.RepairOutcome outcome =
                visionTextService.repair(document, findings);
        return new ResolvedDocumentText(
                StructuredDocumentFormatter.format(outcome.document()),
                outcome.repairedBlockCount(),
                outcome.repairModel(),
                ocrBlockCount,
                ocrModel);
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
