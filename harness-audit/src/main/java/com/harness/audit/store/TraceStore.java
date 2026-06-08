package com.harness.audit.store;

import com.harness.core.model.AgentTrace;

import java.util.List;
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
     * Close the store (release connections).
     */
    void close();
}
