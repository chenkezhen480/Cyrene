package com.harness.server;

import com.harness.core.model.MemoryMessage;
import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.core.model.Session;
import com.harness.core.model.SessionCursor;
import com.harness.core.env.MysqlConnectionPool;
import com.harness.input.memory.MessageStore;
import com.harness.input.memory.MessageWriteWorker;
import com.harness.input.memory.SessionMessageCache;
import com.harness.input.memory.SessionStore;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SessionHandler {

    private static final Logger log = LoggerFactory.getLogger(SessionHandler.class);
    private final SessionStore sessionStore;
    private final MessageStore messageStore;
    private final SessionMessageCache cache;
    private final MessageWriteWorker messageWriteWorker;
    private final SessionRequestOwnerResolver ownerResolver;

    public SessionHandler(SessionStore sessionStore, MessageStore messageStore,
                          SessionMessageCache cache, MessageWriteWorker messageWriteWorker) {
        this(sessionStore, messageStore, cache, messageWriteWorker,
                new SessionRequestOwnerResolver());
    }

    SessionHandler(
            SessionStore sessionStore,
            MessageStore messageStore,
            SessionMessageCache cache,
            MessageWriteWorker messageWriteWorker,
            SessionRequestOwnerResolver ownerResolver
    ) {
        this.sessionStore = sessionStore;
        this.messageStore = messageStore;
        this.cache = cache;
        this.messageWriteWorker = messageWriteWorker;
        this.ownerResolver = ownerResolver;
    }

    /**
     * POST /api/sessions — Create a new session.
     */
    public void create(Context ctx) {
        Map<String, String> body = ctx.bodyAsClass(Map.class);
        String userId = body.get("userId");
        SessionRequestOwnerResolver.Owner owner = resolveOwner(
                ctx, userId, body.get("tenantId"));
        if (owner == null) {
            return;
        }
        String title = body.get("title");
        Session session = sessionStore.create(owner.userId(), owner.tenantId());
        if (title != null && !title.isBlank()) {
            sessionStore.updateTitle(session.id(), title.trim());
            session = sessionStore.findByIdAndOwner(
                    session.id(), owner.userId(), owner.tenantId()).orElse(session);
        }
        log.debug("[Server] Created session {} for user {}, title={}",
                session.id(), owner.userId(), session.title());
        ctx.status(201).json(session);
    }

    /**
     * GET /api/sessions — List sessions with cursor-based pagination.
     * Query params: userId, status, limit, cursor
     */
    public void list(Context ctx) {
        String userId = ctx.queryParam("userId");
        SessionRequestOwnerResolver.Owner owner = resolveOwner(
                ctx, userId, ctx.queryParam("tenantId"));
        if (owner == null) {
            return;
        }
        String statusParam = ctx.queryParam("status");
        String cursorParam = ctx.queryParam("cursor");

        int limit;
        try {
            limit = ApiRequestParameters.limit(ctx, 20, 100);
        } catch (IllegalArgumentException e) {
            ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST, e.getMessage());
            return;
        }

        Session.SessionStatus status = null;
        if (statusParam != null && !statusParam.isBlank()) {
            try {
                status = Session.SessionStatus.valueOf(statusParam);
            } catch (IllegalArgumentException e) {
                ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST,
                        "Invalid status: " + statusParam + ". Use: active, ended, timeout");
                return;
            }
        }

        SessionCursor cursor;
        try {
            cursor = parseSessionCursor(cursorParam);
        } catch (IllegalArgumentException e) {
            ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST, e.getMessage());
            return;
        }
        ctx.json(sessionStore.findAllByOwner(
                owner.userId(), owner.tenantId(), status, cursor, limit));
    }

    /**
     * GET /api/sessions/{sessionId} — Get session detail.
     */
    public void detail(Context ctx) {
        String sessionId = ctx.pathParam("sessionId");
        SessionRequestOwnerResolver.Owner owner = resolveOwnerFromQuery(ctx);
        if (owner == null) {
            return;
        }
        Optional<Session> session = sessionStore.findByIdAndOwner(
                sessionId, owner.userId(), owner.tenantId());
        if (session.isEmpty()) {
            ApiResponses.error(ctx, 404, ApiErrorCode.NOT_FOUND,
                    "Session not found: " + sessionId);
            return;
        }
        ctx.json(session.get());
    }

    /**
     * GET /api/sessions/{sessionId}/messages — Message history with cursor-based pagination.
     * Query params: limit, cursor, direction (asc/desc)
     */
    public void messages(Context ctx) {
        String sessionId = ctx.pathParam("sessionId");
        SessionRequestOwnerResolver.Owner owner = resolveOwnerFromQuery(ctx);
        if (owner == null) {
            return;
        }
        if (sessionStore.findByIdAndOwner(
                sessionId, owner.userId(), owner.tenantId()).isEmpty()) {
            ApiResponses.error(ctx, 404, ApiErrorCode.NOT_FOUND,
                    "Session not found: " + sessionId);
            return;
        }

        String cursorParam = ctx.queryParam("cursor");
        String directionParam = ctx.queryParam("direction");

        int limit;
        try {
            limit = ApiRequestParameters.limit(ctx, 50, 200);
        } catch (IllegalArgumentException e) {
            ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST, e.getMessage());
            return;
        }

        long cursor = 0;
        if (cursorParam != null && !cursorParam.isBlank()) {
            try {
                cursor = Long.parseLong(cursorParam);
            } catch (NumberFormatException e) {
                ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST,
                        "Invalid cursor: " + cursorParam);
                return;
            }
        }

        boolean ascending = true;
        if ("desc".equalsIgnoreCase(directionParam)) {
            ascending = false;
        }

        List<MemoryMessage> messages = messageStore.loadPage(sessionId, cursor, limit + 1, ascending);
        boolean hasMore = messages.size() > limit;
        if (hasMore) {
            messages = ascending
                    ? List.copyOf(messages.subList(0, limit))
                    : List.copyOf(messages.subList(messages.size() - limit, messages.size()));
        }

        String nextCursor = "";
        if (hasMore && !messages.isEmpty()) {
            long cursorId = ascending
                    ? messages.get(messages.size() - 1).id()
                    : messages.get(0).id();
            nextCursor = Long.toString(cursorId);
        }

        ctx.json(new PageResponse<>(
                messages, new PageInfo(limit, nextCursor, hasMore)));
    }

    /**
     * GET /api/sessions/{sessionId}/stats — Session statistics.
     */
    public void stats(Context ctx) {
        String sessionId = ctx.pathParam("sessionId");
        SessionRequestOwnerResolver.Owner owner = resolveOwnerFromQuery(ctx);
        if (owner == null) {
            return;
        }
        Optional<Session> sessionOpt = sessionStore.findByIdAndOwner(
                sessionId, owner.userId(), owner.tenantId());
        if (sessionOpt.isEmpty()) {
            ApiResponses.error(ctx, 404, ApiErrorCode.NOT_FOUND,
                    "Session not found: " + sessionId);
            return;
        }
        Session session = sessionOpt.get();

        int userCount = messageStore.countByRole(sessionId, "user");
        int assistantCount = messageStore.countByRole(sessionId, "assistant");
        int toolCount = messageStore.countToolMessages(sessionId);
        int turns = messageStore.countConversationTurns(sessionId);
        int avgReplyLen = messageStore.avgAssistantReplyLength(sessionId);
        int totalUserChars = messageStore.sumUserContentLength(sessionId);
        boolean hasQuestions = messageStore.hasUserQuestions(sessionId);

        Instant end = session.endedAt() != null ? session.endedAt() : Instant.now();
        long durationMinutes = Duration.between(session.createdAt(), end).toMinutes();

        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("sessionId", sessionId);
        result.put("userId", session.userId());
        result.put("userMessageCount", userCount);
        result.put("assistantMessageCount", assistantCount);
        result.put("toolMessageCount", toolCount);
        result.put("conversationTurns", turns);
        result.put("avgAssistantReplyLength", avgReplyLen);
        result.put("totalUserChars", totalUserChars);
        result.put("hasUserQuestions", hasQuestions);
        result.put("durationMinutes", durationMinutes);
        result.put("status", session.status().name());
        result.put("createdAt", session.createdAt().toString());
        result.put("lastActive", session.lastActive().toString());
        result.put("endedAt", session.endedAt() != null ? session.endedAt().toString() : "");
        ctx.json(result);
    }

    /**
     * DELETE /api/sessions/{sessionId} — Close session and delete all messages in a transaction.
     */
    public void delete(Context ctx) {
        String sessionId = ctx.pathParam("sessionId");
        SessionRequestOwnerResolver.Owner owner = resolveOwnerFromQuery(ctx);
        if (owner == null) {
            return;
        }
        if (sessionStore.findByIdAndOwner(
                sessionId, owner.userId(), owner.tenantId()).isEmpty()) {
            ApiResponses.error(ctx, 404, ApiErrorCode.NOT_FOUND,
                    "Session not found: " + sessionId);
            return;
        }

        // Flush any pending async writes before deletion
        int flushed = messageWriteWorker.flushPending();
        if (flushed > 0) {
            log.debug("[Server] Flushed {} pending messages before session delete", flushed);
        }

        // Transaction: delete session + messages atomically
        Connection conn = null;
        try {
            conn = MysqlConnectionPool.getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT id FROM sessions
                    WHERE id = ? AND user_id = ? AND tenant_id <=> ? FOR UPDATE
                    """)) {
                ps.setString(1, sessionId);
                ps.setString(2, owner.userId());
                ps.setString(3, owner.tenantId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("Session owner changed before delete");
                    }
                }
            }

            int deleted;
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM messages WHERE session_id = ?")) {
                ps.setString(1, sessionId);
                deleted = ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM sessions WHERE id = ?")) {
                ps.setString(1, sessionId);
                ps.executeUpdate();
            }

            conn.commit();
            cache.remove(sessionId); // triggers onEvict → skillRegistry.clearSession()
            log.info("[Server] Deleted session {} with {} messages", sessionId, deleted);
            ctx.json(Map.of("message", "Session deleted", "sessionId", sessionId, "messagesDeleted", deleted));

        } catch (SQLException | RuntimeException e) {
            log.error("[Server] Failed to delete session {}, rolling back: {}", sessionId, e.getMessage(), e);
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ex) { log.error("Rollback failed: {}", ex.getMessage()); }
            }
            ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR,
                    "Failed to delete session: " + e.getMessage());
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) {}
            }
        }
    }

    private SessionRequestOwnerResolver.Owner resolveOwnerFromQuery(Context context) {
        return resolveOwner(
                context,
                context.queryParam("userId"),
                context.queryParam("tenantId"));
    }

    private SessionRequestOwnerResolver.Owner resolveOwner(
            Context context,
            String userId,
            String tenantId
    ) {
        try {
            return ownerResolver.resolve(context, userId, tenantId);
        } catch (SessionRequestOwnerResolver.OwnerResolutionException e) {
            ApiResponses.error(context, 401, ApiErrorCode.UNAUTHORIZED, e.getMessage());
            return null;
        }
    }

    private static SessionCursor parseSessionCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        int separator = cursor.lastIndexOf('|');
        if (separator <= 0 || separator == cursor.length() - 1) {
            throw new IllegalArgumentException("Invalid Session cursor");
        }
        try {
            return new SessionCursor(
                    Instant.parse(cursor.substring(0, separator)),
                    cursor.substring(separator + 1));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid Session cursor", e);
        }
    }
}
