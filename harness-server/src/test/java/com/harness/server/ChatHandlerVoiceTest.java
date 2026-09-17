package com.harness.server;

import com.harness.agent.AgentOrchestrator;
import com.harness.agent.voice.VoiceConversationService;
import com.harness.core.model.Artifact;
import com.harness.core.model.CancellationToken;
import com.harness.core.model.StreamCallback;
import com.harness.core.model.StreamEvent;
import com.harness.provider.VoiceCapabilities;
import com.harness.provider.VoiceModelProvider;
import com.harness.tool.builtin.AudioTranscriptionTool;
import io.javalin.http.Context;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the SSE contract of a voice turn: what the pipeline emits, in what order, and what the
 * agent is allowed to see. The order matters as much as the content -- the client stops reading
 * at {@code done}, so anything emitted after it is silently lost.
 */
class ChatHandlerVoiceTest {

    private static final String RECORDING = "/files/input/voice.webm";

    @Test
    void textTurnsEmitNoVoiceEvents() throws Exception {
        Harness harness = new Harness();
        harness.request(Map.of("outputMode", "streaming", "userId", "user-a"), null);
        harness.answerWith("普通回答");

        harness.handler.handle(harness.context);

        String stream = harness.stream();
        assertThat(stream).doesNotContain("voice_");
        assertThat(harness.provider.transcribeCalls).isZero();
        assertThat(harness.provider.synthesizeCalls).isZero();
    }

    @Test
    void voiceTurnTranscribesBeforeStartingAndSpeaksBeforeDone() throws Exception {
        Harness harness = new Harness();
        harness.request(voiceContext(), "VOICE");
        harness.answerWith("今天星期三。");
        harness.provider.transcript = "今天星期几？";

        harness.handler.handle(harness.context);

        String stream = harness.stream();
        assertThat(stream).containsSubsequence("event: voice_transcript", "event: start");
        assertThat(stream).containsSubsequence("event: voice_output", "event: done");
        assertThat(harness.provider.transcribeCalls).isEqualTo(1);
        assertThat(harness.provider.synthesizeCalls).isEqualTo(1);
        // One call, on the finished answer -- never once per streamed token.
        assertThat(harness.provider.lastSynthesizedText).isEqualTo("今天星期三。");
    }

    @Test
    void voiceTurnHandsTheAgentAPlainUserMessage() throws Exception {
        Harness harness = new Harness();
        harness.request(voiceContext(), "VOICE");
        harness.answerWith("回答");
        harness.provider.transcript = "这张图片里面是什么？";

        harness.handler.handle(harness.context);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(harness.agent).streamRun(any(), text.capture(), anyList(), any(), any(),
                any(), any(), any(), any(), any());

        assertThat(text.getValue()).isEqualTo("这张图片里面是什么？");
        assertThat(text.getValue()).doesNotContain("[Audio File", "transcribe_audio");
    }

    @Test
    void voiceTurnSkipsSynthesisWhenTheRunWasCancelled() throws Exception {
        Harness harness = new Harness();
        harness.request(voiceContext(), "VOICE");
        harness.answerWith("回答");
        harness.cancelled = true;

        harness.handler.handle(harness.context);

        assertThat(harness.stream()).doesNotContain("voice_output");
        assertThat(harness.stream()).contains("event: done");
        assertThat(harness.provider.synthesizeCalls).isZero();
    }

    @Test
    void voiceTurnKeepsTheTextAnswerWhenSynthesisFails() throws Exception {
        Harness harness = new Harness();
        harness.request(voiceContext(), "VOICE");
        harness.answerWith("回答");
        harness.provider.synthesizeFailure = new IllegalStateException("upstream 503");

        harness.handler.handle(harness.context);

        String stream = harness.stream();
        assertThat(stream).contains("voice_error");
        // The whole point: the turn's answer survives a failed TTS.
        assertThat(stream).contains("event: done");
        assertThat(stream).contains("回答");
    }

    @Test
    void voiceTurnIsRejectedBeforeTheAgentRunsWhenAsrIsUnavailable() throws Exception {
        Harness harness = new Harness();
        harness.request(voiceContext(), "VOICE");
        harness.provider.capabilities =
                new VoiceCapabilities(false, true, List.of("audio/webm"), List.of("mp3"));

        harness.handler.handle(harness.context);

        verify(harness.agent, never()).streamRun(any(), any(), anyList(), any(), any(),
                any(), any(), any(), any(), any());
        verify(harness.agent, never()).run(any(), any(), anyList(), any(), any(),
                any(), any(), any(), any());
        assertThat(harness.stream()).isEmpty();
        assertThat(harness.errorBody).contains("语音识别未配置");
    }

    @Test
    void voiceTurnIsRejectedWhenNothingWasRecognized() throws Exception {
        Harness harness = new Harness();
        harness.request(voiceContext(), "VOICE");
        harness.provider.transcript = "   ";

        harness.handler.handle(harness.context);

        verify(harness.agent, never()).streamRun(any(), any(), anyList(), any(), any(),
                any(), any(), any(), any(), any());
        assertThat(harness.errorBody).contains("未识别到有效语音内容");
    }

    private static Map<String, Object> voiceContext() {
        return Map.of(
                "outputMode", "streaming",
                "userId", "user-a",
                VoiceConversationService.CONTEXT_VOICE_INPUT, RECORDING);
    }

    /** One wired ChatHandler plus the fakes it was wired with. */
    private static final class Harness {
        final FakeVoiceProvider provider = new FakeVoiceProvider();
        final AgentOrchestrator agent = mock(AgentOrchestrator.class);
        final Context context = mock(Context.class);
        final Sink streamSink = new Sink();
        String errorBody;
        boolean cancelled;

        final ChatHandler handler;

        Harness() {
            VoiceConversationService voice = new VoiceConversationService(
                    () -> provider,
                    provider::load,
                    provider::store);
            when(agent.voiceConversation()).thenReturn(voice);

            HttpServletResponse response = mock(HttpServletResponse.class);
            when(context.res()).thenReturn(response);
            when(context.status(anyInt())).thenReturn(context);
            when(context.header("X-Session-Id")).thenReturn("session-1");
            try {
                when(response.getOutputStream()).thenReturn(streamSink);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }

            ApiRequestAuthenticator authenticator = mock(ApiRequestAuthenticator.class);
            try {
                when(authenticator.authenticate(context)).thenReturn("token");
            } catch (ApiRequestAuthenticator.RequestAuthenticationException e) {
                throw new IllegalStateException(e);
            }
            ToolPermissionService permissions = ToolPermissionStub.absent().service();

            this.handler = new ChatHandler(
                    agent, new ConcurrentHashMap<>(), authenticator, permissions);
        }

        void request(Map<String, Object> chatContext, String interactionMode) {
            try {
                when(context.bodyAsClass(ChatHandler.ChatRequest.class)).thenReturn(
                        new ChatHandler.ChatRequest(
                                "", null, null, chatContext, null, interactionMode));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            // ApiResponses.error writes through status().json(); capture what it says.
            when(context.json(any())).thenAnswer(invocation -> {
                errorBody = invocation.getArgument(0, Object.class).toString();
                return context;
            });
        }

        /** Streams a START, then a DONE carrying this answer. */
        void answerWith(String output) {
            doAnswer(invocation -> {
                StreamCallback callback = invocation.getArgument(6);
                callback.onEvent(StreamEvent.start("session-1"));
                if (cancelled) {
                    CancellationToken token = invocation.getArgument(5);
                    token.cancel();
                }
                callback.onEvent(StreamEvent.done(output, "trace-1", "session-1", 1));
                return null;
            }).when(agent).streamRun(any(), any(), anyList(), any(), any(),
                    any(), any(), any(), any(), any());
        }

        String stream() {
            return streamSink.text();
        }
    }

    /** Collects the raw SSE bytes the handler writes. */
    private static final class Sink extends ServletOutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public void write(int b) {
            bytes.write(b);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            // Nothing to notify: writes never block in this sink.
        }

        String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class FakeVoiceProvider implements VoiceModelProvider {
        VoiceCapabilities capabilities = new VoiceCapabilities(
                true, true, List.of("audio/webm", "audio/mpeg"), List.of("mp3"));
        String transcript = "今天星期几？";
        RuntimeException synthesizeFailure;
        int transcribeCalls;
        int synthesizeCalls;
        String lastSynthesizedText;

        AudioTranscriptionTool.AudioSource load(String reference) {
            if (!reference.startsWith("/files/")) {
                throw new IllegalArgumentException("Audio reference must use /files/: " + reference);
            }
            return new AudioTranscriptionTool.AudioSource(
                    new byte[]{1, 2, 3}, "voice.webm", "audio/webm");
        }

        Artifact store(byte[] data, String name, String mimeType, String sessionId) {
            return new Artifact("artifact-1", sessionId, name, Artifact.inferType(mimeType),
                    mimeType, data.length, "/tmp/" + name, Instant.EPOCH);
        }

        @Override
        public String transcribe(InputStream audio, String mimeType) {
            transcribeCalls++;
            return transcript;
        }

        @Override
        public byte[] synthesize(String text, String voice) {
            synthesizeCalls++;
            lastSynthesizedText = text;
            if (synthesizeFailure != null) {
                throw synthesizeFailure;
            }
            return new byte[]{9, 9, 9};
        }

        @Override
        public VoiceCapabilities capabilities() {
            return capabilities;
        }

        @Override
        public String providerName() {
            return "fake";
        }
    }
}
