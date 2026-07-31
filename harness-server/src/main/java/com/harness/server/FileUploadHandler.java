package com.harness.server;

import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
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

    // Allowed file extensions whitelist
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            // Documents
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "csv", "json", "rtf", "odt", "ods", "txt", "md",
            // Images
            "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "tiff", "tif",
            // Video
            "mp4", "webm", "avi", "mov", "mkv", "flv", "wmv",
            // Audio
            "mp3", "wav", "ogg"
    );

    public FileUploadHandler(String baseDir) {
        this.uploadDir = Path.of(baseDir, "input");
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

            // 生成唯一文件名，保留原始扩展名
            String originalName = file.filename();
            String ext = getExtension(originalName).toLowerCase();

            // Validate file extension against whitelist
            if (!ext.isEmpty() && !ALLOWED_EXTENSIONS.contains(ext)) {
                ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST,
                        "File type not allowed: ." + ext);
                return;
            }

            String uniqueName = UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext);

            // 保存文件
            Path targetPath = uploadDir.resolve(uniqueName);
            Files.copy(file.content(), targetPath);

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

    private String getExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1) : "";
    }
}
