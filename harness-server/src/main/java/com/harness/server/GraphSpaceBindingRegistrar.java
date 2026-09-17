package com.harness.server;

import com.harness.agent.graph.GraphSpaceAccessService;
import com.harness.core.model.AgentContext;
import com.harness.graph.build.GraphMutationCommitter;
import com.harness.graph.model.GraphChangeSet;
import com.harness.graph.model.GraphMutationResult;

import java.util.Objects;

/**
 * Grants the creating tenant access to a Graph Space as soon as its first mutation commits.
 *
 * <p>Once {@code graph_space_bindings} exists, access to a Graph Space is enforced per tenant, and a
 * space without a binding row is invisible to everyone — including {@code query_graph} and the Wiki
 * card. A freshly built space would therefore look empty, so the tenant that produced the data is
 * granted {@code write} access automatically. An existing row is never modified: a narrower
 * permission or a disabled row set by an operator outranks this automatic grant.</p>
 *
 * <p>Wrapping the committer rather than editing each handler means every write path — confirmed
 * builds, raw change sets, and node/relation batches — is covered by construction.</p>
 *
 * <p>The graph management API is deliberately not tenant-scoped ({@link GraphRequestAuthenticator}
 * applies the server's authentication mode only), so the trusted tenant here is the default tenant:
 * the same rule {@link AgentContext#tenantId()} applies to a request that carries no tenantId.</p>
 */
final class GraphSpaceBindingRegistrar implements GraphMutationCommitter {

    private final GraphMutationCommitter delegate;
    private final GraphSpaceAccessService graphSpaceAccess;

    GraphSpaceBindingRegistrar(
            GraphMutationCommitter delegate,
            GraphSpaceAccessService graphSpaceAccess
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.graphSpaceAccess = Objects.requireNonNull(graphSpaceAccess, "graphSpaceAccess");
    }

    @Override
    public GraphMutationResult commit(GraphChangeSet changeSet) {
        GraphMutationResult result = delegate.commit(changeSet);
        if (result.committed()) {
            graphSpaceAccess.registerBinding(
                    AgentContext.DEFAULT_TENANT_ID, changeSet.graphId(), changeSet.schemaId());
        }
        return result;
    }
}
