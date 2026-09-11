package com.harness.graph.build;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.graph.model.GraphChangeSet;
import com.harness.graph.model.GraphMutationResult;
import com.harness.tool.knowledge.GraphSpaceWikiCompiler;
import com.harness.tool.knowledge.authority.KnowledgeGraphMutationJob;
import com.harness.tool.knowledge.authority.KnowledgeGraphMutationJobStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Durable requestId-based Saga across Neo4j and the MySQL Wiki authority. */
public final class GraphMutationSagaService implements GraphMutationCommitter {

    private static final Duration BASE_RETRY_DELAY = Duration.ofMinutes(1);
    private static final Duration MAX_RETRY_DELAY = Duration.ofHours(1);

    private final GraphMutationCommitter graphCommitter;
    private final GraphSpaceWikiCompiler wikiCompiler;
    private final KnowledgeGraphMutationJobStore jobStore;
    private final Clock clock;
    private final int maxAttempts;

    public GraphMutationSagaService(
            GraphMutationCommitter graphCommitter,
            GraphSpaceWikiCompiler wikiCompiler,
            KnowledgeGraphMutationJobStore jobStore
    ) {
        this(graphCommitter, wikiCompiler, jobStore, Clock.systemUTC(),
                EnvConfig.get().getInt(EnvKey.GRAPH_MUTATION_MAX_ATTEMPTS, 5));
    }

    GraphMutationSagaService(
            GraphMutationCommitter graphCommitter,
            GraphSpaceWikiCompiler wikiCompiler,
            KnowledgeGraphMutationJobStore jobStore,
            Clock clock,
            int maxAttempts
    ) {
        this.graphCommitter = Objects.requireNonNull(graphCommitter, "graphCommitter");
        this.wikiCompiler = Objects.requireNonNull(wikiCompiler, "wikiCompiler");
        this.jobStore = Objects.requireNonNull(jobStore, "jobStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxAttempts = maxAttempts;
    }

    @Override
    public GraphMutationResult commit(GraphChangeSet changeSet) {
        KnowledgeGraphMutationJob registered = jobStore.register(
                changeSet, null, null, clock.instant());
        if (registered.status() == KnowledgeGraphMutationJob.Status.FAILED) {
            throw new IllegalStateException(
                    "Graph mutation has permanently failed: " + registered.errorMessage());
        }
        if (registered.status() == KnowledgeGraphMutationJob.Status.KNOWLEDGE_COMMITTED) {
            return result(registered);
        }
        return runToCompletion(changeSet.requestId());
    }

    public boolean processNext() {
        Optional<KnowledgeGraphMutationJob> claimed = jobStore.claimNext(clock.instant());
        if (claimed.isEmpty()) return false;
        processClaimed(claimed.get());
        return true;
    }

    public int recoverStuck() {
        int stuckMinutes = EnvConfig.get().getInt(
                EnvKey.GRAPH_MUTATION_STUCK_MINUTES, 30);
        if (stuckMinutes < 1) {
            throw new IllegalArgumentException(
                    "HARNESS_GRAPH_MUTATION_STUCK_MINUTES must be positive");
        }
        Instant now = clock.instant();
        return jobStore.recoverStuck(now.minus(Duration.ofMinutes(stuckMinutes)), now);
    }

    private GraphMutationResult runToCompletion(String requestId) {
        for (int stage = 0; stage < 2; stage++) {
            KnowledgeGraphMutationJob current = jobStore.findById(requestId).orElseThrow();
            if (current.status() == KnowledgeGraphMutationJob.Status.KNOWLEDGE_COMMITTED) {
                return result(current);
            }
            if (current.status() == KnowledgeGraphMutationJob.Status.FAILED) {
                throw new IllegalStateException(
                        "Graph mutation has permanently failed: " + current.errorMessage());
            }
            KnowledgeGraphMutationJob claimed = jobStore.claim(
                    requestId, clock.instant()).orElseThrow(() -> new IllegalStateException(
                    "Graph mutation is already claimed or waiting for retry: " + requestId));
            processClaimed(claimed);
        }
        KnowledgeGraphMutationJob completed = jobStore.findById(requestId).orElseThrow();
        if (completed.status() != KnowledgeGraphMutationJob.Status.KNOWLEDGE_COMMITTED) {
            throw new IllegalStateException("Graph mutation did not reach knowledge commit");
        }
        return result(completed);
    }

    private void processClaimed(KnowledgeGraphMutationJob job) {
        try {
            if (job.status() == KnowledgeGraphMutationJob.Status.PENDING) {
                GraphMutationResult graphResult = graphCommitter.commit(job.changeSet());
                jobStore.markGraphCommitted(
                        job.changeSet().requestId(), graphResult, clock.instant());
                return;
            }
            if (job.status() == KnowledgeGraphMutationJob.Status.GRAPH_COMMITTED) {
                GraphMutationResult graphResult = result(job);
                String revisionId = wikiCompiler.synchronize(job.changeSet(), graphResult);
                jobStore.completeKnowledge(
                        job.changeSet().requestId(), revisionId, clock.instant());
                return;
            }
            throw new IllegalStateException(
                    "Claimed graph mutation has terminal status " + job.status());
        } catch (RuntimeException failure) {
            handleFailure(job, failure);
            throw failure;
        }
    }

    private void handleFailure(KnowledgeGraphMutationJob job, RuntimeException failure) {
        String error = boundedError(failure);
        Instant now = clock.instant();
        if (job.attempts() >= maxAttempts) {
            jobStore.markFailed(job.changeSet().requestId(), now, error);
            return;
        }
        jobStore.reschedule(job.changeSet().requestId(),
                now.plus(retryDelay(job.attempts())), error);
    }

    static Duration retryDelay(int attempts) {
        int exponent = Math.max(0, Math.min(attempts - 1, 6));
        long seconds = BASE_RETRY_DELAY.toSeconds() * (1L << exponent);
        return Duration.ofSeconds(Math.min(seconds, MAX_RETRY_DELAY.toSeconds()));
    }

    private static GraphMutationResult result(KnowledgeGraphMutationJob job) {
        if (job.graphNodeCount() == null || job.graphRelationCount() == null) {
            throw new IllegalStateException("Graph mutation result counts are missing");
        }
        return new GraphMutationResult(job.changeSet().requestId(), true,
                job.graphNodeCount(), job.graphRelationCount());
    }

    private static String boundedError(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
        return message.length() <= 1024 ? message : message.substring(0, 1024);
    }
}
