package com.harness.input.memory;

import com.harness.core.model.PageResponse;
import com.harness.core.model.Session;
import com.harness.core.model.SessionCursor;

import java.time.Duration;
import java.util.Optional;

/** Persistence boundary for owner-scoped conversation sessions. */
public interface SessionStore {

    Session create(String userId, String tenantId);

    Optional<Session> findActiveByOwner(String sessionId, String userId, String tenantId);

    Optional<Session> findByIdAndOwner(String sessionId, String userId, String tenantId);

    /** Internal background work only; HTTP/request code must use an owner-scoped lookup. */
    Optional<Session> findByIdForInternalTask(String sessionId);

    PageResponse<Session> findTimedOut(Duration timeout, SessionCursor cursor, int limit);

    PageResponse<Session> findTimedOutByOwner(
            String userId,
            String tenantId,
            Duration timeout,
            SessionCursor cursor,
            int limit);

    PageResponse<Session> findAllByOwner(
            String userId,
            String tenantId,
            Session.SessionStatus status,
            SessionCursor cursor,
            int limit);

    void close(String sessionId, Session.SessionStatus status);

    void updateLastActive(String sessionId);

    void updateTitle(String sessionId, String title);

}
