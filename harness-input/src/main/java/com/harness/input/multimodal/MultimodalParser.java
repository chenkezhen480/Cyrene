package com.harness.input.multimodal;

import com.harness.provider.ChatModelProvider;
import com.harness.core.model.AgentMessage;
import com.harness.core.model.ParsedContent;
import com.harness.input.document.DocumentConversionResult;
import com.harness.input.document.DocumentConversionService;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses multimodal inputs (images, files, video) into a unified message format.
 * All behavior controlled by HARNESS_MULTIMODAL_* env vars.
 * When file attachments exceed the threshold, uses {@link LargeFileParser} for
 * MapReduce-style summarization.
 */
public class MultimodalParser {

    private static final Logger log = LoggerFactory.getLogger(MultimodalParser.class);
    private final boolean imageEnabled;
    private final boolean videoEnabled;
    private final long maxFileSizeMb;
    private final long thresholdKb;
    private final LargeFileParser largeFileParser;
    private final UrlDownloader urlDownloader;
    private final DocumentConversionService documentConversionService;

    public MultimodalParser(
            ChatModelProvider chatProvider,
            DocumentConversionService documentConversionService
    ) {
        EnvConfig cfg = EnvConfig.get();
        this.documentConversionService = java.util.Objects.requireNonNull(
                documentConversionService, "documentConversionService");
        this.imageEnabled = cfg.getBool(EnvKey.MULTIMODAL_IMAGE_ENABLED, true);
        this.videoEnabled = cfg.getBool(EnvKey.MULTIMODAL_VIDEO_ENABLED, false);
        this.maxFileSizeMb = cfg.getLong(EnvKey.MULTIMODAL_FILE_MAX_SIZE, 50);
        this.thresholdKb = cfg.getInt(EnvKey.INPUT_FILE_SIZE_THRESHOLD_KB, 100);
        log.info("[L1-Multimodal] Initialized: image={}, video={}, maxSize={}MB, threshold={}KB",
                imageEnabled, videoEnabled, maxFileSizeMb, thresholdKb);

        this.largeFileParser = new LargeFileParser(java.util.Objects.requireNonNull(
                chatProvider, "chatProvider"));
        this.urlDownloader = new UrlDownloader();
    }

    /**
     * Parse raw attachments into structured AgentMessage.Attachment list.
     * Validates file types and sizes based on env config.
     * For large FILE attachments, uses LargeFileParser and returns a ParsedAttachment
     * containing the summarized content.
     */
    public List<ParsedAttachment> parseWithContent(List<RawAttachment> rawAttachments) {
        if (rawAttachments == null || rawAttachments.isEmpty()) {
            return List.of();
        }

        log.debug("[L1-Multimodal] Parsing {} attachments", rawAttachments.size());
        List<ParsedAttachment> result = new ArrayList<>();
        for (RawAttachment raw : rawAttachments) {
            RawAttachment resolved = resolveUrl(raw);
            AgentMessage.Attachment parsed = parseOne(resolved);
            ParsedContent parsedContent = null;

            // Every FILE attachment is converted to canonical Markdown exactly once.
            if (parsed.type() == AgentMessage.Attachment.AttachmentType.FILE) {
                DocumentConversionResult converted = documentConversionService.convert(
                        parsed.data(), parsed.name(), parsed.mimeType());
                Map<String, Object> conversionMetadata = conversionMetadata(parsed, converted);
                if (parsed.data().length / 1024 > thresholdKb) {
                    // Large file: MapReduce summarization over canonical Markdown.
                    log.info("[L1-Multimodal] Large file detected: {} ({}KB > {}KB), running MapReduce summarization",
                            parsed.name(), parsed.data().length / 1024, thresholdKb);
                    ParsedContent summarized = largeFileParser.summarizeMarkdown(
                            converted.markdown(), parsed.name(), parsed.data().length);
                    Map<String, Object> summarizedMetadata = new HashMap<>(
                            summarized.metadata());
                    summarizedMetadata.putAll(conversionMetadata);
                    parsedContent = new ParsedContent(
                            summarized.text(), summarized.strategy(),
                            summarized.chunkCount(), summarizedMetadata);
                } else {
                    parsedContent = new ParsedContent(
                            converted.markdown(), ParsedContent.ParseStrategy.DIRECT,
                            1, conversionMetadata);
                }
            }

            result.add(new ParsedAttachment(parsed, parsedContent));
        }
        log.debug("[L1-Multimodal] Parsed {} attachments into {} results", rawAttachments.size(), result.size());
        return result;
    }

    private static Map<String, Object> conversionMetadata(
            AgentMessage.Attachment attachment,
            DocumentConversionResult converted
    ) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("file_name", attachment.name());
        metadata.put("file_size_kb", attachment.data().length / 1024);
        metadata.put("source_format", "markdown");
        metadata.put("detected_mime_type", converted.detectedMimeType());
        metadata.put("document_converter", converted.diagnostics().converter());
        metadata.put("document_ocr_enabled", converted.diagnostics().ocrEnabled());
        metadata.put("document_vision_calls", converted.diagnostics().visionCalls());
        metadata.put("document_vision_source", converted.diagnostics().visionSource());
        if (converted.diagnostics().model() != null) {
            metadata.put("document_vision_model", converted.diagnostics().model());
        }
        if (!converted.diagnostics().warnings().isEmpty()) {
            metadata.put("document_conversion_warnings",
                    String.join("\n", converted.diagnostics().warnings()));
        }
        return metadata;
    }

    /**
     * If the attachment has a URL but no data, download the file and return a new RawAttachment with the downloaded data.
     */
    private RawAttachment resolveUrl(RawAttachment raw) {
        if (raw.url() != null && !raw.url().isBlank() && (raw.data() == null || raw.data().length == 0)) {
            log.debug("[L1-Multimodal] URL attachment detected: {}", raw.url());
            try {
                UrlDownloader.DownloadResult dl = urlDownloader.download(raw.url(), raw.name(), raw.mimeType());
                return new RawAttachment(dl.name(), dl.data(), dl.mimeType(), null);
            } catch (Exception e) {
                log.warn("[L1-Multimodal] URL download failed for {}: {}", raw.url(), e.getMessage());
                throw new com.harness.core.exception.AgentException(
                        "Failed to download file from URL: " + raw.url() + " - " + e.getMessage());
            }
        }
        return raw;
    }

    private AgentMessage.Attachment parseOne(RawAttachment raw) {
        long sizeMb = raw.data().length / (1024 * 1024);
        if (sizeMb > maxFileSizeMb) {
            throw new com.harness.core.exception.AgentException(
                    "File " + raw.name() + " exceeds max size " + maxFileSizeMb + "MB");
        }

        AgentMessage.Attachment.AttachmentType type = detectType(raw);

        return switch (type) {
            case IMAGE -> {
                if (!imageEnabled) {
                    throw new com.harness.core.exception.AgentException("Image input is disabled");
                }
                yield new AgentMessage.Attachment(type, raw.name(), raw.data(), raw.mimeType());
            }
            case VIDEO -> {
                if (!videoEnabled) {
                    throw new com.harness.core.exception.AgentException("Video input is disabled");
                }
                yield new AgentMessage.Attachment(type, raw.name(), raw.data(), raw.mimeType());
            }
            default -> new AgentMessage.Attachment(type, raw.name(), raw.data(), raw.mimeType());
        };
    }

    private AgentMessage.Attachment.AttachmentType detectType(RawAttachment raw) {
        String mime = raw.mimeType() != null ? raw.mimeType().toLowerCase() : "";
        if (mime.startsWith("image/")) return AgentMessage.Attachment.AttachmentType.IMAGE;
        if (mime.startsWith("video/")) return AgentMessage.Attachment.AttachmentType.VIDEO;
        if (mime.startsWith("audio/")) return AgentMessage.Attachment.AttachmentType.AUDIO;
        return AgentMessage.Attachment.AttachmentType.FILE;
    }

    public record RawAttachment(String name, byte[] data, String mimeType, String url) {}

    /**
     * Wraps a parsed attachment with optional pre-parsed content for large files.
     */
    public record ParsedAttachment(AgentMessage.Attachment attachment, ParsedContent parsedContent) {}
}
