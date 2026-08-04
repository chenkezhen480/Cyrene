package com.harness.trace.store;

import com.harness.core.model.AgentTrace;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Interface for trace persistence.
 * Implementations: SQLite, PostgreSQL, file-based.
 */
public interface TraceStore {

    /**
     * Save a completed trace.
     */
    void save(AgentTrace trace);

    /**
     * Retrieve a trace by ID.
     */
    Optional<AgentTrace> findById(String traceId);

    /**
     * List recent traces.
     */
    List<AgentTrace> listRecent(int limit);

    /**
     * Delete traces older than the given number of days.
     */
    int cleanup(int retentionDays);

    /**
     * Delete a specific trace by ID.
     * @return true if the trace was found and deleted
     */
    boolean deleteById(String traceId);

    /**
     * Return total number of traces stored.
     */
    int count();

    /**
     * Update specific metadata fields of an existing trace.
     * Merges the given entries into the trace's existing metadata map.
     * Used for post-write updates (e.g., user feedback via thumbs up/down).
     *
     * @param traceId the trace to update
     * @param entries metadata key-value pairs to merge
     */
    void updateMetadata(String traceId, Map<String, String> entries);

    /**
     * Close the store (release connections).
     */
    void close();
}
