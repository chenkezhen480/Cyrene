package com.harness.trace.store;

import com.harness.core.model.AgentTrace;
import com.harness.core.model.PageResponse;
import com.harness.core.model.TraceCursor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Interface for trace persistence.
 * Implementations: MySQL, SQLite, file-based.
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

    /** Stable newest-first pagination scoped to one session. */
    PageResponse<AgentTrace> findBySession(String sessionId, TraceCursor cursor, int limit);

    default PageResponse<AgentTrace> findByOwner(String userId, String tenantId, TraceCursor cursor, int limit) {
        throw new UnsupportedOperationException("Owner-scoped Trace queries require a supporting Trace store");
    }

    default int countByOwner(String userId, String tenantId) {
        throw new UnsupportedOperationException("Owner-scoped Trace statistics require a supporting Trace store");
    }

    default int cleanupByOwner(String userId, String tenantId, int retentionDays) {
        throw new UnsupportedOperationException("Owner-scoped Trace cleanup requires a supporting Trace store");
    }

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
     * @return true only when the trace existed and the update was persisted
     */
    boolean updateMetadata(String traceId, Map<String, String> entries);

    /**
     * Close the store (release connections).
     */
    void close();

}
