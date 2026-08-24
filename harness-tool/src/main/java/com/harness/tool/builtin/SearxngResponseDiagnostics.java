package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Bounded diagnostics extracted from a SearXNG JSON response. */
final class SearxngResponseDiagnostics {

    private SearxngResponseDiagnostics() {
    }

    static Snapshot parse(JsonNode root) {
        int resultCount = root.path("results").isArray() ? root.path("results").size() : 0;
        List<EngineFailure> failures = new ArrayList<>();
        JsonNode unresponsiveEngines = root.path("unresponsive_engines");
        if (unresponsiveEngines.isArray()) {
            for (JsonNode failure : unresponsiveEngines) {
                if (failures.size() >= 20) {
                    break;
                }
                if (failure.isArray()) {
                    failures.add(new EngineFailure(
                            failure.path(0).asText("unknown"),
                            failure.path(1).asText("unknown")));
                } else if (failure.isObject()) {
                    failures.add(new EngineFailure(
                            failure.path("engine").asText("unknown"),
                            failure.path("error").asText("unknown")));
                } else if (failure.isTextual()) {
                    failures.add(new EngineFailure(failure.asText(), "unknown"));
                }
            }
        }
        return new Snapshot(resultCount, List.copyOf(failures));
    }

    static boolean allConfiguredEnginesFailed(
            Snapshot snapshot,
            List<String> configuredEngines
    ) {
        if (snapshot.resultCount() > 0 || configuredEngines.isEmpty()) {
            return false;
        }
        Set<String> failedEngines = new HashSet<>();
        for (EngineFailure failure : snapshot.unresponsiveEngines()) {
            failedEngines.add(failure.engine().trim().toLowerCase(Locale.ROOT));
        }
        return configuredEngines.stream()
                .map(engine -> engine.trim().toLowerCase(Locale.ROOT))
                .allMatch(failedEngines::contains);
    }

    record Snapshot(int resultCount, List<EngineFailure> unresponsiveEngines) {
    }

    record EngineFailure(String engine, String error) {
    }
}
