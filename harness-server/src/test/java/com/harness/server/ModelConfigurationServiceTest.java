package com.harness.server;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import io.javalin.http.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelConfigurationServiceTest {

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void resetConfiguration() {
        EnvConfig.init(Map.of());
    }

    @Test
    void returnsEveryModelSectionAndRedactsApiKeys() throws Exception {
        EnvConfig.init(Map.of(
                EnvKey.MODEL_CHAT_PROVIDER, "openai",
                EnvKey.MODEL_CHAT_API_KEY, "secret-chat-key",
                EnvKey.MODEL_CHAT_MODEL, "gpt-test",
                EnvKey.MODEL_API_MAX_CONCURRENT, "12",
                EnvKey.RERANK_ENABLED, "true",
                EnvKey.TOOL_IMAGE_GEN_MODEL, "image-test"
        ));

        ModelConfigurationService.ModelConfigurationResponse response =
                service().current();

        assertThat(response.sections())
                .extracting(ModelConfigurationService.ModelConfigurationSection::id)
                .containsExactly(
                        "global",
                        "chat",
                        "vision",
                        "voice",
                        "embedding",
                        "rerank",
                        "realtime",
                        "classifier",
                        "imageGeneration",
                        "videoGeneration");

        ModelConfigurationService.ModelConfigurationField apiKey = response.sections().stream()
                .flatMap(section -> section.fields().stream())
                .filter(field -> EnvKey.MODEL_CHAT_API_KEY.equals(field.key()))
                .findFirst()
                .orElseThrow();
        assertThat(apiKey.sensitive()).isTrue();
        assertThat(apiKey.configured()).isTrue();
        assertThat(apiKey.value()).isNull();
        assertThat(apiKey.effectiveValue()).isNull();

        assertThat(response.sections().stream()
                .flatMap(section -> section.fields().stream()))
                .noneMatch(field -> "secret-chat-key".equals(field.value()));
    }

    @Test
    void distinguishesConfiguredValuesFromUnsetValues() throws Exception {
        EnvConfig.init(Map.of(EnvKey.MODEL_CHAT_MODEL, "gpt-test"));

        ModelConfigurationService.ModelConfigurationResponse response =
                service().current();

        ModelConfigurationService.ModelConfigurationSection chat = response.sections().stream()
                .filter(section -> "chat".equals(section.id()))
                .findFirst()
                .orElseThrow();
        assertThat(chat.fields())
                .anySatisfy(field -> {
                    assertThat(field.key()).isEqualTo(EnvKey.MODEL_CHAT_MODEL);
                    assertThat(field.value()).isEqualTo("gpt-test");
                    assertThat(field.configured()).isTrue();
                })
                .anySatisfy(field -> {
                    assertThat(field.key()).isEqualTo(EnvKey.MODEL_CHAT_BASE_URL);
                    assertThat(field.value()).isNull();
                    assertThat(field.configured()).isFalse();
                });
    }

    @Test
    void persistsAndActivatesUpdatesWhileClearsRestoreBaseValues() throws Exception {
        Path envFile = temporaryDirectory.resolve("model-config.env");
        Files.writeString(envFile, String.join(System.lineSeparator(),
                EnvKey.MODEL_CHAT_MODEL + "=old-model",
                EnvKey.MODEL_CHAT_PROVIDER + "=openai",
                ""));
        EnvConfig.init(Map.of(
                EnvKey.MODEL_CHAT_MODEL, "old-model",
                EnvKey.MODEL_CHAT_PROVIDER, "openai"));
        ModelConfigurationService service = new ModelConfigurationService(
                EnvConfig.get(),
                new ModelConfigurationFileStore(envFile));

        ModelConfigurationService.ModelConfigurationResponse response = service.update(
                new ModelConfigurationService.ModelConfigurationUpdateRequest(
                        Map.of(
                                EnvKey.MODEL_CHAT_MODEL, "new model",
                                EnvKey.MODEL_CHAT_API_KEY, "secret-key"),
                        List.of(EnvKey.MODEL_CHAT_PROVIDER)));

        String saved = Files.readString(envFile);
        assertThat(saved)
                .contains(EnvKey.MODEL_CHAT_MODEL + "=\"new model\"")
                .contains(EnvKey.MODEL_CHAT_API_KEY + "=secret-key")
                .doesNotContain(EnvKey.MODEL_CHAT_PROVIDER + "=");
        assertThat(response.runtimeSynchronized()).isTrue();
        ModelConfigurationService.ModelConfigurationField provider = response.sections().stream()
                .flatMap(section -> section.fields().stream())
                .filter(field -> EnvKey.MODEL_CHAT_PROVIDER.equals(field.key()))
                .findFirst()
                .orElseThrow();
        assertThat(provider.value()).isEqualTo("openai");
        assertThat(provider.configured()).isTrue();
        assertThat(provider.managed()).isFalse();
        assertThat(provider.effectiveValue()).isEqualTo("openai");
        ModelConfigurationService.ModelConfigurationField apiKey = response.sections().stream()
                .flatMap(section -> section.fields().stream())
                .filter(field -> EnvKey.MODEL_CHAT_API_KEY.equals(field.key()))
                .findFirst()
                .orElseThrow();
        assertThat(apiKey.value()).isNull();
        assertThat(apiKey.configured()).isTrue();
        assertThat(apiKey.managed()).isTrue();
        assertThat(EnvConfig.get().getString(EnvKey.MODEL_CHAT_MODEL))
                .isEqualTo("new model");
    }

    @Test
    void rollsBackPersistentFileWhenRuntimeActivationFails() throws Exception {
        Path configFile = temporaryDirectory.resolve("model-config.env");
        Files.writeString(configFile, EnvKey.MODEL_CHAT_MODEL + "=old-model\n");
        EnvConfig.init(Map.of(EnvKey.MODEL_CHAT_MODEL, "old-model"));
        ModelConfigurationService service = new ModelConfigurationService(
                EnvConfig.get(),
                new ModelConfigurationFileStore(configFile),
                candidate -> () -> {
                    throw new IllegalStateException("activation failed");
                });

        assertThatThrownBy(() -> service.update(
                new ModelConfigurationService.ModelConfigurationUpdateRequest(
                        Map.of(EnvKey.MODEL_CHAT_MODEL, "new-model"),
                        List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("activation failed");

        assertThat(Files.readString(configFile))
                .contains(EnvKey.MODEL_CHAT_MODEL + "=old-model")
                .doesNotContain("new-model");
        assertThat(EnvConfig.get().getString(EnvKey.MODEL_CHAT_MODEL))
                .isEqualTo("old-model");
    }

    @Test
    void rejectsUnknownKeysMultilineValuesAndConflictingOperations() {
        ModelConfigurationService service = service();

        assertThatThrownBy(() -> service.update(
                new ModelConfigurationService.ModelConfigurationUpdateRequest(
                        Map.of("HARNESS_UNKNOWN_MODEL", "value"),
                        List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");
        assertThatThrownBy(() -> service.update(
                new ModelConfigurationService.ModelConfigurationUpdateRequest(
                        Map.of(EnvKey.MODEL_CHAT_MODEL, "first\nsecond"),
                        List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single line");
        assertThatThrownBy(() -> service.update(
                new ModelConfigurationService.ModelConfigurationUpdateRequest(
                        Map.of(EnvKey.MODEL_CHAT_MODEL, "model"),
                        List.of(EnvKey.MODEL_CHAT_MODEL))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("updated and cleared");
    }

    @Test
    void handlerReturnsTheTypedConfigurationResponse() throws Exception {
        EnvConfig.init(Map.of(EnvKey.MODEL_CHAT_PROVIDER, "openai"));
        Context context = mock(Context.class);
        when(context.json(any())).thenReturn(context);
        ModelConfigurationHandler handler = new ModelConfigurationHandler(
                service());

        handler.get(context);

        ArgumentCaptor<Object> response = ArgumentCaptor.forClass(Object.class);
        verify(context).json(response.capture());
        assertThat(response.getValue())
                .isInstanceOf(ModelConfigurationService.ModelConfigurationResponse.class);
    }

    private ModelConfigurationService service() {
        return new ModelConfigurationService(
                EnvConfig.get(),
                new ModelConfigurationFileStore(temporaryDirectory.resolve(".env")));
    }
}
