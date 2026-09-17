package com.harness.server;

import com.harness.agent.graph.GraphSpaceAccessService;
import com.harness.graph.build.GraphMutationCommitter;
import com.harness.graph.model.GraphChangeSet;
import com.harness.graph.model.GraphMutationResult;
import com.harness.graph.model.GraphNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GraphSpaceBindingRegistrarTest {

    private final GraphMutationCommitter delegate = mock(GraphMutationCommitter.class);
    private final GraphSpaceAccessService graphSpaceAccess = mock(GraphSpaceAccessService.class);
    private final GraphSpaceBindingRegistrar registrar =
            new GraphSpaceBindingRegistrar(delegate, graphSpaceAccess);

    @Test
    void registersTheDefaultTenantOnceAMutationCommits() {
        GraphChangeSet changeSet = changeSet();
        when(delegate.commit(changeSet))
                .thenReturn(new GraphMutationResult("request-1", true, 1, 0));

        var result = registrar.commit(changeSet);

        assertThat(result).isEqualTo(new GraphMutationResult("request-1", true, 1, 0));
        verify(graphSpaceAccess).registerBinding("000000", "graph-a", "schema-a");
    }

    @Test
    void doesNotRegisterWhenTheMutationDidNotCommit() {
        GraphChangeSet changeSet = changeSet();
        when(delegate.commit(changeSet))
                .thenReturn(new GraphMutationResult("request-1", false, 0, 0));

        registrar.commit(changeSet);

        verifyNoInteractions(graphSpaceAccess);
    }

    private static GraphChangeSet changeSet() {
        return new GraphChangeSet(
                "request-1", "graph-a", "schema-a",
                List.of(new GraphNode("student-1", Set.of("Student"), Map.of("name", "Alex"))),
                List.of(), Set.of(), Set.of());
    }
}
