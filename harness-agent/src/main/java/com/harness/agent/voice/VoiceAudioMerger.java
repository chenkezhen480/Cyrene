package com.harness.agent.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Joins the per-segment recordings back into one file.
 *
 * <p>The streamed segments are played as they arrive, but the conversation should keep a single
 * replayable recording rather than one file per sentence — the alternative is a column of tiny
 * players that grows every time the session is reopened.</p>
 *
 * <p>WAV cannot be joined by concatenation. Each segment carries its own {@code RIFF} header
 * declaring its own length, so a stack of them makes a file that stops playing after the first
 * segment. The PCM payloads are extracted and wrapped in one fresh header instead.</p>
 *
 * <p>Container formats whose frames describe themselves (mp3 and friends) do join by
 * concatenation, so those are still handled that way.</p>
 */
final class VoiceAudioMerger {

    private VoiceAudioMerger() {
    }

    static byte[] merge(List<byte[]> parts, String mimeType) {
        if (parts.isEmpty()) {
            return new byte[0];
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }

        List<WavFile> decoded = new ArrayList<>(parts.size());
        for (byte[] part : parts) {
            WavFile wav = WavFile.parse(part);
            if (wav == null) {
                decoded = null;
                break;
            }
            decoded.add(wav);
        }

        if (decoded != null) {
            return mergeWav(decoded);
        }
        if (expectsWav(mimeType)) {
            // Emitting the bare concatenation would produce a file that looks playable and
            // stops after the first sentence. Better to say the recording could not be built.
            throw new IllegalStateException("无法解析语音合成返回的 WAV，已放弃合并以免生成损坏文件");
        }
        return concatenate(parts);
    }

    private static byte[] mergeWav(List<WavFile> segments) {
        WavFile template = segments.get(0);
        for (WavFile segment : segments) {
            if (!template.sameFormatAs(segment)) {
                // Mixing sample rates or channel counts under one header would play at the
                // wrong speed rather than fail outright.
                throw new IllegalStateException("语音分段格式不一致，无法合并为单个音频");
            }
        }
        int total = 0;
        for (WavFile segment : segments) {
            total += segment.payload().length;
        }
        byte[] payload = new byte[total];
        int offset = 0;
        for (WavFile segment : segments) {
            System.arraycopy(segment.payload(), 0, payload, offset, segment.payload().length);
            offset += segment.payload().length;
        }
        return WavFile.writeWith(template, payload);
    }

    private static byte[] concatenate(List<byte[]> parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] merged = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, merged, offset, part.length);
            offset += part.length;
        }
        return merged;
    }

    private static boolean expectsWav(String mimeType) {
        return mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("audio/wav");
    }
}
