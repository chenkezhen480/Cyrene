package com.harness.core.runtime;

import com.harness.core.modelconfig.ModelConfig;

/** Runtime boundary for validating and atomically activating Web-managed model settings. */
public interface ModelConfigurationRuntime {

    ModelConfig currentConfiguration();

    /**
     * Build and validate a candidate without changing the active runtime.
     * The returned update is activated only after persistence succeeds.
     */
    PreparedUpdate prepare(ModelConfig candidateConfiguration);

    @FunctionalInterface
    interface PreparedUpdate {
        void activate();
    }
}
