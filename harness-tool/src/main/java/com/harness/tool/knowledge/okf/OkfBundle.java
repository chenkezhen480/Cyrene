package com.harness.tool.knowledge.okf;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** In-memory exchange artifact. Runtime retrieval never reads these files. */
public record OkfBundle(
        OkfBundleScope scope,
        Instant generatedAt,
        Map<String, String> files,
        int conceptCount,
        int omittedSourceCount,
        boolean logTruncated
) {
    public OkfBundle {
        scope = Objects.requireNonNull(scope, "scope");
        generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
        files = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(files, "files")));
        if (!files.containsKey("index.md") || !files.containsKey("log.md")) {
            throw new IllegalArgumentException("OKF bundle requires index.md and log.md");
        }
        files.keySet().forEach(OkfBundle::validatePath);
        if (conceptCount < 0 || omittedSourceCount < 0) {
            throw new IllegalArgumentException("bundle counts must not be negative");
        }
    }

    private static void validatePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("\\")
                || path.contains("..") || path.contains(":")) {
            throw new IllegalArgumentException("Unsafe OKF bundle path: " + path);
        }
    }
}
