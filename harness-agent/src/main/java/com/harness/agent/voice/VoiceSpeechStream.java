package com.harness.agent.voice;

import com.harness.core.model.Artifact;
import com.harness.provider.VoiceModelProvider;
import com.harness.tool.builtin.SpeechSynthesisTool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Speaks an answer while the agent is still producing it.
 *
 * <p>Synthesizing the finished answer costs about a second plus a fraction of a second per
 * character, so the text is on screen long before any sound arrives. This class cuts the
 * incoming token stream into speakable segments and synthesizes each one as soon as it is
 * complete, so the first words are heard while the rest is still being generated.</p>
 *
 * <p>Synthesis runs on its own single-threaded executor. It is a blocking HTTP call, and
 * running it on the token callback would stall the text stream — the one thing the user is
 * watching. One thread also keeps the segments in order for free.</p>
 *
 * <p>Segments reach the client as they finish so it can play them back to back; the whole
 * recording is emitted once at the end as a single artifact, so the conversation keeps one
 * replayable file rather than one per sentence.</p>
 */
public final class VoiceSpeechStream implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VoiceSpeechStream.class);

    /**
     * Synthesis calls run concurrently, not one after another.
     *
     * <p>A model that thinks before it answers emits its whole visible reply in a burst, so the
     * segments queue up faster than a single thread can speak them — the audio then plays
     * catch-up and stutters. Concurrency is what lets short segments be cheap: a short opener
     * starts the sound early without the fixed per-call cost of every later segment being paid
     * in wall-clock time, one after the other.</p>
     */
    private static final int SYNTHESIS_WORKERS = 3;

    /** Consumes finished segments and the final recording. */
    public interface Listener {
        void onSegment(int sequence, String text, Artifact artifact);

        void onSegmentFailure(int sequence, String text, String message);

        void onComplete(Artifact merged, int segmentCount, String finalText);

        void onCompleteFailure(String message);
    }

    /**
     * The two ends of the trade-off, and they pull in opposite directions.
     *
     * <p>The opening segment must be short: nothing is heard until it is synthesized, so its
     * length alone decides how long the user waits for the first sound.</p>
     *
     * <p>Everything after it must be long. Synthesis costs a fixed ~1.5s per call plus ~30ms
     * per character, while the model writes at roughly 12 characters per second. Below about
     * 30 characters a segment cannot be synthesized as fast as it is spoken, the audio runs dry
     * and the answer stutters. Paying that fixed cost once for the opener is worth it; paying
     * it on every sentence is not.</p>
     */
    static final int FIRST_SEGMENT_MIN_CHARS = 18;
    static final int SEGMENT_MIN_CHARS = 35;

    /**
     * Backstop for text that never reaches a boundary at all. Too high and the opener arrives
     * after the answer is already on screen, which is the delay this class exists to remove.
     */
    static final int SEGMENT_MAX_CHARS = 70;

    /**
     * Anything a speaker would naturally pause at, including newlines and colons: models write
     * markdown, and a bulleted list separates its lines with newlines while using no sentence
     * punctuation at all. Restricting cuts to full stops left a 74-character opener that only
     * landed once the whole answer was already written.
     */
    private static final String TERMINATORS = "。！？!?；;…\n，,、：:";

    /** Preferred cut points when the cap forces one, from most to least natural. */
    private static final String SOFT_TERMINATORS = "，,、：:\n";

    private final Supplier<VoiceModelProvider> providerSource;
    private final SpeechSynthesisTool.ArtifactStorer artifactStorer;
    /**
     * Resolved per store rather than captured: a streamed run only learns its real session id
     * from the agent's START event, which lands after this object is created.
     */
    private final Supplier<String> sessionIdSource;
    private final BooleanSupplier cancelled;
    private final Listener listener;

    private final StringBuilder pending = new StringBuilder();
    /** Synthesized audio by sequence, so the merge can reassemble it in order. */
    private final Map<Integer, byte[]> audioBySequence = new ConcurrentHashMap<>();
    /** Finished segments held until every earlier one has been emitted. */
    private final Map<Integer, Spoken> spokenBySequence = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicInteger nextToEmit = new AtomicInteger();
    private final AtomicInteger outstanding = new AtomicInteger();
    private final Object emitLock = new Object();
    private final ExecutorService worker = Executors.newFixedThreadPool(
            SYNTHESIS_WORKERS, runnable -> {
                Thread thread = new Thread(runnable, "voice-speech");
                thread.setDaemon(true);
                return thread;
            });

    private boolean completed;

    /** One finished segment: its text, and either the artifact or why it failed. */
    private record Spoken(
            String text,
            Artifact artifact,
            String failure,
            long synthMs,
            double audioSeconds,
            int audioBytes
    ) {}

    public VoiceSpeechStream(
            Supplier<VoiceModelProvider> providerSource,
            SpeechSynthesisTool.ArtifactStorer artifactStorer,
            Supplier<String> sessionIdSource,
            BooleanSupplier cancelled,
            Listener listener
    ) {
        this.providerSource = Objects.requireNonNull(providerSource, "providerSource");
        this.artifactStorer = Objects.requireNonNull(artifactStorer, "artifactStorer");
        this.sessionIdSource = Objects.requireNonNull(sessionIdSource, "sessionIdSource");
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** Feeds one token of the streaming answer. */
    public synchronized void accept(String chunk) {
        if (completed || isCancelled() || chunk == null || chunk.isEmpty()) {
            return;
        }
        pending.append(chunk);
        String segment;
        while ((segment = takeReadySegment()) != null) {
            submit(segment);
        }
    }

    /**
     * Flushes the tail, waits for every queued segment, and emits the whole recording.
     * Blocks, so the caller can emit its terminal frame afterwards — the client stops reading
     * at that frame and would silently drop anything sent later.
     */
    public void complete(String finalText) {
        synchronized (this) {
            if (completed) {
                return;
            }
            completed = true;
            if (!isCancelled()) {
                if (pending.length() > 0) {
                    String tail = pending.toString();
                    pending.setLength(0);
                    submit(tail);
                }
                if (sequence.get() == 0 && finalText != null && !finalText.isBlank()) {
                    // An answer that arrived without token events would otherwise go unspoken.
                    submit(finalText);
                }
            }
        }
        drain();
        if (isCancelled()) {
            return;
        }
        emitMerged(finalText);
    }

    @Override
    public void close() {
        completed = true;
        worker.shutdownNow();
    }

    /** Package-private for tests: the next speakable segment, or null if not ready yet. */
    synchronized String takeReadySegment() {
        int minimum = sequence.get() == 0 ? FIRST_SEGMENT_MIN_CHARS : SEGMENT_MIN_CHARS;
        int cut = findCut(minimum);
        if (cut < 0) {
            return null;
        }
        String segment = pending.substring(0, cut);
        pending.delete(0, cut);
        return segment;
    }

    private int findCut(int minimum) {
        int withinCap = Math.min(pending.length(), SEGMENT_MAX_CHARS);
        for (int i = minimum; i < withinCap; i++) {
            if (TERMINATORS.indexOf(pending.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        if (pending.length() < SEGMENT_MAX_CHARS) {
            return -1;
        }
        // Nothing to break on inside the cap: prefer a natural pause, else cut outright.
        for (int i = SEGMENT_MAX_CHARS - 1; i >= minimum; i--) {
            if (SOFT_TERMINATORS.indexOf(pending.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return SEGMENT_MAX_CHARS;
    }

    private void submit(String rawSegment) {
        String text = cleanForSpeech(rawSegment);
        if (text.isEmpty()) {
            return;
        }
        int seq = sequence.getAndIncrement();
        outstanding.incrementAndGet();
        try {
            worker.execute(() -> {
                try {
                    speak(seq, text);
                } finally {
                    outstanding.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            outstanding.decrementAndGet();
        }
    }

    private void speak(int seq, String text) {
        if (isCancelled()) {
            return;
        }
        long startedAt = System.nanoTime();
        String failure = null;
        Artifact artifact = null;
        double audioSeconds = 0;
        int audioBytes = 0;
        try {
            VoiceModelProvider provider = providerSource.get();
            byte[] audio = provider.synthesize(text, provider.defaultVoice());
            if (audio == null || audio.length == 0) {
                throw new IllegalStateException("语音合成返回空音频");
            }
            audioSeconds = audioSeconds(audio);
            audioBytes = audio.length;
            audioBySequence.put(seq, audio);
            artifact = artifactStorer.store(
                    audio,
                    "speech-segment-" + UUID.randomUUID() + "."
                            + provider.synthesizeFileExtension(),
                    provider.synthesizeMimeType(),
                    sessionIdSource.get());
        } catch (Exception e) {
            failure = messageOf(e);
        }
        spokenBySequence.put(seq, new Spoken(
                text, artifact, failure,
                (System.nanoTime() - startedAt) / 1_000_000, audioSeconds, audioBytes));
        emitInOrder();
    }

    /**
     * Releases finished segments in sequence order. With several workers a later segment
     * finishes first, and the client plays them back to back — it cannot reorder them itself.
     */
    private void emitInOrder() {
        synchronized (emitLock) {
            while (true) {
                int seq = nextToEmit.get();
                Spoken spoken = spokenBySequence.remove(seq);
                if (spoken == null) {
                    return;
                }
                if (spoken.failure() != null) {
                    log.warn("[Voice] segment {} failed: {}", seq, spoken.failure());
                    listener.onSegmentFailure(seq, spoken.text(), spoken.failure());
                } else {
                    // audio= is the real playback length and synth= the real synthesis cost:
                    // together they say whether the client runs dry, without guessing at a
                    // speaking rate. bytes= lets the client's own log line be matched to this
                    // one, so a dropped segment is visible end to end.
                    log.info("[Voice] segment {} emitted: {} chars, audio={}s, bytes={},"
                                    + " synth={}ms, backlog={}",
                            seq, spoken.text().length(), spoken.audioSeconds(),
                            spoken.audioBytes(), spoken.synthMs(), outstanding.get());
                    listener.onSegment(seq, spoken.text(), spoken.artifact());
                }
                nextToEmit.incrementAndGet();
            }
        }
    }

    /** Playback length of a segment, or 0 when the bytes carry no readable header. */
    private static double audioSeconds(byte[] audio) {
        WavFile wav = WavFile.parse(audio);
        return wav != null ? wav.durationSeconds() : 0;
    }

    private void drain() {
        worker.shutdown();
        long timeoutSeconds = (long) Math.max(1, outstanding.get())
                * Math.max(1, providerSource.get().timeoutSeconds());
        try {
            if (!worker.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }

    private void emitMerged(String finalText) {
        // Workers finish out of order, so reassemble by sequence rather than by arrival.
        List<byte[]> parts = audioBySequence.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
        // Nothing speakable in the answer: the same situation as a blank reply, which stays
        // silent rather than reporting a failure the user cannot act on.
        if (parts.isEmpty()) {
            return;
        }
        try {
            VoiceModelProvider provider = providerSource.get();
            String mimeType = provider.synthesizeMimeType();
            byte[] merged = VoiceAudioMerger.merge(parts, mimeType);
            Artifact artifact = artifactStorer.store(
                    merged,
                    "speech-" + UUID.randomUUID() + "." + provider.synthesizeFileExtension(),
                    mimeType,
                    sessionIdSource.get());
            listener.onComplete(artifact, parts.size(), finalText);
        } catch (Exception e) {
            listener.onCompleteFailure(messageOf(e));
        }
    }

    private boolean isCancelled() {
        return cancelled.getAsBoolean();
    }

    /**
     * Markdown is written for the eye, not the ear: asterisks and backticks get read aloud as
     * literal noise, and a URL read character by character is worse than useless.
     */
    static String cleanForSpeech(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw
                .replaceAll("```[\\s\\S]*?```", " ")
                .replaceAll("`([^`]*)`", "$1")
                .replaceAll("!\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("https?://\\S+", " ")
                .replaceAll("[*_#>]+", "")
                .replaceAll("\\s+", " ");
        return text.trim();
    }

    private static String messageOf(Exception e) {
        return e.getMessage() != null && !e.getMessage().isBlank()
                ? e.getMessage()
                : e.getClass().getSimpleName();
    }
}
