package com.harness.tool.knowledge.authority;

import com.harness.core.knowledge.KnowledgeSourceType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeSourcePurgeGuardTest {

    @Test
    void retainIfReferenced_countsOnlyRetainedDecisionsByBoundedSourceType() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        when(repository.isSourceReferenced(KnowledgeSourceType.TRACE, "trace-1"))
                .thenReturn(true);
        when(repository.isSourceReferenced(KnowledgeSourceType.TRACE, "trace-2"))
                .thenReturn(false);
        KnowledgeSourcePurgeGuard guard = new KnowledgeSourcePurgeGuard(repository);

        assertThat(guard.retainIfReferenced(KnowledgeSourceType.TRACE, "trace-1"))
                .isTrue();
        assertThat(guard.retainIfReferenced(KnowledgeSourceType.TRACE, "trace-2"))
                .isFalse();
        assertThat(guard.metricsSnapshot().get("TRACE")).isEqualTo(1L);
        assertThat(guard.metricsSnapshot().get("SESSION_MESSAGE")).isZero();
    }
}
