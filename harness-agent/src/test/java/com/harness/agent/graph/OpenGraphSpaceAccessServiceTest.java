package com.harness.agent.graph;

import com.harness.core.model.AgentContext;
import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.graph.store.KnowledgeGraphStore;
import com.harness.graph.model.GraphSpacePageRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void allowsAnUnscopedCallerAndTheStandaloneDefault() {
        // Graph concepts are global and stored with a null tenant, and a standalone deployment's
        // Wiki requests carry no tenantId at all. Treating that as "another tenant" made every
        // Schema Wiki read fail with a binding-table error.
        KnowledgeGraphStore store = mock(KnowledgeGraphStore.class);
        when(store.listGraphSpaces(any(GraphSpacePageRequest.class))).thenReturn(
                new PageResponse<>(List.of(), new PageInfo(100, "", false)));
        var service = new OpenGraphSpaceAccessService(store);

        assertThatCode(() -> service.requireReadable(null, "graph-1", "schema-1"))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.requireReadable("", "graph-1", "schema-1"))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.requireReadable(
                AgentContext.DEFAULT_TENANT_ID, "graph-1", "schema-1"))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.listReadable(null, 100, "")).doesNotThrowAnyException();
    }
}
