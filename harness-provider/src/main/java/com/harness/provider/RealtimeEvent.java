package com.harness.provider;

import com.harness.core.model.ModelUsage;

import java.util.Arrays;

/** Unified server event emitted by realtime providers and the tool bridge. */
public record RealtimeEvent(
        Type type,
        String sessionId,
        String text,
        byte[] audio,
        RealtimeToolCall toolCall,
        ModelUsage usage,
        String error
) {
    public enum Type {
        SESSION_READY,
        USER_SPEECH_STARTED,
        USER_SPEECH_STOPPED,
        USER_TRANSCRIPT_DELTA,
        USER_TRANSCRIPT_DONE,
        ASSISTANT_TEXT_DELTA,
        ASSISTANT_TEXT_DONE,
        ASSISTANT_AUDIO_DELTA,
        TOOL_CALL,
        TOOL_RESULT,
        CONFIRMATION_REQUIRED,
        CONFIRMATION_RESOLVED,
        TURN_DONE,
        USAGE,
        ERROR,
        CLOSED
    }

    public RealtimeEvent {
        if (type == null) {
            throw new IllegalArgumentException("event type is required");
        }
        audio = audio == null ? null : Arrays.copyOf(audio, audio.length);
    }

    @Override
    public byte[] audio() {
        return audio == null ? null : Arrays.copyOf(audio, audio.length);
    }

    public static RealtimeEvent simple(Type type, String sessionId) {
        return new RealtimeEvent(type, sessionId, null, null, null, null, null);
    }

    public static RealtimeEvent text(Type type, String sessionId, String text) {
        return new RealtimeEvent(type, sessionId, text, null, null, null, null);
    }

    public static RealtimeEvent error(String sessionId, String error) {
        return new RealtimeEvent(Type.ERROR, sessionId, null, null, null, null, error);
    }
}
