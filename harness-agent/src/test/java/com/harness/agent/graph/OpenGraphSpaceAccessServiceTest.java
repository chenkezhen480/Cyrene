package com.harness.agent.graph;

import com.harness.graph.store.KnowledgeGraphStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class OpenGraphSpaceAccessServiceTest {

    @Test
    void rejectsNonDefaultTenantWhenBindingTableIsNotInstalled() {
        var service = new OpenGraphSpaceAccessService(mock(KnowledgeGraphStore.class));

        assertThatThrownBy(() -> service.requireReadable(
                "tenant-1",
                "graph-1",
                "schema-1"
        )).isInstanceOf(GraphSpaceAccessException.class)
                .hasMessageContaining("binding table");
    }
}
