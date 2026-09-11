package com.harness.graph.build;

import com.harness.graph.model.GraphChangeSet;
import com.harness.graph.model.GraphMutationResult;
import com.harness.graph.model.GraphNode;
import com.harness.graph.model.GraphRelation;
import com.harness.tool.knowledge.GraphSpaceWikiCompiler;
import com.harness.tool.knowledge.authority.KnowledgeGraphMutationJob;
import com.harness.tool.knowledge.authority.KnowledgeGraphMutationJobStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphMutationSagaServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

    @Test
    void commitsGraphThenWikiAndCompletesBinding() {
        GraphMutationCommitter graphCommitter = mock(GraphMutationCommitter.class);
        GraphSpaceWikiCompiler wikiCompiler = mock(GraphSpaceWikiCompiler.class);
        KnowledgeGraphMutationJobStore jobStore = mock(KnowledgeGraphMutationJobStore.class);
        GraphChangeSet changeSet = changeSet();
        KnowledgeGraphMutationJob pending = job(
                changeSet, KnowledgeGraphMutationJob.Status.PENDING, 0, null, null);
        KnowledgeGraphMutationJob claimedPending = job(
                changeSet, KnowledgeGraphMutationJob.Status.PENDING, 1, null, null);
        KnowledgeGraphMutationJob graphCommitted = job(
                changeSet, KnowledgeGraphMutationJob.Status.GRAPH_COMMITTED, 1, 2, 1);
        KnowledgeGraphMutationJob claimedGraph = job(
                changeSet, KnowledgeGraphMutationJob.Status.GRAPH_COMMITTED, 2, 2, 1);
        KnowledgeGraphMutationJob completed = job(
                changeSet, KnowledgeGraphMutationJob.Status.KNOWLEDGE_COMMITTED, 2, 2, 1);
        GraphMutationResult graphResult = new GraphMutationResult("request-1", true, 2, 1);
        when(jobStore.register(changeSet, null, null, NOW)).thenReturn(pending);
        when(jobStore.findById("request-1")).thenReturn(
                Optional.of(pending), Optional.of(graphCommitted), Optional.of(completed));
        when(jobStore.claim("request-1", NOW)).thenReturn(
                Optional.of(claimedPending), Optional.of(claimedGraph));
        when(graphCommitter.commit(changeSet)).thenReturn(graphResult);
        when(wikiCompiler.synchronize(changeSet, graphResult)).thenReturn("revision-1");
        when(jobStore.markGraphCommitted("request-1", graphResult, NOW))
                .thenReturn(graphCommitted);
        when(jobStore.completeKnowledge("request-1", "revision-1", NOW))
                .thenReturn(completed);
        GraphMutationSagaService service = new GraphMutationSagaService(
                graphCommitter, wikiCompiler, jobStore,
                Clock.fixed(NOW, ZoneOffset.UTC), 5);

        assertThat(service.commit(changeSet)).isEqualTo(graphResult);

        var ordered = inOrder(graphCommitter, jobStore, wikiCompiler);
        ordered.verify(graphCommitter).commit(changeSet);
        ordered.verify(jobStore).markGraphCommitted("request-1", graphResult, NOW);
        ordered.verify(wikiCompiler).synchronize(changeSet, graphResult);
        ordered.verify(jobStore).completeKnowledge("request-1", "revision-1", NOW);
    }

    @Test
    void graphFailureIsPersistedForRetryWithoutWritingWiki() {
        GraphMutationCommitter graphCommitter = mock(GraphMutationCommitter.class);
        GraphSpaceWikiCompiler wikiCompiler = mock(GraphSpaceWikiCompiler.class);
        KnowledgeGraphMutationJobStore jobStore = mock(KnowledgeGraphMutationJobStore.class);
        GraphChangeSet changeSet = changeSet();
        KnowledgeGraphMutationJob pending = job(
                changeSet, KnowledgeGraphMutationJob.Status.PENDING, 0, null, null);
        KnowledgeGraphMutationJob claimed = job(
                changeSet, KnowledgeGraphMutationJob.Status.PENDING, 1, null, null);
        when(jobStore.register(changeSet, null, null, NOW)).thenReturn(pending);
        when(jobStore.findById("request-1")).thenReturn(Optional.of(pending));
        when(jobStore.claim("request-1", NOW)).thenReturn(Optional.of(claimed));
        when(graphCommitter.commit(changeSet))
                .thenThrow(new IllegalStateException("neo4j unavailable"));
        GraphMutationSagaService service = new GraphMutationSagaService(
                graphCommitter, wikiCompiler, jobStore,
                Clock.fixed(NOW, ZoneOffset.UTC), 5);

        assertThatThrownBy(() -> service.commit(changeSet))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("neo4j unavailable");

        verify(jobStore).reschedule(eq("request-1"),
                eq(NOW.plusSeconds(60)), eq("neo4j unavailable"));
        verify(wikiCompiler, never()).synchronize(any(), any());
    }

    @Test
    void wikiFailureRetriesFromGraphCommittedWithoutReapplyingNeo4j() {
        GraphMutationCommitter graphCommitter = mock(GraphMutationCommitter.class);
        GraphSpaceWikiCompiler wikiCompiler = mock(GraphSpaceWikiCompiler.class);
        KnowledgeGraphMutationJobStore jobStore = mock(KnowledgeGraphMutationJobStore.class);
        GraphChangeSet changeSet = changeSet();
        KnowledgeGraphMutationJob graphCommitted = job(
                changeSet, KnowledgeGraphMutationJob.Status.GRAPH_COMMITTED, 2, 2, 1);
        KnowledgeGraphMutationJob claimed = job(
                changeSet, KnowledgeGraphMutationJob.Status.GRAPH_COMMITTED, 3, 2, 1);
        when(jobStore.register(changeSet, null, null, NOW)).thenReturn(graphCommitted);
        when(jobStore.findById("request-1")).thenReturn(Optional.of(graphCommitted));
        when(jobStore.claim("request-1", NOW)).thenReturn(Optional.of(claimed));
        when(wikiCompiler.synchronize(eq(changeSet), any()))
                .thenThrow(new IllegalStateException("mysql unavailable"));
        GraphMutationSagaService service = new GraphMutationSagaService(
                graphCommitter, wikiCompiler, jobStore,
                Clock.fixed(NOW, ZoneOffset.UTC), 5);

        assertThatThrownBy(() -> service.commit(changeSet))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mysql unavailable");

        verify(graphCommitter, never()).commit(any());
        verify(jobStore).reschedule(
                "request-1", NOW.plusSeconds(240), "mysql unavailable");
        verify(jobStore, never()).markFailed(any(), any(), any());
    }

    @Test
    void exhaustedAttemptBudgetPersistsTerminalFailure() {
        GraphMutationCommitter graphCommitter = mock(GraphMutationCommitter.class);
        GraphSpaceWikiCompiler wikiCompiler = mock(GraphSpaceWikiCompiler.class);
        KnowledgeGraphMutationJobStore jobStore = mock(KnowledgeGraphMutationJobStore.class);
        GraphChangeSet changeSet = changeSet();
        KnowledgeGraphMutationJob pending = job(
                changeSet, KnowledgeGraphMutationJob.Status.PENDING, 4, null, null);
        KnowledgeGraphMutationJob claimed = job(
                changeSet, KnowledgeGraphMutationJob.Status.PENDING, 5, null, null);
        when(jobStore.register(changeSet, null, null, NOW)).thenReturn(pending);
        when(jobStore.findById("request-1")).thenReturn(Optional.of(pending));
        when(jobStore.claim("request-1", NOW)).thenReturn(Optional.of(claimed));
        when(graphCommitter.commit(changeSet))
                .thenThrow(new IllegalStateException("neo4j unavailable"));
        GraphMutationSagaService service = new GraphMutationSagaService(
                graphCommitter, wikiCompiler, jobStore,
                Clock.fixed(NOW, ZoneOffset.UTC), 5);

        assertThatThrownBy(() -> service.commit(changeSet))
                .isInstanceOf(IllegalStateException.class);

        verify(jobStore).markFailed("request-1", NOW, "neo4j unavailable");
        verify(jobStore, never()).reschedule(any(), any(), any());
        verify(wikiCompiler, never()).synchronize(any(), any());
    }

    @Test
    void completedRequestReturnsStoredResultWithoutRepeatingEitherStore() {
        GraphMutationCommitter graphCommitter = mock(GraphMutationCommitter.class);
        GraphSpaceWikiCompiler wikiCompiler = mock(GraphSpaceWikiCompiler.class);
        KnowledgeGraphMutationJobStore jobStore = mock(KnowledgeGraphMutationJobStore.class);
        GraphChangeSet changeSet = changeSet();
        KnowledgeGraphMutationJob completed = job(
                changeSet, KnowledgeGraphMutationJob.Status.KNOWLEDGE_COMMITTED, 2, 2, 1);
        when(jobStore.register(changeSet, null, null, NOW)).thenReturn(completed);
        GraphMutationSagaService service = new GraphMutationSagaService(
                graphCommitter, wikiCompiler, jobStore,
                Clock.fixed(NOW, ZoneOffset.UTC), 5);

        assertThat(service.commit(changeSet))
                .isEqualTo(new GraphMutationResult("request-1", true, 2, 1));
        verify(graphCommitter, never()).commit(any());
        verify(wikiCompiler, never()).synchronize(any(), any());
    }

    @Test
    void canonicalPayloadHashIgnoresInputCollectionOrder() {
        GraphChangeSet original = changeSet();
        GraphChangeSet reordered = new GraphChangeSet(
                original.requestId(), original.graphId(), original.schemaId(),
                original.nodes().reversed(), original.relations(),
                original.deleteNodeIds(), original.deleteRelationIds());
        GraphChangeSetCodec codec = new GraphChangeSetCodec();

        GraphChangeSetCodec.Encoded first = codec.encode(original);
        GraphChangeSetCodec.Encoded second = codec.encode(reordered);

        assertThat(second.payloadHash()).isEqualTo(first.payloadHash());
        assertThat(codec.decode(first.canonicalPayload())).isEqualTo(original);
    }

    private static KnowledgeGraphMutationJob job(
            GraphChangeSet changeSet,
            KnowledgeGraphMutationJob.Status status,
            int attempts,
            Integer nodeCount,
            Integer relationCount
    ) {
        return new KnowledgeGraphMutationJob(
                changeSet, null, null, "hash", "{}", status, attempts,
                NOW, (status == KnowledgeGraphMutationJob.Status.PENDING && attempts > 0
                        || status == KnowledgeGraphMutationJob.Status.GRAPH_COMMITTED)
                        ? NOW : null,
                nodeCount, relationCount,
                nodeCount == null ? null : NOW,
                status == KnowledgeGraphMutationJob.Status.KNOWLEDGE_COMMITTED ? NOW : null,
                null, NOW);
    }

    private static GraphChangeSet changeSet() {
        return new GraphChangeSet(
                "request-1", "graph-a", "schema-a",
                List.of(
                        new GraphNode("student-1", Set.of("Student"),
                                Map.of("name", "Alice")),
                        new GraphNode("student-2", Set.of("Student"),
                                Map.of("name", "Bob"))),
                List.of(new GraphRelation(
                        "knows-1", "student-1", "student-2", "KNOWS", Map.of())),
                Set.of("old-node"), Set.of("old-relation"));
    }
}
