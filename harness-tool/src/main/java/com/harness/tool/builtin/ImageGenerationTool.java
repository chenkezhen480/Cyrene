package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.Artifact;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolOutput;
import com.harness.core.model.ToolSpec;
import com.harness.tool.TypedOutputTool;
import com.harness.tool.CancellableTool;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Built-in tool for generating images via OpenAI-compatible API.
 * Works with DALL-E 3, Volcengine Ark, DashScope, and other compatible providers.
 * Downloads generated images and stores them as artifacts.
 */
public class ImageGenerationTool implements TypedOutputTool, CancellableTool {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationTool.class);
    private static final MediaType JSON_TYPE = MediaType.get("application/json");
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Active OkHttp calls for cancellation support */
    private static final java.util.concurrent.ConcurrentHashMap<Thread, Call> activeCalls = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void cancel() {
        int count = 0;
        for (var entry : activeCalls.entrySet()) {
            Call call = entry.getValue();
            if (!call.isCanceled()) {
                call.cancel();
                count++;
            }
        }
        activeCalls.clear();
        if (count > 0) {
            log.info("[ImageGeneration] Cancelled {} active HTTP calls", count);
        }
    }

    public interface ArtifactStorer {
        Artifact store(byte[] data, String name, String mimeType, String sessionId);

        /**
         * Load artifact bytes by ID. Default throws UnsupportedOperationException.
         * Implementations that support img2img should override this.
         */
        default byte[] loadBytes(String artifactId) {
            throw new UnsupportedOperationException("loadBytes not implemented");
        }
    }

    private final ArtifactStorer storer;
    private final OkHttpClient http;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public ImageGenerationTool(ArtifactStorer storer) {
        this(storer, EnvConfig.get());
    }

    public ImageGenerationTool(ArtifactStorer storer, EnvConfig cfg) {
        this.storer = storer;
        int timeoutSeconds = cfg.getInt(EnvKey.MODEL_CHAT_TIMEOUT_SECONDS, 300);
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build();
        this.apiKey = cfg.getString(EnvKey.TOOL_IMAGE_GEN_API_KEY, "");
        String rawBaseUrl = cfg.getString(EnvKey.TOOL_IMAGE_GEN_BASE_URL, "https://api.openai.com/v1");
        // Normalize: strip trailing slash only. Version path (e.g. /v1, /v3) is part of the base URL.
        this.baseUrl = rawBaseUrl.endsWith("/")
                ? rawBaseUrl.substring(0, rawBaseUrl.length() - 1)
                : rawBaseUrl;
        this.model = cfg.getString(EnvKey.TOOL_IMAGE_GEN_MODEL, "dall-e-3");
    }

    @Override
    public ToolSpec spec() {
        String desc = "Generate images using an AI image generation API. Returns downloadable image artifacts. "
                + "Use this when the user asks to create, generate, or draw an image. "
                + "For image-to-image editing (modify/extend a previous image), set reference_image parameter. "
                + "The reference_image accepts: "
                + "  - File paths from reference files section (e.g. /files/input/xxx.jpg) "
                + "  - Image URLs from conversation history (e.g. /api/artifacts/{id}/preview) "
                + "Without reference_image, the tool generates a completely new unrelated image.";

        ObjectNode params = mapper.createObjectNode();
        params.put("type", "object");
        ObjectNode props = mapper.createObjectNode();

        props.set("prompt", mapper.createObjectNode()
                .put("type", "string")
                .put("description", "A detailed text description of the image to generate. Be specific about content, style, colors, composition."));
        props.set("size", mapper.createObjectNode()
                .put("type", "string")
                .put("description", "Image dimensions WxH, max 1024 per side, minimum total area 921600px. Examples: 1024x1024, 960x960, 1024x960. Default: 1024x1024"));
        props.set("quality", mapper.createObjectNode()
                .put("type", "string")
                .put("description", "Image quality: standard or hd. Default: standard (DALL-E only, ignored by other providers)"));
        props.set("style", mapper.createObjectNode()
                .put("type", "string")
                .put("description", "Style: vivid or natural. Default: vivid (DALL-E only, ignored by other providers)"));
        props.set("reference_image", mapper.createObjectNode()
                .put("type", "string")
                .put("description", "Reference image for image-to-image editing. Accepts: "
                        + "  - File path: /files/input/xxx.jpg (from reference files section) "
                        + "  - URL: /api/artifacts/{id}/preview (from conversation history) "
                        + "When set, generates a new image based on this reference + the prompt."));

        params.set("properties", props);
        params.set("required", mapper.createArrayNode().add("prompt"));

        return new ToolSpec("image_generation", desc, params);
    }

    @Override
    public ToolOutput executeOutput(JsonNode arguments) {
        String prompt = arguments.has("prompt") ? arguments.get("prompt").asText() : null;
        if (prompt == null || prompt.isBlank()) {
            throw new ToolExecutionException("image_generation", "Missing required parameter: prompt");
        }

        String size = arguments.has("size") ? arguments.get("size").asText("1024x1024") : "1024x1024";
        size = validateSize(size);

        if (apiKey == null || apiKey.isBlank()) {
            throw new ToolExecutionException("image_generation", "No API key configured. Set HARNESS_TOOL_IMAGE_GEN_API_KEY");
        }

        // Check for image-to-image mode
        String referenceImage = arguments.has("reference_image") ? arguments.get("reference_image").asText(null) : null;

        try {
            List<Artifact> artifactList;
            if (referenceImage != null && !referenceImage.isBlank()) {
                artifactList = executeImg2Img(prompt, size, referenceImage, arguments);
            } else {
                artifactList = executeText2Img(prompt, size, arguments);
            }

            ToolResult.setCurrentStatus(ToolResult.ResultStatus.SUCCESS);
            return ToolOutput.artifacts(
                    "Image generation completed with " + artifactList.size()
                            + " artifact(s) using model " + model + ".",
                    artifactList);

        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException("image_generation", "Image generation failed: " + e.getMessage(), e);
        }
    }

    private String validateSize(String size) {
        try {
            String[] parts = size.split("x");
            int w = Integer.parseInt(parts[0].trim());
            int h = Integer.parseInt(parts[1].trim());
            if (w * h < 921600) {
                log.warn("Image size {} ({}px) below minimum 921600px, falling back to 1024x1024", size, w * h);
                return "1024x1024";
            }
            return size;
        } catch (Exception e) {
            log.warn("Invalid size format '{}', falling back to 1024x1024", size);
            return "1024x1024";
        }
    }

    /**
     * Text-to-image: POST /images/generations (JSON body).
     */
    private List<Artifact> executeText2Img(String prompt, String size, JsonNode arguments) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("n", 1);
        body.put("size", size);
        if (arguments.has("quality")) {
            body.put("quality", arguments.get("quality").asText("standard"));
        }
        if (arguments.has("style")) {
            body.put("style", arguments.get("style").asText("vivid"));
        }
        body.put("response_format", "url");
        body.put("watermark", false);

        String url = baseUrl + "/images/generations";
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON_TYPE))
                .build();

        log.debug("Image generation request: model={}, prompt='{}', size={}",
                model, truncate(prompt, 50), size);

        return executeAndParseResponse(request);
    }

    /**
     * Image-to-image: POST /images/generations with reference image.
     * Supports both OpenAI-style /images/edits and compatible providers (DashScope, Volcengine, etc.)
     * that use /images/generations with image parameter.
     */
    private List<Artifact> executeImg2Img(String prompt, String size,
                                                      String referenceImage, JsonNode arguments) throws Exception {
        // Resolve reference image bytes and convert to base64
        byte[] refBytes = loadReferenceImage(referenceImage);
        String base64Image = java.util.Base64.getEncoder().encodeToString(refBytes);
        String dataUri = "data:image/png;base64," + base64Image;

        // Build JSON body (compatible with most providers)
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("n", 1);
        body.put("size", size);
        body.put("response_format", "url");
        body.put("watermark", false);
        // Reference image as base64 data URI
        body.put("image", dataUri);

        if (arguments.has("quality")) {
            body.put("quality", arguments.get("quality").asText("standard"));
        }
        if (arguments.has("style")) {
            body.put("style", arguments.get("style").asText("vivid"));
        }

        String url = baseUrl + "/images/generations";
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON_TYPE))
                .build();

        log.debug("Image edit request: model={}, prompt='{}', size={}, refImage={}",
                model, truncate(prompt, 50), size, referenceImage);

        return executeAndParseResponse(request);
    }

    /**
     * Load reference image bytes from URL, artifact ID, or local file path.
     */
    private byte[] loadReferenceImage(String ref) throws IOException {
        // Try extracting artifact ID first (handles both relative /api/artifacts/{id}/preview and absolute URLs)
        String artifactId = extractArtifactId(ref);
        if (artifactId != null && storer != null) {
            try {
                return storer.loadBytes(artifactId);
            } catch (Exception e) {
                log.debug("Failed to load artifact {}, falling back", artifactId);
            }
        }

        // If it looks like an absolute URL, download it
        if (ref.startsWith("http://") || ref.startsWith("https://")) {
            return downloadImage(ref);
        }

        // If it starts with /files/, resolve to local knowledge-uploads directory
        if (ref.startsWith("/files/")) {
            String relativePath = ref.substring("/files/".length());
            String uploadDir = EnvConfig.get().getString(
                    com.harness.core.env.EnvKey.KNOWLEDGE_UPLOAD_DIR, "./knowledge-uploads");
            java.nio.file.Path filePath = java.nio.file.Path.of(uploadDir, relativePath);
            if (java.nio.file.Files.exists(filePath)) {
                log.debug("Loading reference image from local file: {}", filePath);
                return java.nio.file.Files.readAllBytes(filePath);
            }
            log.warn("Local file not found: {}", filePath);
        }

        // Otherwise treat as artifact ID directly
        if (storer != null) {
            try {
                return storer.loadBytes(ref);
            } catch (UnsupportedOperationException e) {
                throw new IOException("Cannot load artifact by ID: loadBytes not implemented");
            }
        }

        throw new IOException("Cannot load reference image: " + ref);
    }

    /**
     * Extract artifact ID from URLs like /api/artifacts/{id}/preview or /api/artifacts/{id}
     */
    private String extractArtifactId(String url) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("/api/artifacts/([^/]+)").matcher(url);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Execute request and parse response — shared by text2img and img2img.
     * Tracks active call for cancellation support.
     */
    private List<Artifact> executeAndParseResponse(Request request) throws Exception {
        Call call = http.newCall(request);
        Thread currentThread = Thread.currentThread();
        activeCalls.put(currentThread, call);
        try {
            try (Response response = call.execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    log.error("Image API error: status={}, body={}", response.code(), responseBody);
                    throw new ToolExecutionException("image_generation",
                            "Image API returned " + response.code() + ": " + truncate(responseBody, 500));
                }

                JsonNode resultNode = mapper.readTree(responseBody);
                JsonNode data = resultNode.get("data");
                if (data == null || !data.isArray() || data.isEmpty()) {
                    throw new ToolExecutionException("image_generation", "Image API returned no image data");
                }

                List<Artifact> artifactList = new ArrayList<>();
                for (JsonNode imageData : data) {
                    String imageUrl = imageData.has("url") ? imageData.get("url").asText() : null;
                    if (imageUrl == null && imageData.has("b64_json")) {
                        byte[] imageBytes = java.util.Base64.getDecoder().decode(imageData.get("b64_json").asText());
                        String fileName = "img-" + System.currentTimeMillis() + ".png";
                        Artifact artifact = storer.store(imageBytes, fileName, "image/png", null);
                        artifactList.add(artifact);
                        log.info("Generated image (b64): {} ({} bytes)", fileName, imageBytes.length);
                        continue;
                    }
                    if (imageUrl == null) {
                        log.warn("Image data has neither url nor b64_json: {}", imageData);
                        continue;
                    }

                    byte[] imageBytes = downloadImage(imageUrl);
                    String fileName = "img-" + System.currentTimeMillis() + ".png";
                    Artifact artifact = storer.store(imageBytes, fileName, "image/png", null);
                    artifactList.add(artifact);
                    log.info("Generated image: {} ({} bytes)", fileName, imageBytes.length);
                }
                return artifactList;
            }
        } catch (java.io.IOException e) {
            if (call.isCanceled()) {
                log.info("[ImageGeneration] HTTP call cancelled");
                throw new ToolExecutionException("image_generation", "Request cancelled");
            }
            throw e;
        } finally {
            activeCalls.remove(currentThread);
        }
    }

    private byte[] downloadImage(String imageUrl) throws IOException {
        Request request = new Request.Builder().url(imageUrl).get().build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Failed to download image: HTTP " + response.code());
            }
            return response.body().bytes();
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
