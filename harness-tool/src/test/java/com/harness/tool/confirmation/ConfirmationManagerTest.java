package com.harness.tool.confirmation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfirmationManagerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void approvedRequest_isConsumedOnceForExactArguments() {
        ConfirmationManager manager = new ConfirmationManager(Duration.ofMinutes(1));
        ToolCall toolCall = ToolCall.of(
                "delete_file", MAPPER.createObjectNode().put("path", "/tmp/a.txt"));
        ConfirmationRequest request = manager.create(
                "user-1", "session-1", toolCall, "Delete file");

        assertThat(manager.approve(request.requestId(), "user-1", "session-1"))
                .isEqualTo(ConfirmationDecision.APPROVED);
        assertThat(manager.awaitDecision(request.requestId(), null))
                .isEqualTo(ConfirmationDecision.APPROVED);
        assertThat(manager.consumeApproved(request.requestId(), toolCall)).isTrue();
        assertThat(manager.consumeApproved(request.requestId(), toolCall)).isFalse();
    }

    @Test
    void approval_rejectsDifferentOwner() {
        ConfirmationManager manager = new ConfirmationManager(Duration.ofMinutes(1));
        ToolCall toolCall = ToolCall.of(
                "delete_file", MAPPER.createObjectNode().put("path", "/tmp/a.txt"));
        ConfirmationRequest request = manager.create(
                "user-1", "session-1", toolCall, "Delete file");

        assertThatThrownBy(() ->
                manager.approve(request.requestId(), "user-2", "session-1"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectedRequest_neverProducesApproval() {
        ConfirmationManager manager = new ConfirmationManager(Duration.ofMinutes(1));
        ToolCall toolCall = ToolCall.of(
                "delete_file", MAPPER.createObjectNode().put("path", "/tmp/a.txt"));
        ConfirmationRequest request = manager.create(
                "user-1", "session-1", toolCall, "Delete file");

        assertThat(manager.reject(request.requestId(), "user-1", "session-1"))
                .isEqualTo(ConfirmationDecision.REJECTED);
        assertThat(manager.awaitDecision(request.requestId(), null))
                .isEqualTo(ConfirmationDecision.REJECTED);
        assertThat(manager.consumeApproved(request.requestId(), toolCall)).isFalse();
    }

    @Test
    void pendingRequest_expires() {
        ConfirmationManager manager = new ConfirmationManager(Duration.ofMillis(10));
        ToolCall toolCall = ToolCall.of(
                "delete_file", MAPPER.createObjectNode().put("path", "/tmp/a.txt"));
        ConfirmationRequest request = manager.create(
                "user-1", "session-1", toolCall, "Delete file");

        assertThat(manager.awaitDecision(request.requestId(), null))
                .isEqualTo(ConfirmationDecision.EXPIRED);
    }
}
