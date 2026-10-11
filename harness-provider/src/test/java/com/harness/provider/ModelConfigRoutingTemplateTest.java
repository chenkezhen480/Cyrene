package com.harness.provider;

import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigFile;
import com.harness.core.modelconfig.ModelConfigKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ModelConfigRoutingTemplateTest {
    @TempDir Path directory;

    @Test
    void generatedBlankRoutingFieldsDoNotConstructAJevProviderOrCredentials() throws Exception {
        ModelConfigFile file = new ModelConfigFile(directory.resolve("model.conf"));
        file.replace(ModelConfig.empty());

        assertThat(Files.readAllLines(file.path())).contains("routing.provider=", "routing.apiKey=",
                "routing.baseUrl=", "routing.model=", "routing.timeoutSeconds=");
        ModelConfig config = file.read();
        assertThat(config.values()).isEmpty();
        assertThat(config.getString(ModelConfigKey.ROUTING_API_KEY)).isNull();
        assertThat(ModelProviderFactory.createRouting(config)).isSameAs(RoutingModelProvider.DISABLED);
    }
}
