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
}
