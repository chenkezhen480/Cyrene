package com.harness.core.modelconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelConfigFileTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void missingFileIsEmptyAndFirstAdministrativeSaveCreatesIt() throws Exception {
        Path path = temporaryDirectory.resolve("data").resolve("model.conf");
        ModelConfigFile file = new ModelConfigFile(path);

        assertThat(file.read().values()).isEmpty();
        assertThat(path).doesNotExist();

        file.replace(ModelConfig.of(Map.of(ModelConfigKey.CHAT_PROVIDER, "none")));

        assertThat(path).exists();
        assertThat(Files.readString(path)).contains("chat.provider=none");
    }

    @Test
    void readsSemanticKeysAndAtomicallyReplacesTheCompleteFile() throws Exception {
        Path path = temporaryDirectory.resolve("model.conf");
        Files.writeString(path, "# comment\nchat.model=old-model\nchat.apiKey=secret\n");
        ModelConfigFile file = new ModelConfigFile(path);

        assertThat(file.read().values()).containsEntry(ModelConfigKey.CHAT_MODEL, "old-model");

        file.replace(ModelConfig.of(Map.of(
                ModelConfigKey.CHAT_MODEL, "new model",
                ModelConfigKey.SMALL_TASK_PROVIDER, "openai")));

        assertThat(Files.readString(path))
                .contains("chat.model=\"new model\"")
                .contains("smallTask.provider=openai")
                .contains("chat.apiKey=");
        assertThat(file.read().values()).doesNotContainKey(ModelConfigKey.CHAT_API_KEY);
    }

    @Test
    void replacementIncludesEveryVisibleKeyAndOnlyConfiguredHiddenKeys() throws Exception {
        ModelConfigFile file = new ModelConfigFile(temporaryDirectory.resolve("model.conf"));
        file.replace(ModelConfig.of(Map.of(ModelConfigKey.CHAT_MODEL, "model",
                ModelConfigKey.CHAT_THINKING_DIALECT, "qwen")));

        var lines = Files.readAllLines(file.path());
        for (var definition : ModelConfigKey.DEFINITIONS) {
            if (!definition.hidden())
                assertThat(lines).anyMatch(line -> line.startsWith(definition.key() + "="));
        }
        assertThat(lines).contains("routing.provider=", "routing.apiKey=", "routing.baseUrl=",
                "routing.model=", "routing.timeoutSeconds=", "chat.thinkingDialect=qwen")
                .doesNotContain("chat.thinking=", "chat.thinkingBudgets=", "chat.thinkingMaxLevel=");
        assertThat(file.read().values()).containsExactlyInAnyOrderEntriesOf(Map.of(
                ModelConfigKey.CHAT_MODEL, "model", ModelConfigKey.CHAT_THINKING_DIALECT, "qwen"));
    }

    @Test
    void configuredJevSettingsRoundTripWithoutAddingBlankValuesToTheSnapshot() throws Exception {
        ModelConfigFile file = new ModelConfigFile(temporaryDirectory.resolve("model.conf"));
        Map<String, String> routing = Map.of(ModelConfigKey.ROUTING_PROVIDER, "jev",
                ModelConfigKey.ROUTING_API_KEY, "unit test routing key", ModelConfigKey.ROUTING_BASE_URL,
                "https://example.invalid/v1", ModelConfigKey.ROUTING_MODEL, "jev-test",
                ModelConfigKey.ROUTING_TIMEOUT_SECONDS, "17");

        file.replace(ModelConfig.of(routing));

        assertThat(file.read().values()).containsExactlyInAnyOrderEntriesOf(routing);
        assertThat(file.read().getInt(ModelConfigKey.ROUTING_TIMEOUT_SECONDS, 30)).isEqualTo(17);
    }

    @Test
    void rejectsUnknownAndDuplicateKeys() throws Exception {
        Path unknown = temporaryDirectory.resolve("unknown.conf");
        Files.writeString(unknown, "legacy.model=value\n");
        assertThatThrownBy(() -> new ModelConfigFile(unknown).read())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown model configuration key");

        Path duplicate = temporaryDirectory.resolve("duplicate.conf");
        Files.writeString(duplicate, "chat.model=first\nchat.model=second\n");
        assertThatThrownBy(() -> new ModelConfigFile(duplicate).read())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate model.conf key");
    }

    @Test
    void typedAccessorsReportTheInvalidKey() {
        ModelConfig config = ModelConfig.of(Map.of(
                ModelConfigKey.CHAT_MAX_TOKENS, "many",
                ModelConfigKey.CHAT_THINKING, "sometimes"));

        assertThatThrownBy(() -> config.getInt(ModelConfigKey.CHAT_MAX_TOKENS, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ModelConfigKey.CHAT_MAX_TOKENS);
        assertThatThrownBy(() -> config.getBool(ModelConfigKey.CHAT_THINKING, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("true or false");
    }
}
