package com.harness.trace.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.harness.core.model.AgentTrace;
import com.harness.core.model.PageResponse;
import com.harness.core.model.TraceCursor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * File-based trace store. Saves each trace as a JSON file.
 */
public class FileTraceStore implements TraceStore {

    private static final Logger log = LoggerFactory.getLogger(FileTraceStore.class);
    private final ObjectMapper mapper;
    private final Path traceDir;

    public FileTraceStore() {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.traceDir = Path.of("harness_traces");
        try {
            Files.createDirectories(traceDir);
        } catch (IOException e) {
            log.error("Failed to create trace directory: {}", e.getMessage());
        }
    }

    @Override
    public void save(AgentTrace trace) {
        try {
            Path file = traceDir.resolve(trace.traceId() + ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), trace);
        } catch (IOException e) {
            log.error("Failed to save trace {}: {}", trace.traceId(), e.getMessage(), e);
            throw new TraceStoreException("Failed to save trace " + trace.traceId(), e);
        }
    }

    @Override
    public Optional<AgentTrace> findById(String traceId) {
        Path file = traceDir.resolve(traceId + ".json");
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(file.toFile(), AgentTrace.class));
        } catch (IOException e) {
            log.error("Failed to read trace {}: {}", traceId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    public List<AgentTrace> listRecent(int limit) {
        List<AgentTrace> traces = new ArrayList<>();
        try (Stream<Path> paths = Files.list(traceDir)) {
            paths
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted((a, b) -> {
                        try { return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)); }
                        catch (IOException e) { return 0; }
                    })
                    .limit(limit)
                    .forEach(p -> {
                        try { traces.add(mapper.readValue(p.toFile(), AgentTrace.class)); }
                        catch (IOException e) { log.warn("Failed to read trace file: {}", p); }
                    });
        } catch (IOException e) {
            log.error("Failed to list traces: {}", e.getMessage(), e);
        }
        return traces;
    }

    @Override
    public PageResponse<AgentTrace> findBySession(String sessionId, TraceCursor cursor, int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        List<AgentTrace> filtered;
        try (Stream<Path> paths = Files.list(traceDir)) {
            filtered = paths
                    .filter(path -> path.toString().endsWith(".json"))
                    .map(this::readRequired)
                    .filter(trace -> sessionId.equals(trace.sessionId()))
                    .filter(trace -> cursor == null
                            || trace.timestamp().isBefore(cursor.timestamp())
                            || (trace.timestamp().equals(cursor.timestamp())
                            && trace.traceId().compareTo(cursor.traceId()) < 0))
                    .sorted(java.util.Comparator.comparing(AgentTrace::timestamp)
                            .thenComparing(AgentTrace::traceId)
                            .reversed())
                    .limit((long) limit + 1)
                    .toList();
        } catch (IOException e) {
            throw new TraceStoreException("Failed to list traces for session " + sessionId, e);
        }
        return PageResponse.fromFetched(
                filtered,
                limit,
                trace -> trace.timestamp() + "|" + trace.traceId());
    }

    private AgentTrace readRequired(Path path) {
        try {
            return mapper.readValue(path.toFile(), AgentTrace.class);
        } catch (IOException e) {
            throw new TraceStoreException("Failed to read trace file " + path.getFileName(), e);
        }
    }

    @Override
    public CleanupResult cleanup(
            int retentionDays,
            Predicate<String> retainedByKnowledge
    ) {
        java.util.Objects.requireNonNull(retainedByKnowledge, "retainedByKnowledge");
        Instant cutoff = Instant.now().minusSeconds(retentionDays * 86400L);
        int deleted = 0;
        int retained = 0;
        try (Stream<Path> paths = Files.list(traceDir)) {
            var files = paths.filter(p -> p.toString().endsWith(".json")).toList();
            for (Path f : files) {
                if (Files.getLastModifiedTime(f).toInstant().isBefore(cutoff)) {
                    String fileName = f.getFileName().toString();
                    String traceId = fileName.substring(0, fileName.length() - ".json".length());
                    if (retainedByKnowledge.test(traceId)) {
                        retained++;
                        continue;
                    }
                    Files.delete(f);
                    deleted++;
                }
            }
        } catch (IOException | RuntimeException e) {
            log.error("Cleanup failed: {}", e.getMessage(), e);
            throw new TraceStoreException("Failed to cleanup trace files", e);
        }
        return new CleanupResult(deleted, retained);
    }

    @Override
    public boolean deleteById(String traceId) {
        Path file = traceDir.resolve(traceId + ".json");
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            log.error("Failed to delete trace {}: {}", traceId, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public int count() {
        try (var stream = Files.list(traceDir)) {
            return (int) stream.filter(p -> p.toString().endsWith(".json")).count();
        } catch (IOException e) {
            log.error("Failed to count traces: {}", e.getMessage(), e);
            return 0;
        }
    }

    @Override
    public synchronized boolean updateMetadata(String traceId, Map<String, String> entries) {
        // Read-modify-write: load JSON, merge metadata, save back
        Optional<AgentTrace> found = findById(traceId);
        if (found.isEmpty()) {
            return false;
        }
        AgentTrace trace = found.get();
        Map<String, String> merged = new HashMap<>(trace.metadata());
        merged.putAll(entries);
        AgentTrace updated = new AgentTrace(
                trace.traceId(), trace.timestamp(), trace.userId(), trace.sessionId(),
                trace.inputText(), trace.inputAttachments(), trace.intent(), trace.ragHits(),
                trace.rerankResult(), trace.llmModel(), trace.promptVersion(), trace.steps(),
                trace.finalOutput(), trace.riskLevel(), trace.userConfirmed(),
                trace.totalDurationMs(), trace.totalTokens(), merged);
        save(updated);
        return true;
    }

    @Override
    public void close() {}
}
