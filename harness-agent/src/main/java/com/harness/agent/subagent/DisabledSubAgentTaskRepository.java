package com.harness.agent.subagent;

import com.harness.agent.*;
import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Disables task execution when durable session storage is unavailable. */
public final class DisabledSubAgentTaskRepository implements SubAgentTaskRepository {
    private IllegalStateException unavailable() { return new IllegalStateException("Sub-agent tasks require MySQL session persistence"); }
    @Override public void create(SubAgentTaskRecord record) { throw unavailable(); }
    @Override public boolean transition(String taskId, SubAgentStatus expected, SubAgentStatus next) { throw unavailable(); }
    @Override public boolean complete(String taskId, SubAgentStatus status, SubAgentResult result) { throw unavailable(); }
    @Override public boolean changeDelivery(String taskId, ResultDeliveryState expected, ResultDeliveryState next) { throw unavailable(); }
    @Override public Optional<StoredTask> findAuthorized(AgentRunContext.Owner owner, String sessionId, String taskId) { throw unavailable(); }
    @Override public PageResponse<StoredTask> listAuthorized(AgentRunContext.Owner owner, String sessionId, String cursor, int limit) { throw unavailable(); }
    @Override public List<SessionInbox.SubAgentCompletedEvent> claimDeliveries(String sessionId, Duration lease, int limit) { return List.of(); }
    @Override public void acknowledgeDelivery(String eventId, String leaseToken) { throw unavailable(); }
    @Override public void releaseDelivery(String eventId, String leaseToken) { throw unavailable(); }
    @Override public void renewDelivery(String eventId, String leaseToken, Duration lease) { throw unavailable(); }
    @Override public PageResponse<String> pendingSessions(String cursor, int limit) { return new PageResponse<>(List.of(), new PageInfo(limit, "", false)); }
    @Override public boolean hasPendingDelivery(String sessionId) { return false; }
    @Override public int suppressSession(String sessionId) { return 0; }
    @Override public void suppressTask(String taskId) { throw unavailable(); }
    @Override public int interruptUnfinished(int limit) { return 0; }
    @Override public int deleteExpired(int limit) { return 0; }
}
