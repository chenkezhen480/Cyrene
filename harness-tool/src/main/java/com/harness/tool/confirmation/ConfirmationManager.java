package com.harness.tool.confirmation;

import com.harness.core.model.CancellationToken;
import com.harness.core.model.RiskLevel;
import com.harness.core.model.ToolCall;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stores pending confirmations and issues one-time approval consumption.
 */
public final class ConfirmationManager {

    private enum State {
        PENDING,
        APPROVED,
        REJECTED,
        EXPIRED,
        CANCELLED,
        CONSUMED
    }

    private static final class PendingConfirmation {
        private final ConfirmationRequest request;
        private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);
        private final CompletableFuture<ConfirmationDecision> decision = new CompletableFuture<>();

        private PendingConfirmation(ConfirmationRequest request) {
            this.request = request;
        }
    }

    private final Duration requestTtl;
    private final ConcurrentHashMap<String, PendingConfirmation> pendingById = new ConcurrentHashMap<>();

    public ConfirmationManager(Duration requestTtl) {
        if (requestTtl == null || requestTtl.isZero() || requestTtl.isNegative()) {
            throw new IllegalArgumentException("Confirmation request TTL must be positive");
        }
        this.requestTtl = requestTtl;
    }

    public ConfirmationRequest create(String userId, String sessionId, ToolCall toolCall, String summary) {
        Objects.requireNonNull(toolCall, "toolCall");
        Instant createdAt = Instant.now();
        ConfirmationRequest request = new ConfirmationRequest(
                UUID.randomUUID().toString(),
                userId,
                sessionId,
                toolCall.toolName(),
                toolCall.arguments(),
                argumentsHash(toolCall),
                summary,
                RiskLevel.HIGH,
                createdAt,
                createdAt.plus(requestTtl));
        pendingById.put(request.requestId(), new PendingConfirmation(request));
        return request;
    }

    public ConfirmationDecision approve(String requestId, String userId, String sessionId) {
        return resolve(requestId, userId, sessionId, State.APPROVED, ConfirmationDecision.APPROVED);
    }

    public ConfirmationDecision reject(String requestId, String userId, String sessionId) {
        return resolve(requestId, userId, sessionId, State.REJECTED, ConfirmationDecision.REJECTED);
    }

    public ConfirmationDecision awaitDecision(String requestId, CancellationToken cancellationToken) {
        PendingConfirmation pending = requirePending(requestId);
        if (cancellationToken != null && cancellationToken.isCancelled()) {
            return transitionOrCurrent(
                    pending, State.CANCELLED, ConfirmationDecision.CANCELLED);
        }

        long waitMillis = Duration.between(Instant.now(), pending.request.expiresAt()).toMillis();
        if (waitMillis <= 0) {
            return transitionOrCurrent(
                    pending, State.EXPIRED, ConfirmationDecision.EXPIRED);
        }

        try {
            return pending.decision.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return transitionOrCurrent(
                    pending, State.EXPIRED, ConfirmationDecision.EXPIRED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return transitionOrCurrent(
                    pending, State.CANCELLED, ConfirmationDecision.CANCELLED);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Confirmation decision failed", e.getCause());
        }
    }

    /**
     * Atomically consumes an approval after verifying the exact original tool and arguments.
     */
    public boolean consumeApproved(String requestId, ToolCall toolCall) {
        PendingConfirmation pending = requirePending(requestId);
        if (!pending.request.toolName().equals(toolCall.toolName())
                || !pending.request.argumentsHash().equals(argumentsHash(toolCall))) {
            return false;
        }
        return pending.state.compareAndSet(State.APPROVED, State.CONSUMED);
    }

    public void release(String requestId) {
        if (requestId != null) {
            pendingById.remove(requestId);
        }
    }

    private ConfirmationDecision resolve(String requestId, String userId, String sessionId,
                                         State targetState, ConfirmationDecision decision) {
        PendingConfirmation pending = requirePending(requestId);
        verifyOwner(pending.request, userId, sessionId);
        if (Instant.now().isAfter(pending.request.expiresAt())) {
            transition(pending, State.EXPIRED, ConfirmationDecision.EXPIRED);
            throw new IllegalStateException("Confirmation request has expired");
        }
        if (!transition(pending, targetState, decision)) {
            throw new IllegalStateException("Confirmation request is no longer pending");
        }
        return decision;
    }

    private PendingConfirmation requirePending(String requestId) {
        PendingConfirmation pending = pendingById.get(requestId);
        if (pending == null) {
            throw new NoSuchElementException("Confirmation request not found: " + requestId);
        }
        return pending;
    }

    private void verifyOwner(ConfirmationRequest request, String userId, String sessionId) {
        if (!Objects.equals(request.userId(), userId)
                || !Objects.equals(request.sessionId(), sessionId)) {
            throw new SecurityException("Confirmation request does not belong to this user and session");
        }
    }

    private boolean transition(PendingConfirmation pending, State state, ConfirmationDecision decision) {
        if (pending.state.compareAndSet(State.PENDING, state)) {
            pending.decision.complete(decision);
            return true;
        }
        return false;
    }

    private ConfirmationDecision transitionOrCurrent(
            PendingConfirmation pending, State state, ConfirmationDecision decision) {
        if (transition(pending, state, decision)) {
            return decision;
        }
        return pending.decision.join();
    }

    private String argumentsHash(ToolCall toolCall) {
        try {
            String payload = toolCall.toolName() + "\n"
                    + (toolCall.arguments() != null ? toolCall.arguments().toString() : "null");
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash tool arguments", e);
        }
    }
}
