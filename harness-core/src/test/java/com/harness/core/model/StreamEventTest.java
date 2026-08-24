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
                "call-1",
                "request-1",
                "delete_file",
                Map.of("path", "/tmp/a.txt"),
                "hash",
                "Delete a file",
                "HIGH",
                "2026-07-27T10:00:00Z");

        assertThat(event.type()).isEqualTo(StreamEvent.Type.CONFIRMATION_REQUIRED);
        assertThat(event.metadata())
                .containsEntry("toolCallId", "call-1")
                .containsEntry("requestId", "request-1")
                .containsEntry("toolName", "delete_file")
                .containsEntry("status", ToolCallStatus.AWAITING_CONFIRMATION.name())
                .containsEntry("riskLevel", "HIGH");
    }

    @Test
    void toolEvents_keepStableIdAndTypedStatus() {
        var created = StreamEvent.toolCallCreated("call-7", "search", "{}");
        var running = StreamEvent.toolCallStart("call-7", "search", "{}");
        var done = StreamEvent.toolCallDone(
                "call-7", "search", ToolCallStatus.SUCCEEDED, 12, "");

        assertThat(created.metadata())
                .containsEntry("toolCallId", "call-7")
                .containsEntry("status", "CREATED");
        assertThat(running.metadata())
                .containsEntry("toolCallId", "call-7")
                .containsEntry("status", "RUNNING");
        assertThat(done.metadata())
                .containsEntry("toolCallId", "call-7")
                .containsEntry("status", "SUCCEEDED")
                .doesNotContainKey("success");
    }

    @Test
    void audioDelta_containsOrderedAudioPayload() {
        var event = StreamEvent.audioDelta(2, "audio/mpeg", "AQID");

        assertThat(event.type()).isEqualTo(StreamEvent.Type.AUDIO_DELTA);
        assertThat(event.data()).isEqualTo("AQID");
        assertThat(event.metadata())
                .containsEntry("sequence", 2L)
                .containsEntry("mimeType", "audio/mpeg");
    }
}
