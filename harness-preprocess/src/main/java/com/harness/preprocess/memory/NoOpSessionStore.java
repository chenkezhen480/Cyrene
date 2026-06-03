package com.harness.preprocess.memory;

import com.harness.core.model.Session;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * No-op session store. Used when HARNESS_MEMORY_STORE=none.
 */
public class NoOpSessionStore implements SessionStore {
    @Override public Session create(String userId) { return null; }
    @Override public Optional<Session> findActive(String sessionId) { return Optional.empty(); }
    @Override public List<Session> findActiveByUser(String userId) { return List.of(); }
    @Override public List<Session> findTimedOut(Duration timeout) { return List.of(); }
    @Override public void close(String sessionId, Session.SessionStatus status) {}
    @Override public void updateLastActive(String sessionId) {}
    @Override public void markRefinementStatus(String sessionId, String status) {}
    @Override public boolean claimForRefinement(String sessionId) { return false; }
    @Override public List<Session> findStuckRefinements(java.time.Duration stuckThreshold) { return List.of(); }
    @Override public void resetRefinementToPending(String sessionId) {}
    @Override public Optional<Session> findById(String sessionId) { return Optional.empty(); }
    @Override public List<Session> findAll(String userId, Session.SessionStatus status, Instant cursor, int limit) { return List.of(); }
}
