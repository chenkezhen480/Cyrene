package com.harness.server;

import com.harness.agent.graph.GraphSpaceAccessService;
import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.graph.model.GraphDeleteResult;
import com.harness.graph.model.GraphSpaceKey;
import com.harness.graph.model.GraphSpacePageRequest;
import com.harness.graph.model.GraphSpaceSummary;
import com.harness.graph.schema.GraphSchemaDetails;
import com.harness.graph.schema.GraphSchemaManagementService;
import com.harness.graph.store.GraphStoreException;
import com.harness.graph.store.KnowledgeGraphStore;
import com.harness.tool.knowledge.GraphSchemaWikiCompiler;
import com.harness.tool.knowledge.GraphSpaceWikiCompiler;
import com.harness.tool.knowledge.KnowledgeWikiService;
import com.harness.tool.knowledge.authority.KnowledgeRepository;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Cascading deletion of Graph Spaces and of the Schemas they are built on.
 *
 * <p>A Graph Space is three things at once: nodes and relations in Neo4j, tenant access rows in the
 * optional {@code graph_space_bindings} table, and a Wiki card. A Schema is a stored definition plus
 * its own Wiki card. Every step below is individually idempotent (a repeated Neo4j space delete
 * returns 0/0, a repeated binding delete matches no rows, a repeated deprecate returns early), so an
 * interrupted cascade is completed by calling it again — there is no cross-store transaction, only
 * convergent steps. The order matters and is deliberate:</p>
 *
 * <ol>
 *   <li>The Schema is disabled first, which unregisters it. That is the write fence: a concurrent
 *       {@code /api/graph/build} or node upsert fails against the registry instead of re-creating
 *       nodes in a space this cascade has already cleared.</li>
 *   <li>Graph Spaces are enumerated and deleted next.</li>
 *   <li>The Schema's Wiki card is deprecated before its definition is deleted. The reverse order
 *       would make a retry fail at {@code get(schemaId)} with 404 and leave the card visible
 *       forever, advertising a Schema that no longer exists.</li>
 * </ol>
 */
final class GraphDeletionService {

    private static final int PAGE_LIMIT = 100;

    private final KnowledgeGraphStore graphStore;
    private final GraphSpaceAccessService graphSpaceAccess;
    private final GraphSchemaManagementService schemaService;
    private final GraphSchemaWikiCompiler schemaWikiCompiler;
    private final GraphSpaceWikiCompiler spaceWikiCompiler;
    private final KnowledgeRepository repository;

    GraphDeletionService(
            KnowledgeGraphStore graphStore,
            GraphSpaceAccessService graphSpaceAccess,
            GraphSchemaManagementService schemaService,
            GraphSchemaWikiCompiler schemaWikiCompiler,
            GraphSpaceWikiCompiler spaceWikiCompiler,
            KnowledgeRepository repository
    ) {
        this.graphStore = Objects.requireNonNull(graphStore, "graphStore");
        this.graphSpaceAccess = Objects.requireNonNull(graphSpaceAccess, "graphSpaceAccess");
        this.schemaService = Objects.requireNonNull(schemaService, "schemaService");
        this.schemaWikiCompiler = Objects.requireNonNull(schemaWikiCompiler, "schemaWikiCompiler");
        this.spaceWikiCompiler = Objects.requireNonNull(spaceWikiCompiler, "spaceWikiCompiler");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    GraphSpaceDeletionResult deleteSpace(String graphId, String schemaId) {
        requireGraphProvider(graphStore);
        GraphDeleteResult graph = graphStore.deleteGraphSpace(new GraphSpaceKey(graphId, schemaId));
        int bindings = graphSpaceAccess.deleteBindings(graphId, schemaId);
        spaceWikiCompiler.deprecate(graphId, schemaId);
        return new GraphSpaceDeletionResult(
                graphId, schemaId, graph.deletedNodes(), graph.deletedRelations(), bindings);
    }

    SchemaDeletionResult deleteSchema(String schemaId) {
        // Refused rather than attempted: with the graph provider disabled the store cannot enumerate
        // or delete anything, so the cascade would report nothing deleted while the Schema's graph
        // data survived — and the orphan-card sweep below would run on a false premise.
        requireGraphProvider(graphStore);
        GraphSchemaDetails details = schemaService.get(schemaId);
        if (!details.editable()) {
            throw new IllegalStateException("SPI graph schemas are read-only: " + schemaId);
        }
        boolean wasEnabled = details.enabled();
        if (wasEnabled) {
            // Direct service call, not the handler path: the handler would also re-synchronize the
            // Wiki card, writing two revisions whose only purpose is to be replaced by deprecation.
            schemaService.disable(schemaId);
        }

        Set<GraphSpaceKey> spaces = spacesForSchema(schemaId);
        long deletedNodes = 0;
        long deletedRelations = 0;
        int deletedBindings = 0;
        for (GraphSpaceKey space : spaces) {
            GraphSpaceDeletionResult deleted;
            try {
                deleted = deleteSpace(space.graphId(), space.schemaId());
            } catch (GraphStoreException failure) {
                // Name the space that failed and what to do about it: the per-space deletion is
                // idempotent, so retrying the Schema delete resumes where this stopped, and removing
                // that one space from the graph data page is an equally valid way through.
                throw new GraphStoreException(
                        "Graph Space " + space.graphId() + " (" + space.schemaId()
                                + ") could not be deleted: " + failure.getMessage()
                                + " Retry, or delete that Graph Space from the graph data page"
                                + " and delete the Schema again.",
                        failure);
            }
            deletedNodes += deleted.deletedNodes();
            deletedRelations += deleted.deletedRelations();
            deletedBindings += deleted.deletedBindings();
        }

        // A Graph Space card also exists for spaces that hold no nodes at all — data that was written
        // and then deleted, or a space created through OKF import. The node-derived enumeration above
        // cannot see those, so their cards are deprecated here or they survive as orphans.
        for (GraphSpaceKey card : spaceCardsForSchema(schemaId)) {
            if (spaces.contains(card)) {
                continue;
            }
            spaceWikiCompiler.deprecate(card.graphId(), card.schemaId());
        }

        // Node-derived enumeration misses a binding row for a (graphId, schemaId) that has no nodes.
        deletedBindings += graphSpaceAccess.deleteBindingsBySchema(schemaId);
        schemaWikiCompiler.deprecate(schemaId);
        schemaService.delete(schemaId);
        return new SchemaDeletionResult(
                schemaId, true, wasEnabled, spaces.size(),
                deletedNodes, deletedRelations, deletedBindings);
    }

    /** Every Graph Space that still holds nodes for this Schema. */
    private Set<GraphSpaceKey> spacesForSchema(String schemaId) {
        Set<GraphSpaceKey> spaces = new LinkedHashSet<>();
        String cursor = "";
        while (true) {
            var page = graphStore.listGraphSpaces(new GraphSpacePageRequest(PAGE_LIMIT, cursor));
            page.items().stream()
                    .filter(item -> item.schemaId().equals(schemaId))
                    .map(item -> new GraphSpaceKey(item.graphId(), item.schemaId()))
                    .forEach(spaces::add);
            if (!page.pageInfo().hasMore()) {
                return spaces;
            }
            cursor = advancedCursor(cursor, page.pageInfo().nextCursor(), "Graph space");
        }
    }

    /**
     * Every stored Graph Space Wiki card for this Schema, read from the authority rather than from
     * Neo4j so cards without nodes are included. A card's namespace key is {@code graphId:schemaId}.
     */
    private Set<GraphSpaceKey> spaceCardsForSchema(String schemaId) {
        Set<GraphSpaceKey> spaces = new LinkedHashSet<>();
        var cursor = KnowledgeWikiService.parseCursor(null);
        while (true) {
            var page = repository.findManagementPage(
                    KnowledgeConceptType.GRAPH_SPACE, KnowledgeStatus.STABLE, cursor, PAGE_LIMIT);
            for (KnowledgeConcept concept : page.items()) {
                GraphSpaceKey key = parseSpaceNamespace(concept.namespaceKey());
                if (key != null && key.schemaId().equals(schemaId)) {
                    spaces.add(key);
                }
            }
            if (!page.pageInfo().hasMore()) {
                return spaces;
            }
            var next = KnowledgeWikiService.parseCursor(page.pageInfo().nextCursor());
            if (next == null || next.equals(cursor)) {
                throw new IllegalStateException("Graph space Wiki pagination did not advance");
            }
            cursor = next;
        }
    }

    /**
     * Reads back {@code graphId:schemaId}, the namespace key of a Graph Space card.
     *
     * <p>Split on the LAST colon: a schemaId is restricted to {@code [a-z][a-z0-9-]{1,63}} and so
     * never contains one, while a graphId is business-defined free text and freely can.</p>
     */
    private static GraphSpaceKey parseSpaceNamespace(String namespaceKey) {
        if (namespaceKey == null) {
            return null;
        }
        int separator = namespaceKey.lastIndexOf(':');
        if (separator <= 0 || separator == namespaceKey.length() - 1) {
            return null;
        }
        return new GraphSpaceKey(
                namespaceKey.substring(0, separator), namespaceKey.substring(separator + 1));
    }

    static void requireGraphProvider(KnowledgeGraphStore graphStore) {
        if ("none".equals(graphStore.providerName())) {
            throw new IllegalStateException(
                    "Graph deletion requires a graph provider; the current provider is 'none'");
        }
    }

    private static String advancedCursor(String cursor, String next, String what) {
        if (next == null || next.isBlank() || next.equals(cursor)) {
            throw new IllegalStateException(what + " pagination did not advance");
        }
        return next;
    }

    record GraphSpaceDeletionResult(
            String graphId,
            String schemaId,
            long deletedNodes,
            long deletedRelations,
            int deletedBindings
    ) {
    }

    record SchemaDeletionResult(
            String schemaId,
            boolean deleted,
            boolean disabled,
            int deletedSpaces,
            long deletedNodes,
            long deletedRelations,
            int deletedBindings
    ) {
    }
}
