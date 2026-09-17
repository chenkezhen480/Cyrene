package com.harness.agent.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A RIFF/WAVE file, read by walking its chunks.
 *
 * <p>Assuming the canonical 44-byte layout — {@code fmt } at 12, {@code data} at 36 — breaks
 * on anything that carries an extra chunk. Zhipu's synthesizer ships a 250-byte {@code AIGC}
 * chunk between the two, which pushed {@code data} to offset 294 and made every offset-based
 * read return another field's bytes.</p>
 *
 * <p>RIFF is an extensible container: unknown chunks are skipped, not treated as corruption.
 * A chunk declaring a size that runs past the end is clamped rather than rejected, because
 * some encoders get that field wrong and the audio behind it is still fine.</p>
 */
final class WavFile {

    private static final int CONTAINER_HEADER_BYTES = 12;
    private static final int CHUNK_HEADER_BYTES = 8;
    private static final int CANONICAL_HEADER_BYTES = 44;

    private final int audioFormat;
    private final int channels;
    private final int sampleRate;
    private final int bitsPerSample;
    private final byte[] payload;

    private WavFile(
            int audioFormat, int channels, int sampleRate, int bitsPerSample, byte[] payload) {
        this.audioFormat = audioFormat;
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.bitsPerSample = bitsPerSample;
        this.payload = payload;
    }

    /** Returns null when the bytes are not a readable wav, never a half-parsed one. */
    static WavFile parse(byte[] bytes) {
        if (bytes == null || bytes.length < CONTAINER_HEADER_BYTES
                || !asciiEquals(bytes, 0, "RIFF")
                || !asciiEquals(bytes, 8, "WAVE")) {
            return null;
        }

        int audioFormat = -1;
        int channels = -1;
        int sampleRate = -1;
        int bitsPerSample = -1;
        byte[] payload = null;

        int offset = CONTAINER_HEADER_BYTES;
        while (offset + CHUNK_HEADER_BYTES <= bytes.length) {
            String chunkId = ascii(bytes, offset);
            long declaredSize = uint32(bytes, offset + 4);
            int payloadOffset = offset + CHUNK_HEADER_BYTES;
            long available = bytes.length - (long) payloadOffset;
            if (declaredSize < 0 || declaredSize > available) {
                declaredSize = available;
            }

            if ("fmt ".equals(chunkId) && declaredSize >= 16) {
                audioFormat = uint16(bytes, payloadOffset);
                channels = uint16(bytes, payloadOffset + 2);
                sampleRate = (int) uint32(bytes, payloadOffset + 4);
                bitsPerSample = uint16(bytes, payloadOffset + 14);
            } else if ("data".equals(chunkId)) {
                payload = Arrays.copyOfRange(
                        bytes, payloadOffset, (int) (payloadOffset + declaredSize));
                break;
            }
            // Everything else — including vendor chunks — is skipped.

            // Chunks are word aligned: an odd size is followed by one padding byte.
            offset = (int) (payloadOffset + declaredSize + (declaredSize % 2));
        }

        if (payload == null || payload.length == 0
                || audioFormat < 0 || channels <= 0 || sampleRate <= 0) {
            return null;
        }
        return new WavFile(audioFormat, channels, sampleRate, bitsPerSample, payload);
    }

    int bytesPerSecond() {
        return sampleRate * bytesPerFrame();
    }

    /** Playback length of the data chunk — not of the file, which carries chunk overhead. */
    double durationSeconds() {
        int rate = bytesPerSecond();
        return rate > 0 ? (double) payload.length / rate : 0;
    }

    byte[] payload() {
        return payload;
    }

    /** Two segments can only share one header when every format field matches. */
    boolean sameFormatAs(WavFile other) {
        return audioFormat == other.audioFormat
                && channels == other.channels
                && sampleRate == other.sampleRate
                && bitsPerSample == other.bitsPerSample;
    }

    /** Wraps raw PCM in a single canonical header, using this file's format as the template. */
    static byte[] writeWith(WavFile template, byte[] payload) {
        int blockAlign = template.bytesPerFrame();
        ByteBuffer out = ByteBuffer
                .allocate(CANONICAL_HEADER_BYTES + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        out.put(asciiBytes("RIFF"));
        out.putInt(CANONICAL_HEADER_BYTES - 8 + payload.length);
        out.put(asciiBytes("WAVE"));
        out.put(asciiBytes("fmt "));
        out.putInt(16);
        out.putShort((short) template.audioFormat);
        out.putShort((short) template.channels);
        out.putInt(template.sampleRate);
        out.putInt(template.sampleRate * blockAlign);
        out.putShort((short) blockAlign);
        out.putShort((short) template.bitsPerSample);
        out.put(asciiBytes("data"));
        out.putInt(payload.length);
        out.put(payload);
        return out.array();
    }

    private int bytesPerFrame() {
        return channels * Math.max(1, bitsPerSample / 8);
    }

    private static boolean asciiEquals(byte[] bytes, int offset, String text) {
        for (int i = 0; i < text.length(); i++) {
            if (bytes[offset + i] != (byte) text.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static String ascii(byte[] bytes, int offset) {
        return new String(bytes, offset, 4, StandardCharsets.US_ASCII);
    }

    private static byte[] asciiBytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static int uint16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static long uint32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24);
    }
}
