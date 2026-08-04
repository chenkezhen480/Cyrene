package com.harness.input.multimodal;

import com.harness.core.exception.AgentException;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.input.multimodal.impl.TextExtractorRegistry;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Downloads files from URLs to local disk for processing.
 * Files are saved to HARNESS_KNOWLEDGE_UPLOAD_DIR/downloads/.
 */
public class UrlDownloader {

    private static final Logger log = LoggerFactory.getLogger(UrlDownloader.class);
    private static final int MAX_BODY_BYTES = 100 * 1024 * 1024; // 100MB
    private static final int BUFFER_SIZE = 8192;

    private final OkHttpClient http;
    private final Path downloadDir;
    private final long maxFileSizeBytes;
    private final boolean blockPrivateIps;

    public UrlDownloader() {
        EnvConfig cfg = EnvConfig.get();
        String baseDir = cfg.getString(EnvKey.KNOWLEDGE_UPLOAD_DIR, "uploads");
        this.downloadDir = Paths.get(baseDir, "downloads");
        this.maxFileSizeBytes = cfg.getLong(EnvKey.MULTIMODAL_FILE_MAX_SIZE, 50) * 1024 * 1024;
        this.blockPrivateIps = cfg.getBool(EnvKey.MULTIMODAL_URL_BLOCK_PRIVATE_IPS, true);

        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();

        try {
            Files.createDirectories(downloadDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create download directory: " + downloadDir, e);
        }
        log.info("[UrlDownloader] Initialized: dir={}, maxSize={}MB, blockPrivateIps={}",
                downloadDir, maxFileSizeBytes / (1024 * 1024), blockPrivateIps);
    }

    /**
     * Download a file from the given URL.
     *
     * @param url          the HTTP/HTTPS URL to download
     * @param hintName     optional filename hint (from caller)
     * @param hintMimeType optional MIME type hint (from caller)
     * @return download result with name, data, mimeType, and saved path
     */
    public DownloadResult download(String url, String hintName, String hintMimeType) {
        validateUrl(url);

        log.debug("[UrlDownloader] Downloading: {}", url);
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "HarnessAgent/1.0")
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new AgentException("HTTP " + response.code() + " when downloading: " + url);
            }

            // Detect MIME type
            String mimeType = detectMimeType(response, url, hintMimeType);

            // Detect filename
            String name = detectName(response, url, hintName);

            // Read body with size limit
            byte[] data = readBodyWithLimit(response, maxFileSizeBytes);

            // Save to disk
            String safeName = sanitizeFileName(name);
            String diskName = UUID.randomUUID().toString().substring(0, 8) + "_" + safeName;
            Path savedPath = downloadDir.resolve(diskName);
            Files.write(savedPath, data);

            log.debug("[UrlDownloader] Downloaded {} ({}KB), saved to {}", name, data.length / 1024, savedPath);
            return new DownloadResult(name, data, mimeType, savedPath);

        } catch (AgentException e) {
            throw e;
        } catch (IOException e) {
            throw new AgentException("Failed to download from URL: " + url + " - " + e.getMessage());
        }
    }

    private void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new AgentException("URL is empty");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new AgentException("Invalid URL: " + url);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new AgentException("Only HTTP/HTTPS URLs are supported, got: " + scheme);
        }
        if (blockPrivateIps) {
            String host = uri.getHost();
            if (host != null) {
                checkNotPrivateIp(host);
            }
        }
    }

    private void checkNotPrivateIp(String host) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
                throw new AgentException("URL points to private/internal IP: " + host);
            }
        } catch (UnknownHostException e) {
            throw new AgentException("Cannot resolve host: " + host);
        }
    }

    private String detectMimeType(Response response, String url, String hintMimeType) {
        // 1. Content-Type header
        String contentType = response.header("Content-Type");
        if (contentType != null) {
            String mime = contentType.split(";")[0].trim();
            if (!mime.isEmpty() && !"application/octet-stream".equals(mime)) {
                return mime;
            }
        }
        // 2. URL extension
        String urlPath = URI.create(url).getPath();
        if (urlPath != null) {
            String guessed = TextExtractorRegistry.guessMimeType(urlPath);
            if (guessed != null) return guessed;
        }
        // 3. Hint from caller
        if (hintMimeType != null && !hintMimeType.isEmpty() && !"application/octet-stream".equals(hintMimeType)) {
            return hintMimeType;
        }
        return "application/octet-stream";
    }

    private String detectName(Response response, String url, String hintName) {
        // 1. Caller hint
        if (hintName != null && !hintName.isBlank()) {
            return hintName;
        }
        // 2. Content-Disposition header
        String disposition = response.header("Content-Disposition");
        if (disposition != null) {
            // Try to extract filename="..." or filename*=UTF-8''...
            int idx = disposition.indexOf("filename=");
            if (idx >= 0) {
                String val = disposition.substring(idx + 9).trim();
                if (val.startsWith("\"") && val.endsWith("\"")) {
                    val = val.substring(1, val.length() - 1);
                }
                if (!val.isEmpty()) return val;
            }
        }
        // 3. URL path segment
        String path = URI.create(url).getPath();
        if (path != null && !path.equals("/")) {
            String segment = path.substring(path.lastIndexOf('/') + 1);
            if (!segment.isEmpty()) return segment;
        }
        // 4. UUID fallback
        return "download_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private byte[] readBodyWithLimit(Response response, long maxBytes) throws IOException {
        try (InputStream in = response.body().byteStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[BUFFER_SIZE];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new AgentException("Downloaded file exceeds max size: " + (maxBytes / (1024 * 1024)) + "MB");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    public record DownloadResult(
            String name,
            byte[] data,
            String mimeType,
            Path savedPath
    ) {}
}
