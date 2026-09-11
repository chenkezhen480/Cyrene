package com.harness.input.memory;

import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.core.model.Session;
import com.harness.core.model.SessionCursor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public class NoOpSessionStore implements SessionStore {
    @Override public Session create(String userId, String tenantId) { return null; }
    @Override public Optional<Session> findActiveByOwner(
            String sessionId, String userId, String tenantId) { return Optional.empty(); }
    @Override public Optional<Session> findByIdAndOwner(
            String sessionId, String userId, String tenantId) { return Optional.empty(); }
    @Override public Optional<Session> findByIdForInternalTask(String sessionId) {
        return Optional.empty();
    }
    @Override public PageResponse<Session> findTimedOut(
            Duration timeout, SessionCursor cursor, int limit) { return empty(limit); }
    @Override public PageResponse<Session> findTimedOutByOwner(
            String userId, String tenantId, Duration timeout, SessionCursor cursor, int limit) {
        return empty(limit);
    }
    @Override public PageResponse<Session> findAllByOwner(
            String userId, String tenantId, Session.SessionStatus status,
            SessionCursor cursor, int limit) { return empty(limit); }
    @Override public void close(String sessionId, Session.SessionStatus status) { }
    @Override public void updateLastActive(String sessionId) { }
    @Override public void updateTitle(String sessionId, String title) { }
    private static PageResponse<Session> empty(int limit) {
        return new PageResponse<>(List.of(), new PageInfo(limit, "", false));
    }
}
