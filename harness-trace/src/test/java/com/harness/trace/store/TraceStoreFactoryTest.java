package com.harness.trace.store;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TraceStoreFactoryTest {
    @Test
    void missingConfigurationDefaultsToMysql() {
        assertThat(createWithSetting(null)).isInstanceOf(MysqlTraceStore.class);
    }

    @Test
    void removedBackendIsRejectedInsteadOfFallingBack() {
        assertThatThrownBy(() -> createWithSetting("sqlite"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unknown audit store: sqlite");
    }

    @Test
    void explicitMysqlAndDisabledConfigurationsRemainSupported() {
        assertThat(createWithSetting("mysql")).isInstanceOf(MysqlTraceStore.class);
        assertThat(createWithSetting("none")).isInstanceOf(TraceStoreFactory.NoOpTraceStore.class);
    }

    private TraceStore createWithSetting(String setting) {
        var configuration = mock(EnvConfig.class);
        when(configuration.getString(eq(EnvKey.AUDIT_STORE), anyString()))
                .thenAnswer(call -> setting == null ? call.getArgument(1) : setting);
        try (var environment = mockStatic(EnvConfig.class)) {
            environment.when(EnvConfig::get).thenReturn(configuration);
            return TraceStoreFactory.create();
        }
    }
}
