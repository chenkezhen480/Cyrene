package com.harness.core.modelconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
    }
}
