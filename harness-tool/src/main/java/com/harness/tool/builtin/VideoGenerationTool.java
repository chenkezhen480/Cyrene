package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.Artifact;
import com.harness.core.model.ResultStatus;
import com.harness.core.model.ToolExecutionOutcome;
import com.harness.core.model.ToolOutput;
import com.harness.core.model.ToolSpec;
import com.harness.tool.TypedOutputTool;
import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigKey;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;

/**
 * Built-in tool for video generation via external API (async pattern).
 *
 * Two actions:
 * - submit: Submit a video generation task, returns task_id + status message
 * - check: Check task status, returns artifact if completed
 *
 * Background polling thread monitors submitted tasks and stores completed videos as artifacts.
 */
public class VideoGenerationTool implements TypedOutputTool, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VideoGenerationTool.class);
    private static final MediaType JSON_TYPE = MediaType.get("application/json");
    private static final ObjectMapper mapper = new ObjectMapper();

    @FunctionalInterface
    public interface ArtifactStorer {
        Artifact store(byte[] data, String name, String mimeType, String sessionId);
    }

    @FunctionalInterface
    public interface ArtifactCallback {
        void onArtifactReady(String sessionId, Artifact artifact);
    }

    private final ArtifactStorer storer;
    private final ArtifactCallback callback;
    private final OkHttpClient http;
    private final String provider;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String submitPath;
    private final String statusPath;
    private final ScheduledExecutorService scheduler;
    private final long taskRetentionMillis;
    private final ConcurrentMap<String, TaskState> tasks;

    public VideoGenerationTool(
            ArtifactStorer storer,
            ArtifactCallback callback,
            ModelConfig cfg
    ) {
        this(storer, callback, cfg, new ConcurrentHashMap<>());
    }

    public VideoGenerationTool(ArtifactStorer storer, ArtifactCallback callback, ModelConfig cfg,
                               ConcurrentMap<String, TaskState> tasks) {
        this.tasks = java.util.Objects.requireNonNull(tasks, "tasks");
        this.storer = storer;
        this.callback = callback;
        long retentionSeconds = com.harness.core.env.EnvConfig.get().getLong(
                com.harness.core.env.EnvKey.TOOL_VIDEO_TASK_RETENTION_SECONDS, 86400);
        if (retentionSeconds < 1) throw new IllegalArgumentException("Video task retention must be positive");
        this.taskRetentionMillis = Math.multiplyExact(retentionSeconds, 1000L);
        int timeoutSeconds = cfg.getInt(ModelConfigKey.CHAT_TIMEOUT_SECONDS, 300);
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build();
        this.provider = cfg.getString(ModelConfigKey.VIDEO_PROVIDER, "");
        this.apiKey = cfg.getString(ModelConfigKey.VIDEO_API_KEY, "");
        String rawBaseUrl = cfg.getString(ModelConfigKey.VIDEO_BASE_URL, "");
        this.baseUrl = rawBaseUrl.endsWith("/") ? rawBaseUrl.substring(0, rawBaseUrl.length() - 1) : rawBaseUrl;
        this.model = cfg.getString(ModelConfigKey.VIDEO_MODEL, "");
        this.submitPath = cfg.getString(ModelConfigKey.VIDEO_SUBMIT_PATH, "/submit");
        this.statusPath = cfg.getString(ModelConfigKey.VIDEO_STATUS_PATH, "/status");
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "video-gen-poller");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "video_generation",
                "Generate videos using an external AI video generation API. " +
                "Use action='submit' to start generation, then action='check' to get the result. " +
                "Video generation is asynchronous — it may take several minutes.",
                mapper.createObjectNode()
                        .put("type", "object")
                        .<ObjectNode>set("properties", mapper.createObjectNode()
                                .<ObjectNode>set("action", mapper.createObjectNode()
                                        .put("type", "string")
                                        .put("description", "Action: 'submit' to start generation, 'check' to check status"))
                                .<ObjectNode>set("prompt", mapper.createObjectNode()
                                        .put("type", "string")
                                        .put("description", "Text description of the video to generate (required for submit)"))
                                .<ObjectNode>set("task_id", mapper.createObjectNode()
                                        .put("type", "string")
                                        .put("description", "Task ID returned from submit (required for check)"))
                                .<ObjectNode>set("duration", mapper.createObjectNode()
                                        .put("type", "integer")
                                        .put("description", "Video duration in seconds (default: 5)"))
                                .<ObjectNode>set("resolution", mapper.createObjectNode()
                                        .put("type", "string")
                                        .put("description", "Video resolution: 720p, 1080p (default: 1080p)")))
                        .<ObjectNode>set("required", mapper.createArrayNode().add("action")),
                com.harness.core.model.ToolCapability.GENERATION
        );
    }

    @Override
    public ToolOutput executeOutput(JsonNode arguments) {
        return executeOutcome(arguments).content();
    }

    @Override
    public ToolExecutionOutcome executeOutcome(JsonNode arguments) {
        String action = arguments.has("action") ? arguments.get("action").asText() : null;
        if (action == null || action.isBlank()) {
            throw new ToolExecutionException("video_generation", "Missing required parameter: action");
        }

        if (apiKey == null || apiKey.isBlank() || baseUrl == null || baseUrl.isBlank()) {
            throw new ToolExecutionException("video_generation",
                    "Video generation not configured. Set videoGeneration.provider, " +
                    "videoGeneration.apiKey, and videoGeneration.baseUrl in model.conf");
        }

        pruneExpiredTasks();
        return switch (action) {
            case "submit" -> handleSubmit(arguments);
            case "check" -> handleCheck(arguments);
            default -> throw new ToolExecutionException("video_generation",
                    "Unknown action: " + action + ". Use 'submit' or 'check'");
        };
    }

    private ToolExecutionOutcome handleSubmit(JsonNode arguments) {
        String sessionId = com.harness.tool.artifact.ArtifactSessionContext.current();
        if (sessionId == null) throw new ToolExecutionException("video_generation", "Trusted session is required");
        String prompt = arguments.has("prompt") ? arguments.get("prompt").asText() : null;
        if (prompt == null || prompt.isBlank()) {
            throw new ToolExecutionException("video_generation", "Missing required parameter for submit: prompt");
        }

        int duration = arguments.has("duration") ? arguments.get("duration").asInt(5) : 5;
        String resolution = arguments.has("resolution") ? arguments.get("resolution").asText("1080p") : "1080p";

        try {
            // Build submit request (generic API format — adapt per provider)
            ObjectNode body = mapper.createObjectNode();
            if (!model.isBlank()) {
                body.put("model", model);
            }
            body.put("prompt", prompt);
            body.put("duration", duration);
            body.put("resolution", resolution);

            String submitUrl = baseUrl + submitPath;
            Request request = new Request.Builder()
                    .url(submitUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(mapper.writeValueAsString(body), JSON_TYPE))
                    .build();

            log.debug("Video generation submit: prompt='{}', duration={}s, resolution={}",
                    prompt.length() > 50 ? prompt.substring(0, 50) + "..." : prompt, duration, resolution);

            try (Response response = http.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new ToolExecutionException("video_generation",
                            "Video API submit returned " + response.code() + ": " + truncate(responseBody, 500));
                }

                JsonNode resultNode = mapper.readTree(responseBody);
                String taskId = resultNode.has("task_id") ? resultNode.get("task_id").asText() : null;
                if (taskId == null) {
                    throw new ToolExecutionException("video_generation", "Video API returned no task_id");
                }

                // Store task state and start background polling
                TaskState state = new TaskState(this, sessionId, System.currentTimeMillis());
                if (tasks.putIfAbsent(taskId, state) != null) {
                    throw new ToolExecutionException("video_generation", "Video task ID collision");
                }
                startPolling(taskId, state);

                ObjectNode result = mapper.createObjectNode();
                result.put("status", "submitted");
                result.put("task_id", taskId);
                result.put("message", "视频正在生成中，完成后会自动通知。可稍后使用 action='check', task_id='" + taskId + "' 查询状态。");
                return ToolExecutionOutcome.succeeded(
                        ToolOutput.text(mapper.writeValueAsString(result)),
                        ResultStatus.PENDING);
            }

        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException("video_generation", "Submit failed: " + e.getMessage(), e);
        }
    }

    private ToolExecutionOutcome handleCheck(JsonNode arguments) {
        String taskId = arguments.has("task_id") ? arguments.get("task_id").asText() : null;
        if (taskId == null || taskId.isBlank()) {
            throw new ToolExecutionException("video_generation", "Missing required parameter for check: task_id");
        }

        TaskState state = tasks.get(taskId);
        if (state == null || !state.sessionId.equals(com.harness.tool.artifact.ArtifactSessionContext.current())) {
            throw new ToolExecutionException("video_generation", "Video task access denied");
        }

        if (state.artifact != null) {
            // Already completed
            ObjectNode result = mapper.createObjectNode();
            result.put("status", "completed");
            result.put("task_id", taskId);
            try {
                return ToolExecutionOutcome.succeeded(
                        ToolOutput.artifacts(
                                mapper.writeValueAsString(result), List.of(state.artifact)),
                        ResultStatus.AVAILABLE);
            } catch (Exception e) {
                throw new ToolExecutionException("video_generation", "Serialization failed: " + e.getMessage(), e);
            }
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("status", state.status);
        result.put("task_id", taskId);
        result.put("message", "视频仍在生成中，请稍后再试。");
        try {
            ResultStatus status = "failed".equals(state.status)
                    ? ResultStatus.CONTRACT_FAILED
                    : ResultStatus.PENDING;
            return ToolExecutionOutcome.succeeded(
                    ToolOutput.text(mapper.writeValueAsString(result)), status);
        } catch (Exception e) {
            throw new ToolExecutionException("video_generation", "Serialization failed: " + e.getMessage(), e);
        }
    }

    private void startPolling(String taskId, TaskState state) {
        state.poll = scheduler.scheduleWithFixedDelay(() -> {
            try {
                synchronized (state) {
                    pruneExpiredTasks();
                    if (tasks.get(taskId) != state) { state.poll.cancel(false); return; }
                    pollTaskStatus(taskId);
                    if (state.artifact != null || "failed".equals(state.status)) {
                        state.poll.cancel(false);
                        if (state.artifact != null && callback != null) callback.onArtifactReady(state.sessionId, state.artifact);
                    }
                }
            } catch (Exception e) {
                log.warn("Video poll failed for taskId={}: {}", taskId, e.getMessage());
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    private void pruneExpiredTasks() {
        long cutoff = System.currentTimeMillis() - taskRetentionMillis;
        tasks.forEach((id, state) -> {
            if (state.submittedAt < cutoff && tasks.remove(id, state) && state.poll != null) state.poll.cancel(false);
        });
    }

    @Override public void close() {
        scheduler.shutdownNow();
        tasks.entrySet().removeIf(entry -> entry.getValue().owner == this);
    }

    /**
     * Poll the video generation API for task status.
     * Returns JSON with status, and artifacts if completed.
     */
    private ToolOutput pollTaskStatus(String taskId) {
        TaskState state = tasks.get(taskId);
        if (state == null) throw new ToolExecutionException("video_generation", "Video task is unavailable");
        String statusUrl = baseUrl + statusPath + "/" + taskId;
        Request request = new Request.Builder()
                .url(statusUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .get()
                .build();

        try (Response response = http.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("Status API returned " + response.code());
            }

            JsonNode resultNode = mapper.readTree(responseBody);
            String status = resultNode.has("status") ? resultNode.get("status").asText() : "unknown";

            ObjectNode result = mapper.createObjectNode();
            result.put("status", status);
            result.put("task_id", taskId);
            state.status = status;
            List<Artifact> artifacts = new java.util.ArrayList<>();

            if ("completed".equals(status)) {
                // Download video
                String videoUrl = resultNode.has("video_url") ? resultNode.get("video_url").asText() : null;
                if (videoUrl != null) {
                    byte[] videoBytes = downloadFile(videoUrl);
                    String fileName = "video-" + System.currentTimeMillis() + ".mp4";
                    Artifact artifact = storer.store(videoBytes, fileName, "video/mp4", state.sessionId);

                    state.artifact = artifact;
                    artifacts.add(artifact);
                }
            }

            return ToolOutput.artifacts(mapper.writeValueAsString(result), artifacts);
        } catch (Exception e) {
            throw new RuntimeException("Poll failed: " + e.getMessage(), e);
        }
    }

    private byte[] downloadFile(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Download failed: HTTP " + response.code());
            }
            return response.body().bytes();
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    public static final class TaskState {
        private final VideoGenerationTool owner;
        final String sessionId;
        volatile ScheduledFuture<?> poll;
        final long submittedAt;
        volatile String status = "processing";
        volatile Artifact artifact;

        private TaskState(VideoGenerationTool owner, String sessionId, long submittedAt) {
            this.owner = owner;
            this.sessionId = sessionId;
            this.submittedAt = submittedAt;
        }
    }
}
