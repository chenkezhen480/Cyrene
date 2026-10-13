package com.harness.agent.experiment;

import com.harness.core.modelconfig.ModelConfigFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class ExperimentModelConfigurationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void startupCompletesOnlyBlankKeysBeforeTheStabilityDigest() throws Exception {
        Path copy = temporaryDirectory.resolve("model.conf");
        Files.writeString(copy, "chat.provider=openai\nchat.model=synthetic-model\n");
        var settingsBefore = new ModelConfigFile(copy).read().values();
        var addedKeys = CyreneExperimentIT.initializeCopiedModel(copy);
        assertThat(addedKeys).contains("chat.apiFormat");
        assertThat(Files.readString(copy)).contains("chat.apiFormat=" + System.lineSeparator());
        assertThat(new ModelConfigFile(copy).read().values()).isEqualTo(settingsBefore);
        assertThat(CyreneExperimentIT.initializeCopiedModel(copy)).isEmpty();
    }
}
