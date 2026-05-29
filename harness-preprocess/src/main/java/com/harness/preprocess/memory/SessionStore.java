package com.harness.preprocess.memory;

import com.harness.core.model.Session;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Persistence interface for conversation sessions.
 */
public interface SessionStore {
    Session create(String userId);
    Optional<Session> findActive(String sessionId);
    List<Session> findActiveByUser(String userId);
    List<Session> findTimedOut(Duration timeout);
    void close(String sessionId, Session.SessionStatus status);
    void updateLastActive(String sessionId);

    /**
     * Update the refinement_status column for a session.
     */
    void markRefinementStatus(String sessionId, String status);

    /**
     * Atomically claim a session for refinement using CAS (compare-and-swap).
     * Only succeeds if the session's current refinement_status is 'pending'.
     *
     * @return true if the session was successfully claimed (status changed from 'pending' to 'in_progress')
     */
    boolean claimForRefinement(String sessionId);

    /**
     * Find sessions stuck in 'in_progress' refinement status whose last_active is older than the given threshold.
     * These are sessions where refinement was interrupted (e.g., crash mid-refinement) and needs to be retried.
     *
     * @param stuckThreshold duration after which an 'in_progress' session is considered stuck
     * @return list of stuck sessions (id, userId populated)
     */
    List<Session> findStuckRefinements(Duration stuckThreshold);

    /**
     * Reset refinement_status from 'in_progress' to 'pending' for a given session.
     */
    void resetRefinementToPending(String sessionId);
}
