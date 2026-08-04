package com.harness.agent.voice;

import com.harness.provider.AudioChunk;
import com.harness.provider.AudioStreamCallback;
import com.harness.provider.SynthesisRequest;
import com.harness.provider.VoiceCapabilities;
import com.harness.provider.VoiceModelProvider;
import com.harness.core.model.CancellationToken;
import com.harness.core.model.StreamEvent;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceOutputCoordinatorTest {

    @Test
    void startsPhraseSynthesisBeforeAnswerFinishes() throws Exception {
        RecordingVoiceProvider provider = new RecordingVoiceProvider();
        List<StreamEvent> events = new CopyOnWriteArrayList<>();
        VoiceOutputSettings settings = new VoiceOutputSettings(
                "audio", "mp3", "alloy", 1.0,
                4, 8, 20, 2, 200);

        try (VoiceOutputCoordinator coordinator = new VoiceOutputCoordinator(
                provider, events::add, new CancellationToken(), settings)) {
            coordinator.accept("第一句话已经完成。");

            assertThat(provider.started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(provider.phrases).containsExactly("第一句话已经完成。");

            coordinator.accept("第二句。");
            coordinator.finishAndAwait(Duration.ofSeconds(2));
        }

        assertThat(events).extracting(StreamEvent::type)
                .contains(StreamEvent.Type.AUDIO_START,
                        StreamEvent.Type.AUDIO_DELTA,
                        StreamEvent.Type.AUDIO_CHUNK_DONE,
                        StreamEvent.Type.AUDIO_DONE);
    }

    private static final class RecordingVoiceProvider implements VoiceModelProvider {
        private final CountDownLatch started = new CountDownLatch(1);
        private final List<String> phrases = new CopyOnWriteArrayList<>();

        @Override
        public String transcribe(InputStream audio, String mimeType) {
            return "";
        }

        @Override
        public byte[] synthesize(String text, String voice) {
            return new byte[0];
        }

        @Override
        public void streamSynthesize(
                SynthesisRequest request,
                AudioStreamCallback callback,
                CancellationToken cancellationToken
        ) {
            phrases.add(request.text());
            started.countDown();
            callback.onStart(request.sequence(), "audio/mpeg");
            callback.onChunk(new AudioChunk(request.sequence(), new byte[]{1, 2, 3}, "audio/mpeg"));
            callback.onComplete(request.sequence());
        }

        @Override
        public VoiceCapabilities capabilities() {
            return new VoiceCapabilities(true, true, true, List.of("audio/webm"), List.of("mp3"));
        }

        @Override
        public String providerName() {
            return "test";
        }
    }
}
