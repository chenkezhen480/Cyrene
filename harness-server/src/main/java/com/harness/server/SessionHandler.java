package com.harness.server;

import com.harness.core.model.MemoryMessage;
import com.harness.core.model.Session;
import com.harness.preprocess.memory.MessageStore;
import com.harness.preprocess.memory.SessionMessageCache;
import com.harness.preprocess.memory.SessionStore;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public SessionHandler(SessionStore sessionStore, MessageStore messageStore,
                          SessionMessageCache cache) {
        this.sessionStore = sessionStore;
        this.messageStore = messageStore;
        this.cache = cache;
    }

    /**
     * POST /api/sessions — Create a new session.
     */
    public void create(Context ctx) {
        Map<String, String> body = ctx.bodyAsClass(Map.class);
        String userId = body.get("userId");
        if (userId == null || userId.isBlank()) {
            ctx.status(400).json(Map.of("error", "userId is required"));
            return;
        }
        String title = body.get("title");
        Session session = sessionStore.create(userId);
        if (title != null && !title.isBlank()) {
            sessionStore.updateTitle(session.id(), title.trim());
            session = sessionStore.findById(session.id()).orElse(session);
        }
        log.info("[Server] Created session {} for user {}, title={}", session.id(), userId, session.title());
        ctx.status(201).json(session);
    }

    /**
     * GET /api/sessions — List sessions with cursor-based pagination.
     * Query params: userId, status, limit, cursor
     */
    public void list(Context ctx) {
        String userId = ctx.queryParam("userId");
        String statusParam = ctx.queryParam("status");
        String limitParam = ctx.queryParam("limit");
        String cursorParam = ctx.queryParam("cursor");

        int limit = 20;
        if (limitParam != null) {
            try {
                limit = Integer.parseInt(limitParam);
                limit = Math.min(Math.max(limit, 1), 100);
            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "Invalid limit: " + limitParam));
                return;
            }
        }

        Session.SessionStatus status = null;
        if (statusParam != null && !statusParam.isBlank()) {
            try {
                status = Session.SessionStatus.valueOf(statusParam);
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", "Invalid status: " + statusParam + ". Use: active, ended, timeout"));
                return;
            }
        }

        Instant cursor = null;
        if (cursorParam != null && !cursorParam.isBlank()) {
            try {
                cursor = Instant.parse(cursorParam);
            } catch (DateTimeParseException e) {
                ctx.status(400).json(Map.of("error", "Invalid cursor format. Use ISO-8601 (e.g., 2026-06-01T10:00:00Z)"));
                return;
            }
        }

        List<Session> sessions = sessionStore.findAll(userId, status, cursor, limit + 1);
        boolean hasMore = sessions.size() > limit;
        if (hasMore) {
            sessions = sessions.subList(0, limit);
        }

        String nextCursor = null;
        if (hasMore && !sessions.isEmpty()) {
            nextCursor = sessions.get(sessions.size() - 1).lastActive().toString();
        }

        ctx.json(Map.of(
                "sessions", sessions,
                "nextCursor", nextCursor != null ? nextCursor : "",
                "hasMore", hasMore
        ));
    }

    /**
     * GET /api/sessions/{sessionId} — Get session detail.
     */
    public void detail(Context ctx) {
        String sessionId = ctx.pathParam("sessionId");
        Optional<Session> session = sessionStore.findById(sessionId);
        if (session.isEmpty()) {
            ctx.status(404).json(Map.of("error", "Session not found: " + sessionId));
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
            ctx.status(404).json(Map.of("error", "Session not found: " + sessionId));
            return;
        }

        String limitParam = ctx.queryParam("limit");
        String cursorParam = ctx.queryParam("cursor");
        String directionParam = ctx.queryParam("direction");

        int limit = 50;
        if (limitParam != null) {
            try {
                limit = Integer.parseInt(limitParam);
                limit = Math.min(Math.max(limit, 1), 200);
            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "Invalid limit: " + limitParam));
                return;
            }
        }

        long cursor = 0;
        if (cursorParam != null && !cursorParam.isBlank()) {
            try {
                cursor = Long.parseLong(cursorParam);
            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "Invalid cursor: " + cursorParam));
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
            messages = messages.subList(0, limit);
        }

        Long nextCursor = null;
        if (hasMore && !messages.isEmpty()) {
            nextCursor = messages.get(messages.size() - 1).id();
        }

        ctx.json(Map.of(
                "messages", messages,
                "nextCursor", nextCursor != null ? nextCursor : 0,
                "hasMore", hasMore
        ));
    }

    /**
     * GET /api/sessions/{sessionId}/stats — Session statistics.
     */
    public void stats(Context ctx) {
        String sessionId = ctx.pathParam("sessionId");
        Optional<Session> sessionOpt = sessionStore.findById(sessionId);
        if (sessionOpt.isEmpty()) {
            ctx.status(404).json(Map.of("error", "Session not found: " + sessionId));
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
     * DELETE /api/sessions/{sessionId} — Close/delete a session.
     */
    public void delete(Context ctx) {
        String sessionId = ctx.pathParam("sessionId");
        Optional<Session> session = sessionStore.findById(sessionId);
        if (session.isEmpty()) {
            ctx.status(404).json(Map.of("error", "Session not found: " + sessionId));
            return;
        }
        if (session.get().status() != Session.SessionStatus.active) {
            ctx.status(400).json(Map.of("error", "Session is already closed with status: " + session.get().status()));
            return;
        }
        sessionStore.close(sessionId, Session.SessionStatus.ended);
        cache.remove(sessionId); // triggers onEvict → skillRegistry.clearSession()
        log.info("[Server] Closed session {}", sessionId);
        ctx.json(Map.of("message", "Session closed", "sessionId", sessionId));
    }
}
