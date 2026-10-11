package com.harness.core.modelconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelConfigInitializerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void migratesLegacyCredentialsAndRenamedGroupsWhenFileIsMissing() throws Exception {
        ModelConfigFile file = new ModelConfigFile(
                temporaryDirectory.resolve("data/model.conf"));

        ModelConfigInitializer.InitializationResult result =
                ModelConfigInitializer.initializeIfMissing(file, Map.of(
                        "HARNESS_MODEL_CHAT_API_KEY", "chat-secret",
                        "HARNESS_MODEL_CLASSIFIER_API_KEY", "small-task-secret",
                        "HARNESS_TOOL_IMAGE_GEN_API_KEY", "image-secret",
                        "HARNESS_MODEL_VOICE_TIMEOUT_SECONDS", "120",
                        "HARNESS_MODEL_VISION_API_KEY", ""
                ));

        assertThat(result)
                .isEqualTo(ModelConfigInitializer.InitializationResult.MIGRATED_LEGACY);
        assertThat(file.read().values())
                .containsEntry(ModelConfigKey.CHAT_API_KEY, "chat-secret")
                .containsEntry(ModelConfigKey.SMALL_TASK_API_KEY, "small-task-secret")
                .containsEntry(ModelConfigKey.IMAGE_API_KEY, "image-secret")
                .containsEntry(ModelConfigKey.VOICE_TIMEOUT_SECONDS, "120")
                .doesNotContainKey(ModelConfigKey.VISION_API_KEY);
    }

    @Test
    void existingModelConfigIsNeverOverwrittenByLegacyValues() throws Exception {
        ModelConfigFile file = new ModelConfigFile(
                temporaryDirectory.resolve("model.conf"));
        file.replace(ModelConfig.of(Map.of(
                ModelConfigKey.CHAT_API_KEY, "current-secret")));

        ModelConfigInitializer.InitializationResult result =
                ModelConfigInitializer.initializeIfMissing(file, Map.of(
                "HARNESS_MODEL_CHAT_API_KEY", "legacy-secret"));

        assertThat(result)
                .isEqualTo(ModelConfigInitializer.InitializationResult.EXISTING);
        assertThat(file.read().getString(ModelConfigKey.CHAT_API_KEY))
                .isEqualTo("current-secret");
    }

    @Test
    void emptyLegacyConfigurationCreatesEmptyFileForWebFirstSetup() throws Exception {
        ModelConfigFile file = new ModelConfigFile(
                temporaryDirectory.resolve("model.conf"));

        assertThat(ModelConfigInitializer.initializeIfMissing(file, Map.of()))
                .isEqualTo(ModelConfigInitializer.InitializationResult.CREATED_EMPTY);
        assertThat(file.path()).exists();
        assertThat(file.read().values()).isEmpty();
        assertThat(Files.readAllLines(file.path())).contains("routing.provider=", "routing.apiKey=",
                "routing.baseUrl=", "routing.model=", "routing.timeoutSeconds=");
    }

    @Test
    void existingFileAppendsMissingVisibleKeysWithoutChangingValuesCommentsOrBlankAssignments() throws Exception {
        ModelConfigFile file = new ModelConfigFile(temporaryDirectory.resolve("model.conf"));
        String original = "# Operator notes\r\nchat.model=custom-model # keep this comment\r\n"
                + "routing.provider=jev\r\nrouting.apiKey=\"existing routing key\"\r\n"
                + "routing.model=\r\nchat.thinkingDialect=qwen\r\n# routing.baseUrl=comment only";
        Files.writeString(file.path(), original);

        assertThat(ModelConfigInitializer.initializeIfMissing(file,
                Map.of("HARNESS_MODEL_CHAT_MODEL", "legacy-model")))
                .isEqualTo(ModelConfigInitializer.InitializationResult.EXISTING);

        String completed = Files.readString(file.path());
        assertThat(completed).startsWith(original).contains("routing.baseUrl=", "routing.timeoutSeconds=");
        assertThat(file.read().values()).containsExactlyInAnyOrderEntriesOf(Map.of(
                ModelConfigKey.CHAT_MODEL, "custom-model", ModelConfigKey.ROUTING_PROVIDER, "jev",
                ModelConfigKey.ROUTING_API_KEY, "existing routing key", ModelConfigKey.CHAT_THINKING_DIALECT, "qwen"));
        assertThat(Files.readAllLines(file.path()).stream().filter(line -> line.equals("routing.model=")))
                .hasSize(1);
        FileTime marker = FileTime.from(Instant.parse("2000-01-01T00:00:00Z"));
        Files.setLastModifiedTime(file.path(), marker);

        ModelConfigInitializer.initializeIfMissing(file, Map.of("HARNESS_MODEL_CHAT_MODEL", "another-legacy-model"));

        assertThat(Files.readString(file.path())).isEqualTo(completed);
        assertThat(Files.getLastModifiedTime(file.path())).isEqualTo(marker);
    }

    @Test
    void invalidExistingFileIsReportedWithoutAppendingOrOverwritingIt() throws Exception {
        ModelConfigFile file = new ModelConfigFile(temporaryDirectory.resolve("model.conf"));
        for (var invalid : java.util.List.of("legacy.model=value\n", "chat.model=\nchat.model=again\n", "invalid line\n")) {
            Files.writeString(file.path(), invalid);

            assertThatThrownBy(() -> ModelConfigInitializer.initializeIfMissing(file, Map.of()))
                    .isInstanceOf(RuntimeException.class);
            assertThat(Files.readString(file.path())).isEqualTo(invalid);
        }
    }
}
