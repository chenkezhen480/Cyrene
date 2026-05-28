package com.harness.audit.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.harness.core.model.AgentTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        try {
            Files.list(traceDir)
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
    public int cleanup(int retentionDays) {
        Instant cutoff = Instant.now().minusSeconds(retentionDays * 86400L);
        int deleted = 0;
        try {
            var files = Files.list(traceDir).filter(p -> p.toString().endsWith(".json")).toList();
            for (Path f : files) {
                if (Files.getLastModifiedTime(f).toInstant().isBefore(cutoff)) {
                    Files.delete(f);
                    deleted++;
                }
            }
        } catch (IOException e) {
            log.error("Cleanup failed: {}", e.getMessage(), e);
        }
        return deleted;
    }

    @Override
    public void close() {}
}
