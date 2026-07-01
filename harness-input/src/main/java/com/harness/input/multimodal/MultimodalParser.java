package com.harness.input.multimodal;

import com.harness.ai.model.ChatModelProvider;
import com.harness.ai.model.VisionModelProvider;
import com.harness.ai.model.VoiceModelProvider;
import com.harness.core.model.AgentMessage;
import com.harness.core.model.ParsedContent;
import com.harness.input.multimodal.impl.TextExtractorRegistry;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
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

    public MultimodalParser() {
        this(null, null, null);
    }

    public MultimodalParser(ChatModelProvider chatProvider, VisionModelProvider visionProvider, VoiceModelProvider voiceProvider) {
        EnvConfig cfg = EnvConfig.get();
        this.imageEnabled = cfg.getBool(EnvKey.MULTIMODAL_IMAGE_ENABLED, true);
        this.videoEnabled = cfg.getBool(EnvKey.MULTIMODAL_VIDEO_ENABLED, false);
        this.maxFileSizeMb = cfg.getLong(EnvKey.MULTIMODAL_FILE_MAX_SIZE, 50);
        this.thresholdKb = cfg.getInt(EnvKey.INPUT_FILE_SIZE_THRESHOLD_KB, 100);
        log.info("[L1-Multimodal] Initialized: image={}, video={}, maxSize={}MB, threshold={}KB",
                imageEnabled, videoEnabled, maxFileSizeMb, thresholdKb);

        if (chatProvider != null) {
            this.largeFileParser = new LargeFileParser(chatProvider, visionProvider, voiceProvider);
        } else {
            this.largeFileParser = null;
        }
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
            if (parsed != null) {
                ParsedContent parsedContent = null;

                // For FILE attachments, extract text content
                if (parsed.type() == AgentMessage.Attachment.AttachmentType.FILE) {
                    if (largeFileParser != null && parsed.data().length / 1024 > thresholdKb) {
                        // Large file: MapReduce summarization via LLM
                        log.info("[L1-Multimodal] Large file detected: {} ({}KB > {}KB), running MapReduce summarization",
                                parsed.name(), parsed.data().length / 1024, thresholdKb);
                        try {
                            parsedContent = largeFileParser.parse(parsed.data(), parsed.name(), parsed.mimeType());
                        } catch (Exception e) {
                            log.warn("[L1-Multimodal] Large file parsing failed for {}, falling back to direct extraction: {}",
                                    parsed.name(), e.getMessage());
                        }
                    }
                    // Small file or large file fallback: extract text directly
                    if (parsedContent == null) {
                        try {
                            String extractedText = TextExtractorRegistry.extract(parsed.data(), parsed.name(), parsed.mimeType());
                            if (extractedText != null && !extractedText.isBlank()) {
                                Map<String, Object> meta = new HashMap<>();
                                meta.put("file_name", parsed.name());
                                meta.put("file_size_kb", parsed.data().length / 1024);
                                parsedContent = new ParsedContent(extractedText, ParsedContent.ParseStrategy.DIRECT, 1, meta);
                            }
                        } catch (Exception e) {
                            log.warn("[L1-Multimodal] Text extraction failed for {}: {}", parsed.name(), e.getMessage());
                        }
                    }
                }

                result.add(new ParsedAttachment(parsed, parsedContent));
            }
        }
        log.debug("[L1-Multimodal] Parsed {} attachments into {} results", rawAttachments.size(), result.size());
        return result;
    }

    /**
     * Parse raw attachments into structured AgentMessage.Attachment list.
     * Backward-compatible: ignores parsed content from large files.
     */
    public List<AgentMessage.Attachment> parse(List<RawAttachment> rawAttachments) {
        if (rawAttachments == null || rawAttachments.isEmpty()) {
            return List.of();
        }

        List<AgentMessage.Attachment> result = new ArrayList<>();
        for (RawAttachment raw : rawAttachments) {
            RawAttachment resolved = resolveUrl(raw);
            AgentMessage.Attachment parsed = parseOne(resolved);
            if (parsed != null) {
                result.add(parsed);
            }
        }
        return result;
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
