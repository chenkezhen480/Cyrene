package com.harness.agent;

import com.harness.core.model.SubAgentLifecycleEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class SubAgentRunScopeTest {

    @Test
    void lifecycleTransportFailureDoesNotFailSubAgentExecution() {
        SubAgentRunScope scope = new SubAgentRunScope(
                "run-1", 1, event -> { throw new IllegalStateException("closed stream"); });

        assertThatCode(() -> scope.publish(new SubAgentLifecycleEvent(
                "call-1", "task-1", SubAgentLifecycleEvent.Status.RUNNING, "")))
                .doesNotThrowAnyException();
    }
}
