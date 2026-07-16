package com.harness.server;

import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * 处理文件上传，存储到 knowledge-uploads/input/ 目录
 * 返回相对路径 URL 供前端使用
 */
public class FileUploadHandler {

    private static final Logger log = LoggerFactory.getLogger(FileUploadHandler.class);
    private final Path uploadDir;

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
            ctx.status(400).json(Map.of("error", "No file uploaded"));
            return;
        }

        try {
            // 确保目录存在
            Files.createDirectories(uploadDir);

            // 生成唯一文件名，保留原始扩展名
            String originalName = file.filename();
            String ext = getExtension(originalName);
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
            ctx.status(500).json(Map.of("error", "Failed to store file: " + e.getMessage()));
        }
    }

    private String getExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1) : "";
    }
}
