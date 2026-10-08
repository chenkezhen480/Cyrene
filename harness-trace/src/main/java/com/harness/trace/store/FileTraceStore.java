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
import java.util.Objects;
import java.util.function.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File-based trace store. Saves each trace as a JSON file.
 */
public class FileTraceStore implements TraceStore {

    private static final Logger log = LoggerFactory.getLogger(FileTraceStore.class);
    private final ObjectMapper mapper;
    private final Path traceDir;

    public FileTraceStore() {
        this(Path.of("harness_traces"));
    }

    FileTraceStore(Path traceDir) {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.traceDir = traceDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(traceDir);
        } catch (IOException e) {
            throw new TraceStoreException("Failed to create trace directory", e);
        }
    }

    @Override
    public synchronized void save(AgentTrace trace) {
        try {
            Path file = traceFile(trace.traceId());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), trace);
        } catch (IOException e) {
            log.error("Failed to save trace {}: {}", trace.traceId(), e.getMessage(), e);
            throw new TraceStoreException("Failed to save trace " + trace.traceId(), e);
        }
    }

    @Override
    public synchronized Optional<AgentTrace> findById(String traceId) {
        Path file = traceFile(traceId);
        try {
            return Optional.of(mapper.readValue(file.toFile(), AgentTrace.class));
        } catch (java.io.FileNotFoundException e) {
            if (Files.notExists(file)) return Optional.empty();
            throw new TraceStoreException("Failed to read trace " + traceId, e);
        } catch (IOException e) {
            throw new TraceStoreException("Failed to read trace " + traceId, e);
        }
    }

    @Override
    public List<AgentTrace> listRecent(int limit) {
        return page(trace -> true, null, limit).items();
    }

    @Override
    public PageResponse<AgentTrace> findBySession(String sessionId, TraceCursor cursor, int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        return page(trace -> sessionId.equals(trace.sessionId()), cursor, limit);
    }

    @Override
    public PageResponse<AgentTrace> findByOwner(String userId, String tenantId, TraceCursor cursor, int limit) {
        return page(owner(userId, tenantId), cursor, limit);
    }

    private synchronized PageResponse<AgentTrace> page(Predicate<AgentTrace> scope, TraceCursor cursor, int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        var order = java.util.Comparator.comparing(AgentTrace::timestamp).thenComparing(AgentTrace::traceId);
        var selected = new java.util.PriorityQueue<AgentTrace>(limit + 1, order);
        try (Stream<Path> paths = Files.list(traceDir)) {
            paths
                    .filter(path -> path.toString().endsWith(".json"))
                    .map(this::readRequired)
                    .filter(scope)
                    .filter(trace -> cursor == null
                            || trace.timestamp().isBefore(cursor.timestamp())
                            || (trace.timestamp().equals(cursor.timestamp())
                            && trace.traceId().compareTo(cursor.traceId()) < 0))
                    .forEach(trace -> {
                        if (selected.size() < limit + 1) selected.add(trace);
                        else if (order.compare(trace, selected.peek()) > 0) {
                            selected.remove();
                            selected.add(trace);
                        }
                    });
        } catch (IOException e) {
            throw new TraceStoreException("Failed to list traces", e);
        }
        return PageResponse.fromFetched(
                selected.stream().sorted(order.reversed()).toList(),
                limit,
                trace -> trace.timestamp() + "|" + trace.traceId());
    }

    private AgentTrace readRequired(Path path) {
        if (Files.isSymbolicLink(path)) throw new SecurityException("Trace links are forbidden");
        try {
            return mapper.readValue(path.toFile(), AgentTrace.class);
        } catch (IOException e) {
            throw new TraceStoreException("Failed to read trace file " + path.getFileName(), e);
        }
    }

    private Path traceFile(String traceId) {
        if (traceId == null || !traceId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid traceId");
        }
        Path file = traceDir.resolve(traceId + ".json");
        if (Files.isSymbolicLink(file)) throw new SecurityException("Trace links are forbidden");
        return file;
    }

    private static Predicate<AgentTrace> owner(String userId, String tenantId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        return trace -> userId.equals(trace.userId()) && Objects.equals(tenantId, trace.metadata().get("tenant_id"));
    }

    @Override
    public synchronized int countByOwner(String userId, String tenantId) {
        var scope = owner(userId, tenantId);
        // ponytail: file queries scan all JSON; use SQLite/MySQL when Trace volume grows.
        try (var paths = Files.list(traceDir)) {
            return Math.toIntExact(paths.filter(path -> path.toString().endsWith(".json"))
                    .map(this::readRequired).filter(scope).count());
        } catch (IOException e) {
            throw new TraceStoreException("Failed to count owned traces", e);
        }
    }

    @Override
    public synchronized int cleanupByOwner(String userId, String tenantId, int retentionDays) {
        if (retentionDays < 0) throw new IllegalArgumentException("retentionDays must not be negative");
        var scope = owner(userId, tenantId);
        var cutoff = Instant.now().minusSeconds(retentionDays * 86400L);
        try (var paths = Files.list(traceDir)) {
            var expired = paths.filter(path -> path.toString().endsWith(".json"))
                    .filter(path -> {
                        var trace = readRequired(path);
                        return scope.test(trace) && trace.timestamp().isBefore(cutoff);
                    }).toList();
            deleteFiles(expired);
            return expired.size();
        } catch (IOException e) {
            throw new TraceStoreException("Failed to cleanup owned traces", e);
        }
    }

    private void deleteFiles(List<Path> files) throws IOException {
        if (files.isEmpty()) return;
        Path staging = Files.createTempDirectory(traceDir, ".cleanup-");
        var moved = new ArrayList<Path>();
        try {
            for (Path file : files) {
                Files.move(file, staging.resolve(file.getFileName()), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                moved.add(file);
            }
        } catch (IOException failure) {
            for (Path file : moved.reversed()) {
                try { Files.move(staging.resolve(file.getFileName()), file, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
                catch (IOException rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        }
        // ponytail: staging failures roll back; use a database store for crash-atomic bulk cleanup.
        // All staged files are now logically deleted. Physical removal is post-commit cleanup.
        try {
            for (Path file : moved) Files.delete(staging.resolve(file.getFileName()));
            Files.delete(staging);
        } catch (IOException failure) {
            throw new TraceStoreException("Trace cleanup committed, but staged-file removal failed", failure);
        }
    }

    @Override
    public synchronized int cleanup(int retentionDays) {
        Instant cutoff = Instant.now().minusSeconds(retentionDays * 86400L);
        int deleted = 0;
        try (Stream<Path> paths = Files.list(traceDir)) {
            var files = paths.filter(p -> p.toString().endsWith(".json")).toList();
            for (Path f : files) {
                if (Files.getLastModifiedTime(f).toInstant().isBefore(cutoff)) {
                    Files.delete(f);
                    deleted++;
                }
            }
        } catch (IOException | RuntimeException e) {
            log.error("Cleanup failed: {}", e.getMessage(), e);
            throw new TraceStoreException("Failed to cleanup trace files", e);
        }
        return deleted;
    }

    @Override
    public synchronized boolean deleteById(String traceId) {
        Path file = traceFile(traceId);
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new TraceStoreException("Failed to delete trace " + traceId, e);
        }
    }

    @Override
    public synchronized int count() {
        try (var stream = Files.list(traceDir)) {
            return (int) stream.filter(p -> p.toString().endsWith(".json")).count();
        } catch (IOException e) {
            throw new TraceStoreException("Failed to count trace files", e);
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
