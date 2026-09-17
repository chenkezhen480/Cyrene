package com.harness.agent.voice;

import com.harness.core.model.Artifact;
import com.harness.provider.VoiceCapabilities;
import com.harness.provider.VoiceModelProvider;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceSpeechStreamTest {

    /**
     * Long enough to be cut more than once: the opening floor is 20 characters and every
     * later segment waits for 40, so a two-sentence stub would come out whole.
     */
    private static final String LONG_ANSWER =
            "今天星期三。天气不错，适合出门散步，也可以去公园坐一会儿。下午可能会下雨，记得带伞，如果雨太大就别走远了。";

    @Test
    void cutsTheFirstSegmentBeforeTheAnswerIsFinished() {
        Recorder recorder = new Recorder();
        VoiceSpeechStream stream = recorder.open();

        stream.accept(LONG_ANSWER);
        stream.complete("");

        assertThat(recorder.spokenTexts()).hasSizeGreaterThan(1);
        assertThat(recorder.spokenTexts().get(0).length()).isLessThan(LONG_ANSWER.length());
        assertThat(String.join("", recorder.spokenTexts())).isEqualTo(LONG_ANSWER);
    }

    @Test
    void opensOnTheFirstBoundaryButDoesNotCutOncePerLine() {
        Recorder recorder = new Recorder();
        VoiceSpeechStream stream = recorder.open();

        // The shape models actually write: a bulleted list separated by newlines and colons,
        // carrying no sentence punctuation at all until the very end.
        stream.accept("可以帮你做这些：\n- 回答问题或讨论话题\n- 查询知识图谱中的信息\n- 分析你上传的文档");
        stream.complete("");

        // One short opener so sound starts early, then the remainder — not a fragment per
        // line, each of which would pay the per-call synthesis cost.
        assertThat(recorder.spokenTexts()).hasSize(2);
        assertThat(recorder.spokenTexts().get(0)).hasSizeLessThan(
                VoiceSpeechStream.SEGMENT_MIN_CHARS + 10);
    }

    @Test
    void forcesACutWhenTheTextNeverPunctuates() {
        Recorder recorder = new Recorder();
        VoiceSpeechStream stream = recorder.open();

        stream.accept("词".repeat(VoiceSpeechStream.SEGMENT_MAX_CHARS + 25));
        stream.complete("");

        // Without the cap this would hold everything back until the answer ended, which is
        // exactly the delay this class exists to remove.
        assertThat(recorder.spokenTexts().get(0))
                .hasSize(VoiceSpeechStream.SEGMENT_MAX_CHARS);
        assertThat(recorder.spokenTexts()).hasSizeGreaterThan(1);
    }

    @Test
    void synthesizesConcurrentlyButStillEmitsInOrder() {
        Recorder recorder = new Recorder();
        // The opener is the slowest call by far, so it finishes last while later segments are
        // already done. The client cannot reorder what it receives, so the stream must.
        recorder.delayMsForCall = call -> call == 1 ? 400 : 40;
        VoiceSpeechStream stream = recorder.open();

        stream.accept(LONG_ANSWER);
        stream.complete("");

        assertThat(recorder.maxInFlight.get())
                .describedAs("segments must be synthesized in parallel, not one after another")
                .isGreaterThan(1);
        assertThat(recorder.sequences)
                .describedAs("emission order must follow the text, not the finishing order")
                .containsExactlyElementsOf(
                        IntStream.range(0, recorder.spoken.size()).boxed().toList());
        assertThat(String.join("", recorder.spokenTexts())).isEqualTo(LONG_ANSWER);
    }

    @Test
    void speaksEveryCharacterExactlyOnceInOrder() {
        Recorder recorder = new Recorder();
        VoiceSpeechStream stream = recorder.open();

        stream.accept(LONG_ANSWER);
        stream.complete("");

        assertThat(recorder.sequences)
                .containsExactlyElementsOf(
                        IntStream.range(0, recorder.spoken.size()).boxed().toList());
        assertThat(String.join("", recorder.spokenTexts())).isEqualTo(LONG_ANSWER);
        assertThat(recorder.synthesizeCalls.get()).isEqualTo(recorder.spoken.size());
    }

    @Test
    void flushesTheTailOnCompletion() {
        Recorder recorder = new Recorder();
        VoiceSpeechStream stream = recorder.open();

        // Below the opening floor, so it waits in the buffer until the answer ends.
        stream.accept("好的");
        assertThat(recorder.spoken).isEmpty();

        stream.complete("");

        assertThat(recorder.spokenTexts()).containsExactly("好的");
    }

    @Test
    void speaksAWholeAnswerThatArrivedWithoutTokens() {
        Recorder recorder = new Recorder();
        VoiceSpeechStream stream = recorder.open();

        stream.complete("只有最终输出，没有逐字事件。");

        assertThat(recorder.spokenTexts()).containsExactly("只有最终输出，没有逐字事件。");
    }

    @Test
    void emitsOneMergedRecordingWithACorrectWavHeader() {
        Recorder recorder = new Recorder();
        VoiceSpeechStream stream = recorder.open();

        stream.accept(LONG_ANSWER);
        stream.complete("");

        byte[] merged = recorder.mergedAudio.get();
        int payload = recorder.spokenTexts().stream()
                .mapToInt(text -> text.getBytes(StandardCharsets.UTF_8).length)
                .sum();

        assertThat(recorder.spoken).hasSizeGreaterThan(1);
        assertThat(recorder.merged.get()).isNotNull();
        // One header for the whole recording, not one per segment.
        assertThat(merged).hasSize(WAV_HEADER_BYTES + payload);
        // A plain concatenation would leave the header claiming only the first segment's
        // length, and playback would stop after the first sentence.
        assertThat(intAt(merged, 40)).isEqualTo(payload);
        assertThat(intAt(merged, 4)).isEqualTo(merged.length - 8);
        assertThat(ascii(merged, 0)).isEqualTo("RIFF");
    }

    @Test
    void keepsGoingWhenOneSegmentFailsToSynthesize() {
        Recorder recorder = new Recorder();
        recorder.failOnSynthesizeCall = 1;
        VoiceSpeechStream stream = recorder.open();

        stream.accept(LONG_ANSWER);
        stream.complete("");

        assertThat(recorder.failures).hasSize(1);
        // The rest of the answer is still spoken; one bad sentence is not a failed turn.
        assertThat(recorder.merged.get()).isNotNull();
        assertThat(recorder.spoken).hasSize(1);
    }

    @Test
    void stopsSubmittingOnceTheRunIsCancelled() {
        Recorder recorder = new Recorder();
        VoiceSpeechStream stream = recorder.open();
        recorder.cancelled.set(true);

        stream.accept(LONG_ANSWER);
        stream.complete("被取消的回答");

        assertThat(recorder.synthesizeCalls.get()).isZero();
        assertThat(recorder.merged.get()).isNull();
    }

    private static final int WAV_HEADER_BYTES = 44;

    /** 24 kHz mono 16-bit, with a fully populated fmt chunk — the merger validates it. */
    private static byte[] wav(byte[] payload) {
        byte[] bytes = new byte[WAV_HEADER_BYTES + payload.length];
        putAscii(bytes, 0, "RIFF");
        putInt(bytes, 4, 36 + payload.length);
        putAscii(bytes, 8, "WAVE");
        putAscii(bytes, 12, "fmt ");
        putInt(bytes, 16, 16);
        putShort(bytes, 20, 1);
        putShort(bytes, 22, 1);
        putInt(bytes, 24, 24000);
        putInt(bytes, 28, 48000);
        putShort(bytes, 32, 2);
        putShort(bytes, 34, 16);
        putAscii(bytes, 36, "data");
        putInt(bytes, 40, payload.length);
        System.arraycopy(payload, 0, bytes, WAV_HEADER_BYTES, payload.length);
        return bytes;
    }

    private static void putShort(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >> 8);
    }

    private static void putAscii(byte[] target, int offset, String text) {
        byte[] raw = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(raw, 0, target, offset, raw.length);
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >> 8);
        target[offset + 2] = (byte) (value >> 16);
        target[offset + 3] = (byte) (value >> 24);
    }

    private static int intAt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16) | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static String ascii(byte[] bytes, int offset) {
        return new String(bytes, offset, 4, StandardCharsets.US_ASCII);
    }

    private static final class Recorder implements VoiceSpeechStream.Listener {
        final List<String> spoken = new CopyOnWriteArrayList<>();
        final List<Integer> sequences = new CopyOnWriteArrayList<>();
        final List<Integer> failures = new CopyOnWriteArrayList<>();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicInteger synthesizeCalls = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final AtomicReference<Artifact> merged = new AtomicReference<>();
        final AtomicReference<byte[]> mergedAudio = new AtomicReference<>();
        volatile Integer failOnSynthesizeCall;
        volatile java.util.function.IntFunction<Integer> delayMsForCall;

        VoiceSpeechStream open() {
            return new VoiceSpeechStream(
                    () -> new FakeVoiceProvider(this),
                    this::store,
                    () -> "session-1",
                    cancelled::get,
                    this);
        }

        List<String> spokenTexts() {
            return spoken;
        }

        @Override
        public void onSegment(int sequence, String text, Artifact artifact) {
            sequences.add(sequence);
            spoken.add(text);
        }

        @Override
        public void onSegmentFailure(int sequence, String text, String message) {
            failures.add(sequence);
        }

        @Override
        public void onComplete(Artifact artifact, int segmentCount, String finalText) {
            merged.set(artifact);
        }

        @Override
        public void onCompleteFailure(String message) {
            throw new AssertionError("unexpected completion failure: " + message);
        }

        Artifact store(byte[] data, String name, String mimeType, String sessionId) {
            if (!name.startsWith("speech-segment-")) {
                // The merged recording, the only artifact the client keeps.
                mergedAudio.set(data.clone());
            }
            return new Artifact(name, sessionId, name, Artifact.ArtifactType.AUDIO,
                    mimeType, data.length, name, Instant.EPOCH);
        }
    }

    private static final class FakeVoiceProvider implements VoiceModelProvider {
        private final Recorder recorder;

        FakeVoiceProvider(Recorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public String transcribe(InputStream audio, String mimeType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] synthesize(String text, String voice) {
            int call = recorder.synthesizeCalls.incrementAndGet();
            recorder.maxInFlight.accumulateAndGet(recorder.inFlight.incrementAndGet(), Math::max);
            try {
                java.util.function.IntFunction<Integer> delays = recorder.delayMsForCall;
                if (delays != null) {
                    Thread.sleep(delays.apply(call));
                }
                if (recorder.failOnSynthesizeCall != null
                        && recorder.failOnSynthesizeCall == call - 1) {
                    throw new IllegalStateException("upstream 503");
                }
                return wav(text.getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            } finally {
                recorder.inFlight.decrementAndGet();
            }
        }

        @Override
        public String synthesizeMimeType() {
            return "audio/wav";
        }

        @Override
        public String synthesizeFileExtension() {
            return "wav";
        }

        @Override
        public VoiceCapabilities capabilities() {
            return new VoiceCapabilities(false, true, List.of(), List.of("wav"));
        }

        @Override
        public String providerName() {
            return "fake";
        }
    }
}
