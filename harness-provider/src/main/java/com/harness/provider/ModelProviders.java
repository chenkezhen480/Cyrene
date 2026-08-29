package com.harness.provider;

import java.util.Objects;

/**
 * Immutable set of model providers used by one application runtime.
 *
 * <p>Provider selection happens once during application composition. Request
 * execution consumes this set and does not read mutable configuration sources.</p>
 */
public record ModelProviders(
        ChatModelProvider chat,
        VisionModelProvider vision,
        VoiceModelProvider voice,
        EmbeddingModelProvider embedding,
        RerankModelProvider rerank,
        RealtimeModelProvider realtime,
        SmallTaskModelProvider smallTask
) {

    public ModelProviders {
        Objects.requireNonNull(chat, "chat");
        Objects.requireNonNull(vision, "vision");
        Objects.requireNonNull(voice, "voice");
        Objects.requireNonNull(embedding, "embedding");
        Objects.requireNonNull(rerank, "rerank");
        Objects.requireNonNull(realtime, "realtime");
        Objects.requireNonNull(smallTask, "smallTask");
    }
}
