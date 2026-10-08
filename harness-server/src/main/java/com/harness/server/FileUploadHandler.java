package com.harness.server;

import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import com.harness.core.security.RequestPrincipal;
import com.harness.server.security.RequestPrincipalResolver;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 处理文件上传，存储到 knowledge-uploads/input/ 目录
 * 返回相对路径 URL 供前端使用
 */
public class FileUploadHandler {

    private static final Logger log = LoggerFactory.getLogger(FileUploadHandler.class);
    private final Path uploadDir;
    private final Path baseDir;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private final com.harness.tool.artifact.UploadedFileAccess uploadedFiles;

    // Allowed file extensions whitelist
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            // Documents
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "csv", "json", "rtf", "odt", "ods", "txt", "md",
            // Images
            "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "tiff", "tif",
            // Video
            "mp4", "webm", "avi", "mov", "mkv", "flv", "wmv",
            // Audio. m4a is what Safari's MediaRecorder produces; webm is accepted above as
            // a video container, which is how a Chrome recording has always slipped through.
            "mp3", "wav", "ogg", "m4a"
    );

    public FileUploadHandler(String baseDir) {
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
        this.uploadDir = this.baseDir.resolve("input");
        this.uploadedFiles = new com.harness.tool.artifact.UploadedFileAccess(this.baseDir, mapper);
    }

    /**
     * POST /api/files/upload
     * multipart/form-data: file
     * 返回: { "url": "/files/input/xxx.png", "name": "original.png" }
     */
    public void handle(Context ctx) {
        UploadedFile file = ctx.uploadedFile("file");
        if (file == null) {
            ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST, "No file uploaded");
            return;
        }

        try {
            // 确保目录存在
            Files.createDirectories(uploadDir);
            Path root = confinedInputRoot();

            // 生成唯一文件名，保留原始扩展名
            String originalName = file.filename();
            String ext = getExtension(originalName).toLowerCase(java.util.Locale.ROOT);

            // Validate file extension against whitelist
            if (!ext.isEmpty() && !ALLOWED_EXTENSIONS.contains(ext)) {
                ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST,
                        "File type not allowed: ." + ext);
                return;
            }

            String mediaMarker = "webm".equals(ext)
                    && file.contentType() != null
                    && file.contentType().toLowerCase(java.util.Locale.ROOT)
                            .startsWith("audio/")
                    ? ".audio"
                    : "";
            String uniqueName = UUID.randomUUID()
                    + mediaMarker
                    + (ext.isEmpty() ? "" : "." + ext);

            // 保存文件
            Path targetPath = root.resolve(uniqueName);
            RequestPrincipal principal = ctx.attribute(RequestPrincipalResolver.PRINCIPAL_ATTRIBUTE);
            if (principal == null) throw new SecurityException("Authenticated upload owner is required");
            String userId = principal.authenticationType() == RequestPrincipal.AuthenticationType.ANONYMOUS
                    ? "anonymous" : principal.requireUserId();
            Path metadataPath = root.resolve(uniqueName + ".owner.json");
            try (var input = file.content()) {
                Files.copy(input, targetPath);
                mapper.writeValue(metadataPath.toFile(), new com.harness.tool.artifact.UploadedFileAccess.FileOwner(userId, principal.tenantId(), file.contentType()));
            } catch (IOException e) {
                Files.deleteIfExists(targetPath);
                Files.deleteIfExists(metadataPath);
                throw e;
            }

            // 返回相对路径 URL
            String url = "/files/input/" + uniqueName;
            log.info("[FileUpload] Stored: {} -> {} ({} bytes)", originalName, targetPath, file.size());

            ctx.json(Map.of(
                    "url", url,
                    "name", originalName,
                    "size", file.size()
            ));

        } catch (IOException e) {
            log.error("[FileUpload] Failed to store file: {}", e.getMessage(), e);
            ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR,
                    "Failed to store file: " + e.getMessage());
        }
    }

    public void download(Context ctx) throws IOException {
        var file = uploadedFiles.authorize("/files/input/" + ctx.pathParam("fileName"), ctx.attribute(RequestPrincipalResolver.PRINCIPAL_ATTRIBUTE));
        ctx.contentType(file.owner().mimeType() == null ? "application/octet-stream" : file.owner().mimeType());
        ctx.header("X-Content-Type-Options", "nosniff");
        ctx.result(Files.newInputStream(file.path()));
    }

    public void authorizeReference(String reference, RequestPrincipal principal) throws IOException {
        uploadedFiles.authorize(reference, principal);
    }

    private Path confinedInputRoot() throws IOException {
        Path root = uploadDir.toRealPath();
        if (!root.startsWith(baseDir.toRealPath())) throw new SecurityException("Input directory escapes upload root");
        return root;
    }

    private String getExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1) : "";
    }
}
