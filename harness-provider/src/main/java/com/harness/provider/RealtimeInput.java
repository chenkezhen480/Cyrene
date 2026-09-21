package com.harness.provider;

import java.util.Arrays;

/** Inputs accepted by every realtime session without exposing provider JSON. */
public sealed interface RealtimeInput
        permits RealtimeInput.TextInput, RealtimeInput.AudioChunk,
                RealtimeInput.ImageFrame, RealtimeInput.CommitTurn {

    record TextInput(String text) implements RealtimeInput {
        public TextInput {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("text must not be blank");
            }
        }
    }

    record AudioChunk(byte[] data) implements RealtimeInput {
        public AudioChunk {
            if (data == null || data.length == 0) {
                throw new IllegalArgumentException("audio data must not be empty");
            }
            data = Arrays.copyOf(data, data.length);
        }

        @Override
        public byte[] data() {
            return Arrays.copyOf(data, data.length);
        }
    }

    record ImageFrame(byte[] jpeg) implements RealtimeInput {
        public ImageFrame {
            if (jpeg == null || jpeg.length == 0) {
                throw new IllegalArgumentException("image data must not be empty");
            }
            jpeg = Arrays.copyOf(jpeg, jpeg.length);
        }

        @Override
        public byte[] jpeg() {
            return Arrays.copyOf(jpeg, jpeg.length);
        }
    }

    record CommitTurn() implements RealtimeInput {
    }
}
