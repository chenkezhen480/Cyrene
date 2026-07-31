package com.harness.core.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StreamEventTest {

    @Test
    void start_createsStartEvent() {
        var event = StreamEvent.start("sess-123");

        assertThat(event.type()).isEqualTo(StreamEvent.Type.START);
        assertThat(event.metadata()).containsEntry("sessionId", "sess-123");
    }

    @Test
    void start_nullSessionId_usesEmptyString() {
        var event = StreamEvent.start(null);

        assertThat(event.type()).isEqualTo(StreamEvent.Type.START);
        assertThat(event.metadata()).containsEntry("sessionId", "");
    }

    @Test
    void token_createsTokenEvent() {
        var event = StreamEvent.token("hello world");

        assertThat(event.type()).isEqualTo(StreamEvent.Type.TOKEN);
        assertThat(event.data()).isEqualTo("hello world");
        assertThat(event.metadata()).isEmpty();
    }

    @Test
    void done_createsDoneEvent() {
        var event = StreamEvent.done("final output", "trace-1", "sess-1", 5);

        assertThat(event.type()).isEqualTo(StreamEvent.Type.DONE);
        assertThat(event.data()).isEqualTo("final output");
        assertThat(event.metadata())
                .containsEntry("traceId", "trace-1")
                .containsEntry("sessionId", "sess-1")
                .containsEntry("steps", 5);
    }

    @Test
    void error_createsErrorEvent() {
        var event = StreamEvent.error("something went wrong");

        assertThat(event.type()).isEqualTo(StreamEvent.Type.ERROR);
        assertThat(event.data()).isEqualTo("something went wrong");
        assertThat(event.metadata()).isEmpty();
    }

    @Test
    void confirmationRequired_containsApprovalPayload() {
        var event = StreamEvent.confirmationRequired(
                "request-1",
                "delete_file",
                Map.of("path", "/tmp/a.txt"),
                "hash",
                "Delete a file",
                "HIGH",
                "2026-07-27T10:00:00Z");

        assertThat(event.type()).isEqualTo(StreamEvent.Type.CONFIRMATION_REQUIRED);
        assertThat(event.metadata())
                .containsEntry("requestId", "request-1")
                .containsEntry("toolName", "delete_file")
                .containsEntry("riskLevel", "HIGH");
    }
}
