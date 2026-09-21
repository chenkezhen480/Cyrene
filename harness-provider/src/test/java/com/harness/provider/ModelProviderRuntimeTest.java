package com.harness.provider;

import org.junit.jupiter.api.Test;
import com.harness.core.modelconfig.ModelConfig;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelProviderRuntimeTest {

    @Test
    void activationWaitsForCurrentGenerationLeaseAndThenPublishesNewProviders()
            throws Exception {
        ModelProviders first = providers("first-model");
        ModelProviders second = providers("second-model");
        ModelConfig firstConfig = ModelConfig.empty();
        ModelConfig secondConfig = ModelConfig.of(java.util.Map.of(
                com.harness.core.modelconfig.ModelConfigKey.CHAT_MODEL, "second-model"));
        ModelProviderRuntime runtime = new ModelProviderRuntime(first, firstConfig);
        CountDownLatch leaseAcquired = new CountDownLatch(1);
        CountDownLatch releaseLease = new CountDownLatch(1);

        CompletableFuture<Void> activeRun = CompletableFuture.runAsync(() ->
                runtime.withCurrent(current -> {
                    assertThat(current.chat().modelName()).isEqualTo("first-model");
                    leaseAcquired.countDown();
                    try {
                        releaseLease.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(exception);
                    }
                    return null;
                }));
        assertThat(leaseAcquired.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> activation = CompletableFuture.runAsync(() ->
                runtime.activate(second, secondConfig, () -> {}));
        Thread.sleep(50);
        assertThat(activation.isDone()).isFalse();
        assertThat(runtime.delegates().chat().modelName()).isEqualTo("first-model");

        releaseLease.countDown();
        activeRun.get(5, TimeUnit.SECONDS);
        activation.get(5, TimeUnit.SECONDS);
        assertThat(runtime.delegates().chat().modelName()).isEqualTo("second-model");
        assertThat(runtime.delegates().routing().route("test").needsWebSearch()).isTrue();
    }

    @Test
    void voiceDelegateForwardsTheConfiguredOutputFormat() {
        ModelProviderRuntime runtime = new ModelProviderRuntime(
                providers("model", new FixedFormatVoiceProvider("audio/wav", "wav")),
                ModelConfig.empty());

        // The delegate is its own VoiceModelProvider. A method it does not explicitly forward
        // falls back to the interface default, which silently labels wav bytes as mp3.
        assertThat(runtime.delegates().voice().synthesizeMimeType()).isEqualTo("audio/wav");
        assertThat(runtime.delegates().voice().synthesizeFileExtension()).isEqualTo("wav");
    }

    private record FixedFormatVoiceProvider(String mimeType, String extension)
            implements VoiceModelProvider {
        @Override public String transcribe(java.io.InputStream audio, String type) {
            return "";
        }
        @Override public byte[] synthesize(String text, String voice) { return new byte[0]; }
        @Override public String synthesizeMimeType() { return mimeType; }
        @Override public String synthesizeFileExtension() { return extension; }
        @Override public String providerName() { return "fixed"; }
    }

    private static ModelProviders providers(String chatModelName) {
        return providers(chatModelName, mock(VoiceModelProvider.class));
    }

    private static ModelProviders providers(String chatModelName, VoiceModelProvider voice) {
        ChatModelProvider chat = mock(ChatModelProvider.class);
        when(chat.modelName()).thenReturn(chatModelName);
        when(chat.providerName()).thenReturn("test");
        return new ModelProviders(
                chat,
                mock(VisionModelProvider.class),
                voice,
                mock(EmbeddingModelProvider.class),
                mock(RerankModelProvider.class),
                mock(RealtimeModelProvider.class),
                mock(SmallTaskModelProvider.class), query -> new RoutingModelProvider.Decision(
                        com.harness.core.model.ThinkingLevel.LOW, false, "second-model".equals(chatModelName)));
    }
}
