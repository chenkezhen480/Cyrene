package com.harness.input.multimodal;

import com.harness.core.model.AgentMessage;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses multimodal inputs (images, files, video) into a unified message format.
 * All behavior controlled by HARNESS_MULTIMODAL_* env vars.
 * Documents are validated here and read on demand through the isolated file tool.
 */
public class MultimodalParser {

    private static final Logger log = LoggerFactory.getLogger(MultimodalParser.class);
    private final boolean imageEnabled;
    private final boolean videoEnabled;
    private final long maxFileSizeMb;
    private final UrlDownloader urlDownloader;

    public MultimodalParser() {
        EnvConfig cfg = EnvConfig.get();
        this.imageEnabled = cfg.getBool(EnvKey.MULTIMODAL_IMAGE_ENABLED, true);
        this.videoEnabled = cfg.getBool(EnvKey.MULTIMODAL_VIDEO_ENABLED, false);
        this.maxFileSizeMb = cfg.getLong(EnvKey.MULTIMODAL_FILE_MAX_SIZE, 50);
        log.info("[L1-Multimodal] Initialized: image={}, video={}, maxSize={}MB",
                imageEnabled, videoEnabled, maxFileSizeMb);

        this.urlDownloader = new UrlDownloader();
    }

    /**
     * Parse raw attachments into structured AgentMessage.Attachment list.
     * Validates file types and sizes based on env config.
     * Document bodies are not added to the chat input.
     */
    public List<AgentMessage.Attachment> parse(List<RawAttachment> rawAttachments) {
        if (rawAttachments == null || rawAttachments.isEmpty()) {
            return List.of();
        }

        log.debug("[L1-Multimodal] Parsing {} attachments", rawAttachments.size());
        List<AgentMessage.Attachment> result = new ArrayList<>();
        for (RawAttachment raw : rawAttachments) {
            RawAttachment resolved = resolveUrl(raw);
            AgentMessage.Attachment parsed = parseOne(resolved);
            result.add(parsed);
        }
        log.debug("[L1-Multimodal] Parsed {} attachments into {} results", rawAttachments.size(), result.size());
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
        if (raw.data() == null || raw.data().length == 0) {
            throw new com.harness.core.exception.AgentException("File is empty: " + raw.name());
        }
        if (raw.data().length > Math.multiplyExact(maxFileSizeMb, 1024L * 1024L)) {
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

}
