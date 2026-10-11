package com.harness.agent.subagent;

import com.harness.agent.*;
import com.harness.core.model.PageResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Authoritative task and delivery state; execution scopes are only live handles. */
public interface SubAgentTaskRepository {
    record StoredTask(String taskId, String ownerRunId, String ownerSessionId, String ownerTurnId,
                      AgentRunContext.Owner owner, String spawnToolCallId, String rootTraceId,
                      SubAgentTask task, SubAgentStatus status, SubAgentResult result,
                      ResultDeliveryState deliveryState, String eventId, Instant createdAt, Instant expiresAt) {
        public SessionInbox.SubAgentCompletedEvent event(String leaseToken) {
            return new SessionInbox.SubAgentCompletedEvent(eventId, ownerSessionId, taskId,
                    task.description(), ownerTurnId, result, createdAt,
                    SessionInbox.SubAgentCompletedEvent.EventStatus.PROCESSING, owner, leaseToken);
        }
    }

    void create(SubAgentTaskRecord record);
    boolean transition(String taskId, SubAgentStatus expected, SubAgentStatus next);
    boolean complete(String taskId, SubAgentStatus status, SubAgentResult result);
    boolean changeDelivery(String taskId, ResultDeliveryState expected, ResultDeliveryState next);
    Optional<StoredTask> findAuthorized(AgentRunContext.Owner owner, String sessionId, String taskId);
    PageResponse<StoredTask> listAuthorized(AgentRunContext.Owner owner, String sessionId, String cursor, int limit);
    List<SessionInbox.SubAgentCompletedEvent> claimDeliveries(String sessionId, Duration lease, int limit);
    void acknowledgeDelivery(String eventId, String leaseToken);
    void releaseDelivery(String eventId, String leaseToken);
    void renewDelivery(String eventId, String leaseToken, Duration lease);
    PageResponse<String> pendingSessions(String cursor, int limit);
    boolean hasPendingDelivery(String sessionId);
    int suppressSession(String sessionId);
    void suppressTask(String taskId);
    int interruptUnfinished(int limit);
    int deleteExpired(int limit);

    static int requireLimit(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        return limit;
    }

    static String cursor(StoredTask task) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                (task.createdAt().toEpochMilli() + ":" + task.taskId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String[] decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String value = new String(java.util.Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
            String[] values = value.split(":", 2);
            if (values.length != 2 || values[1].isBlank() || values[1].length() > 64) throw new IllegalArgumentException();
            Long.parseLong(values[0]);
            return values;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid task cursor", e);
        }
    }

    static String tenant(AgentRunContext.Owner owner) {
        if (owner == null) throw new IllegalArgumentException("Trusted task owner is required");
        return owner.tenantId() == null || owner.tenantId().isBlank() ? "000000" : owner.tenantId();
    }
}
