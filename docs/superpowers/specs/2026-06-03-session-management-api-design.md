# Session Management API Design

## Overview

Expose session lifecycle management through HTTP API endpoints. Internal infrastructure (SessionStore, MessageStore, SessionLifecycleManager, SessionMessageCache) is already complete — this design adds the HTTP layer only.

## Endpoints

### POST /api/sessions — Create Session

Explicitly create a session without sending a chat message.

**Request:**
```json
{
  "userId": "user123"
}
```

**Response (201):**
```json
{
  "id": "a1b2c3d4e5f6",
  "userId": "user123",
  "createdAt": "2026-06-03T10:00:00Z",
  "lastActive": "2026-06-03T10:00:00Z",
  "status": "active"
}
```

**Implementation:** `SessionStore.create(userId)` — already exists.

---

### GET /api/sessions — List Sessions

Cursor-based pagination over sessions. Supports optional filtering by userId and status.

**Query Parameters:**
| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `userId` | string | No | Filter by user. Omit for global query. |
| `status` | string | No | Filter: `active` / `ended` / `timeout`. Omit for all. |
| `limit` | int | No | Page size, default 20, max 100. |
| `cursor` | string | No | ISO-8601 timestamp cursor. Omit to start from newest. |

**Response:**
```json
{
  "sessions": [
    {
      "id": "a1b2c3d4e5f6",
      "userId": "user123",
      "createdAt": "2026-06-03T10:00:00Z",
      "lastActive": "2026-06-03T10:30:00Z",
      "endedAt": null,
      "status": "active",
      "refinementStatus": "done"
    }
  ],
  "nextCursor": "2026-06-03T09:00:00Z",
  "hasMore": true
}
```

**Cursor logic:** `WHERE last_active < :cursor ORDER BY last_active DESC LIMIT :limit`. First request omits cursor (starts from `NOW()`).

**New SessionStore method:**
```java
List<Session> findAll(String userId, Session.SessionStatus status, Instant cursor, int limit)
```

**New MysqlSessionStore implementation:** Dynamic WHERE clause building with optional userId, status, and cursor filters.

---

### GET /api/sessions/{sessionId} — Session Detail

**Response (200):**
```json
{
  "id": "a1b2c3d4e5f6",
  "userId": "user123",
  "createdAt": "2026-06-03T10:00:00Z",
  "lastActive": "2026-06-03T10:30:00Z",
  "endedAt": null,
  "status": "active",
  "refinementStatus": "done"
}
```

**Response (404):** `{"error": "Session not found"}`

**Implementation:** `SessionStore.findActive(sessionId)` for active sessions. For ended/timeout sessions, need a new `findById(sessionId)` method (currently only `findActive` exists, which filters `status='active'`).

**New SessionStore method:**
```java
Optional<Session> findById(String sessionId)
```

---

### GET /api/sessions/{sessionId}/messages — Message History

Cursor-based pagination over messages.

**Query Parameters:**
| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `limit` | int | No | Page size, default 50, max 200. |
| `cursor` | long | No | Message ID cursor. Omit to start from beginning/end. |
| `direction` | string | No | `asc` (old→new, default) or `desc` (new→old). |

**Response:**
```json
{
  "messages": [
    {
      "id": 1001,
      "sessionId": "a1b2c3d4e5f6",
      "role": "user",
      "content": "Hello",
      "isSummary": false,
      "createdAt": "2026-06-03T10:00:01Z"
    }
  ],
  "nextCursor": 1050,
  "hasMore": true
}
```

**Cursor logic (asc):** `WHERE session_id = :sid AND id > :cursor ORDER BY id ASC LIMIT :limit`
**Cursor logic (desc):** `WHERE session_id = :sid AND id < :cursor ORDER BY id DESC LIMIT :limit`

**New MessageStore method:**
```java
List<MemoryMessage> loadPage(String sessionId, long cursor, int limit, boolean ascending)
```

---

### GET /api/sessions/{sessionId}/stats — Session Statistics

Aggregated statistics from MessageStore analytics methods.

**Response:**
```json
{
  "sessionId": "a1b2c3d4e5f6",
  "userMessageCount": 12,
  "assistantMessageCount": 11,
  "toolMessageCount": 5,
  "conversationTurns": 11,
  "avgAssistantReplyLength": 256,
  "totalUserChars": 1500,
  "hasUserQuestions": true,
  "durationMinutes": 25,
  "status": "active",
  "refinementStatus": "done"
}
```

**Implementation:** Combines `SessionStore.findById()` + multiple `MessageStore` analytics calls (already exist). Duration computed from `session.createdAt` to `session.endedAt` or `NOW()`.

**New MessageStore method:**
```java
int countByRole(String sessionId, String role)
```

The existing `countUserMessages`, `countConversationTurns`, `countToolMessages`, `avgAssistantReplyLength`, `sumUserContentLength`, `hasUserQuestions` are reused directly.

---

### DELETE /api/sessions/{sessionId} — Close/Delete Session

**Behavior:**
1. Close session: `SessionStore.close(sessionId, SessionStatus.ended)`
2. Remove from cache: `SessionMessageCache.remove(sessionId)`
3. Messages preserved in DB (audit history retained)

**Response (200):** `{"message": "Session closed", "sessionId": "a1b2c3d4e5f6"}`
**Response (404):** `{"error": "Session not found"}`

---

## Architecture

### New File: SessionHandler.java

Location: `harness-server/src/main/java/com/harness/server/SessionHandler.java`

Constructor dependencies:
- `SessionStore sessionStore`
- `MessageStore messageStore`
- `SessionMessageCache cache`

Methods: one handler per endpoint (create, list, detail, messages, stats, delete).

### Route Registration

In `Main.java`, register routes:
```java
SessionHandler sessionHandler = new SessionHandler(sessionStore, messageStore, cache);
app.post("/api/sessions", sessionHandler::create);
app.get("/api/sessions", sessionHandler::list);
app.get("/api/sessions/:sessionId", sessionHandler::detail);
app.get("/api/sessions/:sessionId/messages", sessionHandler::messages);
app.get("/api/sessions/:sessionId/stats", sessionHandler::stats);
app.delete("/api/sessions/:sessionId", sessionHandler::delete);
```

### Store Changes Summary

| Store | New Method | Purpose |
|-------|-----------|---------|
| `SessionStore` | `findById(sessionId)` | Get any session (not just active) |
| `SessionStore` | `findAll(userId, status, cursor, limit)` | Paginated list with filters |
| `MessageStore` | `loadPage(sessionId, cursor, limit, ascending)` | Paginated message history |
| `MessageStore` | `countByRole(sessionId, role)` | Count messages by role |

### NoOp Updates

`NoOpSessionStore` and `NoOpMessageStore` must add the new methods with empty/null returns.

## Files to Modify

| File | Action |
|------|--------|
| `harness-server/.../server/SessionHandler.java` | **NEW** |
| `harness-server/.../server/Main.java` | ADD route registration |
| `harness-preprocess/.../memory/SessionStore.java` | ADD 2 methods |
| `harness-preprocess/.../memory/MysqlSessionStore.java` | IMPLEMENT 2 methods |
| `harness-preprocess/.../memory/NoOpSessionStore.java` | ADD 2 stubs |
| `harness-preprocess/.../memory/MessageStore.java` | ADD 2 methods |
| `harness-preprocess/.../memory/MysqlMessageStore.java` | IMPLEMENT 2 methods |
| `harness-preprocess/.../memory/NoOpMessageStore.java` | ADD 2 stubs |
| `CLAUDE.md` | UPDATE endpoint table |

## Error Handling

- 400: Invalid parameters (bad cursor format, negative limit)
- 404: Session not found (detail/messages/stats/delete)
- 500: Database errors (let Javalin default handler catch)

## No Changes To

- Chat flow (AgentOrchestrator, ChatHandler)
- Session lifecycle (SessionLifecycleManager, SessionCleanupScheduler)
- Cache logic (SessionMessageCache internals)
- Database schema (existing tables sufficient)
