package com.harness.core.runtime;

import com.harness.core.env.EnvConfig;

/** Runtime boundary for validating and atomically activating Web-managed model settings. */
public interface ModelConfigurationRuntime {

    /**
     * Build and validate a candidate without changing the active runtime.
     * The returned update is activated only after persistence succeeds.
     */
    PreparedUpdate prepare(EnvConfig candidateConfiguration);

    @FunctionalInterface
    interface PreparedUpdate {
        void activate();
    }
}
