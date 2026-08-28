package com.harness.provider;

import com.harness.core.model.CancellationToken;
import com.harness.core.model.ModelUsage;
import com.harness.core.text.TextTokenEstimator;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * Owns one active immutable provider generation and stable delegating providers.
 * A fair lifecycle lock lets complete Agent runs retain their generation while
 * a configuration update waits to publish the next generation.
 */
public final class ModelProviderRuntime {

    private final AtomicReference<ModelProviders> active;
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);
    private final ModelProviders delegates;

    public ModelProviderRuntime(ModelProviders initialProviders) {
        this.active = new AtomicReference<>(Objects.requireNonNull(
                initialProviders, "initialProviders"));
        this.delegates = new ModelProviders(
                new ChatDelegate(),
                new VisionDelegate(),
                new VoiceDelegate(),
                new EmbeddingDelegate(),
                new RerankDelegate(),
                new RealtimeDelegate(),
                new ClassifierDelegate());
    }

    /** Stable providers suitable for injection into long-lived components. */
    public ModelProviders delegates() {
        return delegates;
    }

    public ModelProviders current() {
        return active.get();
    }

    public <T> T withCurrent(Function<ModelProviders, T> action) {
        Objects.requireNonNull(action, "action");
        ReentrantReadWriteLock.ReadLock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            return action.apply(active.get());
        } finally {
            readLock.unlock();
        }
    }

    public void withCurrentVoid(java.util.function.Consumer<ModelProviders> action) {
        withCurrent(providers -> {
            action.accept(providers);
            return null;
        });
    }

    /** Publish a fully built provider generation after persistent configuration succeeds. */
    public void activate(ModelProviders providers, Runnable beforePublish) {
        Objects.requireNonNull(providers, "providers");
        Objects.requireNonNull(beforePublish, "beforePublish");
        ReentrantReadWriteLock.WriteLock writeLock = lifecycleLock.writeLock();
        writeLock.lock();
        try {
            beforePublish.run();
            active.set(providers);
        } finally {
            writeLock.unlock();
        }
    }

    private final class ChatDelegate implements ChatModelProvider {
        private final ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return withCurrent(providers ->
                        providers.chat().chatModel().chat(request));
            }
        };

        @Override public ChatModel chatModel() { return chatModel; }
        @Override public StreamingChatModel streamingModel() {
            return current().chat().streamingModel();
        }
        @Override public String providerName() { return current().chat().providerName(); }
        @Override public String modelName() { return current().chat().modelName(); }
        @Override public int contextWindow() { return current().chat().contextWindow(); }
        @Override public ModelUsage modelUsage(ChatResponse response, long latencyMs) {
            return current().chat().modelUsage(response, latencyMs);
        }
        @Override public ChatRequestParameters planningRequestParameters(
                Boolean enableThinking,
                List<ToolSpecification> toolSpecifications
        ) {
            return withCurrent(providers -> providers.chat().planningRequestParameters(
                    enableThinking, toolSpecifications));
        }
    }

    private final class VisionDelegate implements VisionModelProvider {
        @Override public String analyze(String prompt, Image image) {
            return withCurrent(providers -> providers.vision().analyze(prompt, image));
        }
        @Override public String analyze(String prompt, List<Image> images) {
            return withCurrent(providers -> providers.vision().analyze(prompt, images));
        }
        @Override public boolean isAvailable() { return current().vision().isAvailable(); }
        @Override public String providerName() { return current().vision().providerName(); }
        @Override public String modelName() { return current().vision().modelName(); }
    }

    private final class VoiceDelegate implements VoiceModelProvider {
        @Override public String transcribe(InputStream audio, String mimeType) {
            return withCurrent(providers -> providers.voice().transcribe(audio, mimeType));
        }
        @Override public byte[] synthesize(String text, String voice) {
            return withCurrent(providers -> providers.voice().synthesize(text, voice));
        }
        @Override public void streamSynthesize(
                SynthesisRequest request,
                AudioStreamCallback callback,
                CancellationToken cancellationToken
        ) {
            withCurrentVoid(providers -> providers.voice().streamSynthesize(
                    request, callback, cancellationToken));
        }
        @Override public VoiceCapabilities capabilities() {
            return current().voice().capabilities();
        }
        @Override public boolean isTranscribeAvailable() {
            return current().voice().isTranscribeAvailable();
        }
        @Override public boolean isSynthesizeAvailable() {
            return current().voice().isSynthesizeAvailable();
        }
        @Override public String providerName() { return current().voice().providerName(); }
    }

    private final class EmbeddingDelegate implements EmbeddingModelProvider {
        @Override public Embedding embed(String text) {
            return withCurrent(providers -> providers.embedding().embed(text));
        }
        @Override public Embedding embed(TextSegment segment) {
            return withCurrent(providers -> providers.embedding().embed(segment));
        }
        @Override public List<Embedding> embedAll(List<TextSegment> segments) {
            return withCurrent(providers -> providers.embedding().embedAll(segments));
        }
        @Override public int dimension() { return current().embedding().dimension(); }
        @Override public boolean isAvailable() { return current().embedding().isAvailable(); }
        @Override public String providerName() { return current().embedding().providerName(); }
        @Override public String modelName() { return current().embedding().modelName(); }
        @Override public TextTokenEstimator tokenEstimator() {
            return current().embedding().tokenEstimator();
        }
    }

    private final class RerankDelegate implements RerankModelProvider {
        @Override public double score(String query, String document) {
            return withCurrent(providers -> providers.rerank().score(query, document));
        }
        @Override public List<RankedResult> rerank(
                String query, List<String> documents, int topN) {
            return withCurrent(providers -> providers.rerank().rerank(query, documents, topN));
        }
        @Override public boolean isAvailable() { return current().rerank().isAvailable(); }
        @Override public String providerName() { return current().rerank().providerName(); }
        @Override public String modelName() { return current().rerank().modelName(); }
    }

    private final class RealtimeDelegate implements RealtimeModelProvider {
        @Override public String startSession(RealtimeEventHandler handler) {
            return withCurrent(providers -> providers.realtime().startSession(handler));
        }
        @Override public void send(String sessionId, byte[] data) {
            withCurrentVoid(providers -> providers.realtime().send(sessionId, data));
        }
        @Override public void endSession(String sessionId) {
            withCurrentVoid(providers -> providers.realtime().endSession(sessionId));
        }
        @Override public boolean isAvailable() { return current().realtime().isAvailable(); }
        @Override public String providerName() { return current().realtime().providerName(); }
    }

    private final class ClassifierDelegate implements ClassifierModelProvider {
        private final ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return withCurrent(providers ->
                        providers.classifier().chatModel().chat(request));
            }
        };

        @Override public ChatModel chatModel() {
            return isAvailable() ? chatModel : null;
        }
        @Override public String providerName() { return current().classifier().providerName(); }
        @Override public String modelName() { return current().classifier().modelName(); }
        @Override public boolean isAvailable() { return current().classifier().isAvailable(); }
    }
}
