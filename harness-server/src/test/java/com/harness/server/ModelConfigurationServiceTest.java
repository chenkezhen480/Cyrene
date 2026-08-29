package com.harness.server;

import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigFile;
import com.harness.core.modelconfig.ModelConfigKey;
import com.harness.core.runtime.ModelConfigurationRuntime;
import io.javalin.http.Context;
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

    @Test
    void returnsEverySectionWithChineseLabelsAndRedactsCredentials() throws Exception {
        ModelConfig config = ModelConfig.of(Map.of(
                ModelConfigKey.CHAT_PROVIDER, "openai",
                ModelConfigKey.CHAT_API_KEY, "secret-chat-key",
                ModelConfigKey.CHAT_MODEL, "gpt-test"));
        ModelConfigurationService service = service(config, new TestRuntime(config));

        ModelConfigurationService.ModelConfigurationResponse response = service.current();

        assertThat(response.sections())
                .extracting(ModelConfigurationService.ModelConfigurationSection::id)
                .containsExactly("global", "chat", "vision", "voice", "embedding",
                        "rerank", "realtime", "smallTask", "imageGeneration",
                        "videoGeneration");
        ModelConfigurationService.ModelConfigurationField model = field(
                response, ModelConfigKey.CHAT_MODEL);
        assertThat(model.label()).isEqualTo("对话模型名称");
        assertThat(model.value()).isEqualTo("gpt-test");

        ModelConfigurationService.ModelConfigurationField apiKey = field(
                response, ModelConfigKey.CHAT_API_KEY);
        assertThat(apiKey.label()).isEqualTo("对话模型密钥");
        assertThat(apiKey.sensitive()).isTrue();
        assertThat(apiKey.configured()).isTrue();
        assertThat(apiKey.value()).isNull();
    }

    @Test
    void persistsAndActivatesUpdatesFromTheSingleModelConfigSource() throws Exception {
        ModelConfig initial = ModelConfig.of(Map.of(
                ModelConfigKey.CHAT_MODEL, "old-model",
                ModelConfigKey.CHAT_PROVIDER, "openai"));
        TestRuntime runtime = new TestRuntime(initial);
        ModelConfigurationService service = service(initial, runtime);

        ModelConfigurationService.ModelConfigurationResponse response = service.update(
                new ModelConfigurationService.ModelConfigurationUpdateRequest(
                        Map.of(
                                ModelConfigKey.CHAT_MODEL, "new model",
                                ModelConfigKey.CHAT_API_KEY, "secret-key"),
                        List.of(ModelConfigKey.CHAT_PROVIDER)));

        String saved = Files.readString(configPath());
        assertThat(saved)
                .contains("chat.model=\"new model\"")
                .contains("chat.apiKey=secret-key")
                .doesNotContain("chat.provider=");
        assertThat(runtime.currentConfiguration().getString(ModelConfigKey.CHAT_MODEL))
                .isEqualTo("new model");
        assertThat(response.runtimeSynchronized()).isTrue();
        assertThat(field(response, ModelConfigKey.CHAT_PROVIDER).configured()).isFalse();
    }

    @Test
    void rollsBackPersistentFileWhenRuntimeActivationFails() throws Exception {
        ModelConfig initial = ModelConfig.of(Map.of(ModelConfigKey.CHAT_MODEL, "old-model"));
        ModelConfigurationRuntime failingRuntime = new ModelConfigurationRuntime() {
            @Override public ModelConfig currentConfiguration() { return initial; }
            @Override public PreparedUpdate prepare(ModelConfig candidateConfiguration) {
                return () -> { throw new IllegalStateException("activation failed"); };
            }
        };
        ModelConfigurationService service = service(initial, failingRuntime);

        assertThatThrownBy(() -> service.update(
                new ModelConfigurationService.ModelConfigurationUpdateRequest(
                        Map.of(ModelConfigKey.CHAT_MODEL, "new-model"), List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("activation failed");

        assertThat(Files.readString(configPath()))
                .contains("chat.model=old-model")
                .doesNotContain("new-model");
    }

    @Test
    void rejectsUnknownKeysMultilineValuesAndConflictingOperations() throws Exception {
        ModelConfig initial = ModelConfig.of(Map.of(ModelConfigKey.CHAT_MODEL, "old-model"));
        ModelConfigurationService service = service(initial, new TestRuntime(initial));

        assertThatThrownBy(() -> service.update(
                new ModelConfigurationService.ModelConfigurationUpdateRequest(
                        Map.of("legacy.model", "value"), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");
        assertThatThrownBy(() -> service.update(
                new ModelConfigurationService.ModelConfigurationUpdateRequest(
                        Map.of(ModelConfigKey.CHAT_MODEL, "first\nsecond"), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single line");
        assertThatThrownBy(() -> service.update(
                new ModelConfigurationService.ModelConfigurationUpdateRequest(
                        Map.of(ModelConfigKey.CHAT_MODEL, "model"),
                        List.of(ModelConfigKey.CHAT_MODEL))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("updated and cleared");
    }

    @Test
    void handlerReturnsTheTypedConfigurationResponse() throws Exception {
        ModelConfig config = ModelConfig.of(Map.of(ModelConfigKey.CHAT_PROVIDER, "openai"));
        Context context = mock(Context.class);
        when(context.json(any())).thenReturn(context);
        ModelConfigurationHandler handler = new ModelConfigurationHandler(
                service(config, new TestRuntime(config)));

        handler.get(context);

        ArgumentCaptor<Object> response = ArgumentCaptor.forClass(Object.class);
        verify(context).json(response.capture());
        assertThat(response.getValue())
                .isInstanceOf(ModelConfigurationService.ModelConfigurationResponse.class);
    }

    private ModelConfigurationService service(
            ModelConfig config,
            ModelConfigurationRuntime runtime
    ) throws Exception {
        ModelConfigFile file = new ModelConfigFile(configPath());
        file.replace(config);
        return new ModelConfigurationService(file, runtime);
    }

    private Path configPath() {
        return temporaryDirectory.resolve("model.conf");
    }

    private static ModelConfigurationService.ModelConfigurationField field(
            ModelConfigurationService.ModelConfigurationResponse response,
            String key
    ) {
        return response.sections().stream()
                .flatMap(section -> section.fields().stream())
                .filter(field -> key.equals(field.key()))
                .findFirst()
                .orElseThrow();
    }

    private static final class TestRuntime implements ModelConfigurationRuntime {
        private ModelConfig active;

        private TestRuntime(ModelConfig active) { this.active = active; }

        @Override public ModelConfig currentConfiguration() { return active; }

        @Override public PreparedUpdate prepare(ModelConfig candidateConfiguration) {
            return () -> active = candidateConfiguration;
        }
    }
}
