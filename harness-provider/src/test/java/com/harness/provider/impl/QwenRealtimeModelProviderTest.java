package com.harness.provider.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.provider.RealtimeEvent;
import com.harness.provider.RealtimeInput;
import com.harness.provider.RealtimeSession;
import com.harness.provider.RealtimeSessionConfig;
import com.harness.provider.RealtimeSessionState;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QwenRealtimeModelProviderTest {

    @Test
    void opensSendsInterruptsAndClosesOneSession() {
        OkHttpClient http = mock(OkHttpClient.class);
        WebSocket socket = mock(WebSocket.class);
        ArgumentCaptor<Request> request = ArgumentCaptor.forClass(Request.class);
        ArgumentCaptor<WebSocketListener> callback =
                ArgumentCaptor.forClass(WebSocketListener.class);
        when(socket.send(any(String.class))).thenReturn(true);
        when(socket.close(1000, "client close")).thenReturn(true);
        when(http.newWebSocket(request.capture(), callback.capture())).thenReturn(socket);
        List<RealtimeEvent> events = new ArrayList<>();
        QwenRealtimeModelProvider provider = new QwenRealtimeModelProvider(
                "secret", "wss://example.invalid/realtime", "qwen-test", http,
                new ObjectMapper());

        RealtimeSession session = provider.open(RealtimeSessionConfig.defaults(), events::add);
        assertThat(request.getValue().url().queryParameter("model")).isEqualTo("qwen-test");
        assertThat(request.getValue().header("Authorization")).isEqualTo("Bearer secret");
        assertThat(session.state()).isEqualTo(RealtimeSessionState.OPENING);

        WebSocketListener listener = callback.getValue();
        listener.onMessage(socket, "{\"type\":\"session.created\"}");
        listener.onMessage(socket, "{\"type\":\"session.updated\"}");
        assertThat(session.state()).isEqualTo(RealtimeSessionState.READY);
        assertThat(events).extracting(RealtimeEvent::type)
                .contains(RealtimeEvent.Type.SESSION_READY);

        session.send(new RealtimeInput.TextInput("hello"));
        session.send(new RealtimeInput.AudioChunk(new byte[]{1, 2, 3}));
        listener.onMessage(socket, "{\"type\":\"response.created\"}");
        assertThat(session.state()).isEqualTo(RealtimeSessionState.ACTIVE);
        listener.onMessage(socket, "{\"type\":\"input_audio_buffer.speech_started\"}");
        assertThat(session.state()).isEqualTo(RealtimeSessionState.INTERRUPTING);
        assertThat(events).extracting(RealtimeEvent::type)
                .contains(RealtimeEvent.Type.USER_SPEECH_STARTED);

        session.close();
        session.close();
        assertThat(session.state()).isEqualTo(RealtimeSessionState.CLOSED);
        assertThat(events).extracting(RealtimeEvent::type)
                .containsOnlyOnce(RealtimeEvent.Type.CLOSED);
        verify(socket).close(1000, "client close");
    }

    @Test
    void mapsTranscriptAudioToolUsageAndSuppressesInterruptedAudio() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        assertThat(QwenRealtimeModelProvider.mapEvent(mapper.readTree("""
                {"type":"conversation.item.input_audio_transcription.delta",
                 "text":"hello ","stash":"world"}
                """), "session", true).getFirst().text()).isEqualTo("hello world");

        RealtimeEvent audio = QwenRealtimeModelProvider.mapEvent(mapper.readTree("""
                {"type":"response.audio.delta","delta":"AQID"}
                """), "session", true).getFirst();
        assertThat(audio.audio()).containsExactly(1, 2, 3);
        assertThat(QwenRealtimeModelProvider.mapEvent(mapper.readTree("""
                {"type":"response.audio.delta","delta":"AQID"}
                """), "session", false)).isEmpty();

        RealtimeEvent tool = QwenRealtimeModelProvider.mapEvent(mapper.readTree("""
                {"type":"response.function_call_arguments.done","call_id":"call-1",
                 "name":"weather","arguments":"{\\\"city\\\":\\\"Hangzhou\\\"}"}
                """), "session", true).getFirst();
        assertThat(tool.toolCall().callId()).isEqualTo("call-1");
        assertThat(tool.toolCall().name()).isEqualTo("weather");

        List<RealtimeEvent> done = QwenRealtimeModelProvider.mapEvent(mapper.readTree("""
                {"type":"response.done","response":{"usage":{
                 "input_tokens":12,"output_tokens":4}}}
                """), "session", true);
        assertThat(done).extracting(RealtimeEvent::type)
                .containsExactly(RealtimeEvent.Type.USAGE, RealtimeEvent.Type.TURN_DONE);
        assertThat(done.getFirst().usage().inputTokens()).isEqualTo(12);
        assertThat(done.getFirst().usage().outputTokens()).isEqualTo(4);
    }
}
