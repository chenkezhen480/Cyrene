package com.harness.preprocess.rag;

import com.harness.preprocess.rag.route.RetrievalRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Parallel fan-out retriever that queries multiple RetrievalRoutes concurrently,
 * merges results by document ID (keeping the highest score), and returns sorted results.
 */
public class MultiRouteRetriever {

    private static final Logger log = LoggerFactory.getLogger(MultiRouteRetriever.class);

    private final List<RetrievalRoute> routes;
    private final ExecutorService executor;

    public MultiRouteRetriever(List<RetrievalRoute> routes) {
        this.routes = routes.stream().filter(RetrievalRoute::isAvailable).toList();
        this.executor = Executors.newFixedThreadPool(Math.max(this.routes.size(), 1));
        log.info("[MultiRoute] Initialized with {} active routes: {}",
                this.routes.size(), this.routes.stream().map(RetrievalRoute::routeName).toList());
    }

    /**
     * Fan out retrieval to all enabled routes in parallel, merge and deduplicate.
     *
     * @param query the (possibly rewritten) query
     * @return merged, deduplicated documents sorted by score descending
     */
    public List<RagRetriever.RagDocument> retrieve(String query) {
        if (routes.isEmpty()) return List.of();

        long start = System.currentTimeMillis();

        List<CompletableFuture<List<RagRetriever.RagDocument>>> futures = routes.stream()
                .map(route -> CompletableFuture.supplyAsync(() -> {
                    try {
                        List<RagRetriever.RagDocument> docs = route.retrieve(query);
                        log.debug("[MultiRoute] {} returned {} docs", route.routeName(), docs.size());
                        return docs;
                    } catch (Exception e) {
                        log.warn("[MultiRoute] {} failed: {}", route.routeName(), e.getMessage());
                        return Collections.<RagRetriever.RagDocument>emptyList();
                    }
                }, executor))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Merge: deduplicate by ID, keep highest score
        Map<String, RagRetriever.RagDocument> merged = new LinkedHashMap<>();
        for (CompletableFuture<List<RagRetriever.RagDocument>> f : futures) {
            for (RagRetriever.RagDocument doc : f.join()) {
                merged.merge(doc.id(), doc, (existing, incoming) ->
                        existing.score() >= incoming.score() ? existing : incoming);
            }
        }

        List<RagRetriever.RagDocument> result = merged.values().stream()
                .sorted(Comparator.comparingDouble(RagRetriever.RagDocument::score).reversed())
                .toList();

        long elapsed = System.currentTimeMillis() - start;
        log.info("[MultiRoute] {} routes returned {} merged docs in {}ms", routes.size(), result.size(), elapsed);
        return result;
    }

    public void shutdown() {
        executor.shutdown();
    }
}
