package com.harness.server;

import com.harness.core.model.MemoryMessage;
import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.core.model.Session;
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
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SessionHandler {

    private static final Logger log = LoggerFactory.getLogger(SessionHandler.class);
    private final SessionStore sessionStore;
    private final MessageStore messageStore;
    private final SessionMessageCache cache;
    private final MessageWriteWorker messageWriteWorker;

    public SessionHandler(SessionStore sessionStore, MessageStore messageStore,
                          SessionMessageCache cache, MessageWriteWorker messageWriteWorker) {
        this.sessionStore = sessionStore;
        this.messageStore = messageStore;
        this.cache = cache;
        this.messageWriteWorker = messageWriteWorker;
    }

    /**
     * POST /api/sessions — Create a new session.
     */
    public void create(Context ctx) {
        Map<String, String> body = ctx.bodyAsClass(Map.class);
        String userId = body.get("userId");
        if (userId == null || userId.isBlank()) {
            ApiResponses.error(
                    ctx, 400, ApiErrorCode.INVALID_REQUEST, "userId is required");
            return;
        }
        String title = body.get("title");
        Session session = sessionStore.create(userId);
        if (title != null && !title.isBlank()) {
            sessionStore.updateTitle(session.id(), title.trim());
            session = sessionStore.findById(session.id()).orElse(session);
        }
        log.debug("[Server] Created session {} for user {}, title={}", session.id(), userId, session.title());
        ctx.status(201).json(session);
    }

    /**
     * GET /api/sessions — List sessions with cursor-based pagination.
     * Query params: userId, status, limit, cursor
     */
    public void list(Context ctx) {
        String userId = ctx.queryParam("userId");
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

        Instant cursor = null;
        if (cursorParam != null && !cursorParam.isBlank()) {
            try {
                cursor = Instant.parse(cursorParam);
            } catch (DateTimeParseException e) {
                ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST,
                        "Invalid cursor format. Use ISO-8601 (e.g., 2026-06-01T10:00:00Z)");
                return;
            }
        }

        List<Session> sessions = sessionStore.findAll(userId, status, cursor, limit + 1);
        ctx.json(PageResponse.fromFetched(
                sessions, limit, session -> session.lastActive().toString()));
    }

    /**
     * GET /api/sessions/{sessionId} — Get session detail.
     */
    public void detail(Context ctx) {
        String sessionId = ctx.pathParam("sessionId");
        Optional<Session> session = sessionStore.findById(sessionId);
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
        if (sessionStore.findById(sessionId).isEmpty()) {
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
        Optional<Session> sessionOpt = sessionStore.findById(sessionId);
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
        if (sessionStore.findById(sessionId).isEmpty()) {
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

            // Diagnostic: count messages before delete
            int existingCount = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM messages WHERE session_id = ?")) {
                ps.setString(1, sessionId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) existingCount = rs.getInt(1);
            }
            if (existingCount == 0) {
                log.warn("[Server] Session {} has 0 messages in DB before delete — possible stale UI or write failure", sessionId);
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

        } catch (SQLException e) {
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
}
