package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.Artifact;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;
import com.harness.tool.ArtifactProducingTool;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Built-in tool for executing Python scripts in a Docker sandbox.
 * Scripts write output files to /workspace/output/, which are collected as artifacts.
 *
 * Security: --network=none, --read-only, --memory, --pids-limit=50, tmpfs output size limit.
 */
public class PythonSandboxTool implements ArtifactProducingTool {

    private static final Logger log = LoggerFactory.getLogger(PythonSandboxTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_OUTPUT_BYTES = 64 * 1024; // 64KB truncation for stdout/stderr

    private static final Semaphore CONCURRENT_SANDBOXES;

    static {
        int maxConcurrent = EnvConfig.get().getInt(EnvKey.SANDBOX_MAX_CONCURRENT, 3);
        CONCURRENT_SANDBOXES = new Semaphore(maxConcurrent);
    }

    /**
     * Callback interface for storing artifacts.
     * Keeps artifact lookup behind the core store contract.
     */
    @FunctionalInterface
    public interface ArtifactStorer {
        Artifact storeFromPath(Path source, String name, String mimeType, String sessionId);
    }

    /**
     * Callback interface for looking up existing artifacts (for input_artifact_ids).
     */
    @FunctionalInterface
    public interface ArtifactLookup {
        Optional<Artifact> get(String id);
    }

    private final ArtifactStorer storer;
    private final ArtifactLookup lookup;
    private final String dockerImage;
    private final int defaultTimeout;
    private final int defaultMemoryMb;

    public PythonSandboxTool(ArtifactStorer storer, ArtifactLookup lookup) {
        this.storer = storer;
        this.lookup = lookup;
        this.dockerImage = EnvConfig.get().getString(EnvKey.SANDBOX_DOCKER_IMAGE, "cyrene-sandbox");
        this.defaultTimeout = EnvConfig.get().getInt(EnvKey.SANDBOX_TIMEOUT_SECONDS, 120);
        this.defaultMemoryMb = EnvConfig.get().getInt(EnvKey.SANDBOX_MEMORY_MB, 512);
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "python_sandbox",
                "Execute Python code in an isolated Docker sandbox. " +
                "Output files written to /workspace/output/ become downloadable artifacts. " +
                "Input files can be passed via input_artifact_ids (available in /workspace/input/). " +
                "Do NOT use os.system, subprocess, eval, exec (sandbox has no network). " +
                "Do NOT write to paths outside /workspace/output/ (root filesystem is read-only).",
                mapper.createObjectNode()
                        .put("type", "object")
                        .<ObjectNode>set("properties", mapper.createObjectNode()
                                .<ObjectNode>set("script", mapper.createObjectNode()
                                        .put("type", "string")
                                        .put("description", "Python code to execute. Write output files to /workspace/output/."))
                                .<ObjectNode>set("input_artifact_ids", ((ObjectNode) mapper.createObjectNode()
                                        .put("type", "array")
                                        .set("items", mapper.createObjectNode().put("type", "string")))
                                        .put("description", "Artifact IDs to copy into /workspace/input/ before execution. Files are available by their original names."))
                                .<ObjectNode>set("timeout_seconds", mapper.createObjectNode()
                                        .put("type", "integer")
                                        .put("description", "Execution timeout in seconds (default: " + defaultTimeout + ")"))
                                .<ObjectNode>set("memory_limit_mb", mapper.createObjectNode()
                                        .put("type", "integer")
                                        .put("description", "Memory limit in MB (default: " + defaultMemoryMb + ")")))
                        .<ObjectNode>set("required", mapper.createArrayNode().add("script"))
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        String script = arguments.has("script") ? arguments.get("script").asText() : null;
        if (script == null || script.isBlank()) {
            throw new ToolExecutionException("python_sandbox", "Missing required parameter: script");
        }

        int timeout = arguments.has("timeout_seconds") ? arguments.get("timeout_seconds").asInt(defaultTimeout) : defaultTimeout;
        int memoryMb = arguments.has("memory_limit_mb") ? arguments.get("memory_limit_mb").asInt(defaultMemoryMb) : defaultMemoryMb;

        // Acquire concurrency semaphore
        boolean acquired;
        try {
            acquired = CONCURRENT_SANDBOXES.tryAcquire(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolExecutionException("python_sandbox", "Interrupted waiting for sandbox slot");
        }
        if (!acquired) {
            throw new ToolExecutionException("python_sandbox", "Too many concurrent sandbox executions, try again later");
        }

        Path workDir = null;
        try {
            // Create temporary working directory
            workDir = Files.createTempDirectory("sandbox-");
            Path scriptFile = workDir.resolve("script.py");
            Files.writeString(scriptFile, script);

            // Create output directory
            Files.createDirectories(workDir.resolve("output"));

            // Copy input artifacts if specified
            if (arguments.has("input_artifact_ids") && arguments.get("input_artifact_ids").isArray()) {
                Path inputDir = workDir.resolve("input");
                Files.createDirectories(inputDir);
                for (JsonNode idNode : arguments.get("input_artifact_ids")) {
                    String artifactId = idNode.asText();
                    Optional<Artifact> artifactOpt = lookup.get(artifactId);
                    if (artifactOpt.isPresent()) {
                        Artifact artifact = artifactOpt.get();
                        Path source = Path.of(artifact.filePath());
                        if (Files.exists(source)) {
                            Files.copy(source, inputDir.resolve(artifact.name()), StandardCopyOption.REPLACE_EXISTING);
                        }
                    } else {
                        log.warn("Input artifact not found: {}", artifactId);
                    }
                }
            }

            // Build docker command
            String hostPath = toDockerPath(workDir.toAbsolutePath().toString());
            List<String> command = List.of(
                    "docker", "run", "--rm",
                    "--memory=" + memoryMb + "m",
                    "--cpus=1",
                    "--pids-limit=50",
                    "--network=none",
                    "--read-only",
                    "--tmpfs", "/tmp:size=100m",
                    "-v", hostPath + ":/workspace",
                    dockerImage,
                    "python", "/workspace/script.py"
            );

            log.debug("Sandbox executing: {} (timeout={}s, memory={}MB)", dockerImage, timeout, memoryMb);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // Read stdout and stderr in parallel
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread stdoutThread = new Thread(() -> readStream(process.getInputStream(), stdout));
            Thread stderrThread = new Thread(() -> readStream(process.getErrorStream(), stderr));
            stdoutThread.start();
            stderrThread.start();

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                stdoutThread.join(2000);
                stderrThread.join(2000);
                throw new ToolExecutionException("python_sandbox",
                        "Execution timed out after " + timeout + " seconds. Process killed.\n" +
                        "Stdout (truncated):\n" + truncate(stdout.toString()) + "\n" +
                        "Stderr (truncated):\n" + truncate(stderr.toString()));
            }

            stdoutThread.join(5000);
            stderrThread.join(5000);

            int exitCode = process.exitValue();
            String stdoutStr = truncate(stdout.toString());
            String stderrStr = truncate(stderr.toString());

            // Collect output files
            Path outputDir = workDir.resolve("output");
            List<Map<String, Object>> artifactList = new ArrayList<>();
            if (Files.exists(outputDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(outputDir)) {
                    for (Path file : stream) {
                        if (Files.isRegularFile(file)) {
                            String fileName = file.getFileName().toString();
                            long fileSize = Files.size(file);
                            log.info("[Sandbox] Found output file: {} ({}KB)", fileName, fileSize / 1024);
                            String mimeType = inferMimeType(fileName);
                            // Copy file to a temp location before workDir cleanup
                            Path tempCopy = Files.createTempFile("sandbox-artifact-", "-" + fileName);
                            Files.copy(file, tempCopy, StandardCopyOption.REPLACE_EXISTING);
                            try {
                                Artifact artifact = storer.storeFromPath(tempCopy, fileName, mimeType, null);
                                artifactList.add(Map.of(
                                        "id", artifact.id(),
                                        "name", artifact.name(),
                                        "mimeType", artifact.mimeType() != null ? artifact.mimeType() : "",
                                        "sizeBytes", artifact.sizeBytes(),
                                        "downloadUrl", artifact.downloadUrl()
                                ));
                            } catch (Exception e) {
                                log.warn("Failed to store artifact {}: {}", fileName, e.getMessage());
                                try { Files.deleteIfExists(tempCopy); } catch (IOException ignored) {}
                            }
                        }
                    }
                }
            } else {
                log.warn("[Sandbox] Output directory does not exist: {}", outputDir);
            }

            // Build result JSON
            ObjectNode result = mapper.createObjectNode();
            if (exitCode == 0) {
                result.put("success", true);
            } else {
                result.put("success", false);
                result.put("exitCode", exitCode);
            }
            result.put("stdout", stdoutStr);
            if (!stderrStr.isBlank()) {
                result.put("stderr", stderrStr);
            }
            result.set("artifacts", mapper.valueToTree(artifactList));

            if (exitCode != 0) {
                log.warn("Sandbox exited with code {}: stdout={}, stderr={}", exitCode, stdoutStr.length(), stderrStr.length());
            } else {
                log.info("Sandbox completed: {} artifacts produced", artifactList.size());
            }

            // Declare explicit status for Inspector
            if (stdoutStr.isBlank() && artifactList.isEmpty()) {
                ToolResult.setCurrentStatus(ToolResult.ResultStatus.EMPTY);
            } else {
                ToolResult.setCurrentStatus(ToolResult.ResultStatus.SUCCESS);
            }

            return mapper.writeValueAsString(result);

        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException("python_sandbox", "Sandbox execution failed: " + e.getMessage(), e);
        } finally {
            CONCURRENT_SANDBOXES.release();
            // Cleanup temp directory
            if (workDir != null) {
                try (Stream<Path> walk = Files.walk(workDir)) {
                    walk.sorted(Comparator.reverseOrder())
                            .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
                } catch (IOException ignored) {}
            }
        }
    }

    private void readStream(InputStream is, StringBuilder sb) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
                // Early truncation: if buffer exceeds 2x limit, stop reading
                if (sb.length() > MAX_OUTPUT_BYTES * 2) {
                    sb.append("... (output truncated)\n");
                    break;
                }
            }
        } catch (IOException ignored) {}
    }

    private String truncate(String text) {
        if (text.length() <= MAX_OUTPUT_BYTES) return text;
        return text.substring(text.length() - MAX_OUTPUT_BYTES);
    }

    /**
     * Convert a host path to Docker-compatible mount path.
     * On Windows: C:\Users\... → /c/Users/...
     * On Linux/Mac: returns as-is.
     */
    private static String toDockerPath(String path) {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            // Convert Windows path: C:\Users\foo → /c/Users/foo
            String normalized = path.replace('\\', '/');
            if (normalized.length() >= 2 && normalized.charAt(1) == ':') {
                return "/" + Character.toLowerCase(normalized.charAt(0)) + normalized.substring(2);
            }
        }
        return path;
    }

    private String inferMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg"))  return "image/svg+xml";
        if (lower.endsWith(".pdf"))  return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".csv"))  return "text/csv";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".xml"))  return "application/xml";
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".md"))   return "text/markdown";
        if (lower.endsWith(".txt"))  return "text/plain";
        if (lower.endsWith(".py"))   return "text/x-python";
        if (lower.endsWith(".js"))   return "text/javascript";
        if (lower.endsWith(".ts"))   return "text/typescript";
        if (lower.endsWith(".java")) return "text/x-java";
        if (lower.endsWith(".mp4"))  return "video/mp4";
        if (lower.endsWith(".mp3"))  return "audio/mpeg";
        if (lower.endsWith(".wav"))  return "audio/wav";
        return "application/octet-stream";
    }
}
