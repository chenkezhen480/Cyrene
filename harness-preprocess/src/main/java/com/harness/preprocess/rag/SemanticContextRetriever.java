package com.harness.preprocess.rag;

import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Wraps RAG retrieval to add semantic completeness checking.
 * When a chunk is semantically truncated, it fetches the previous chunk and merges.
 * Uses pure heuristics (punctuation + continuation word detection) — no LLM calls.
 */
public class SemanticContextRetriever {

    private static final Logger log = LoggerFactory.getLogger(SemanticContextRetriever.class);

    // Terminal punctuation — ending with these means the chunk is likely complete
    private static final String TERMINAL_PUNCTUATION = "。.！!？?」』】）)\"‘’“”";

    // Structural closing characters — code blocks, quotes, tables
    private static final String STRUCTURAL_ENDINGS = "}>]`|";

    // Chinese continuation words — if chunk starts with these, it's a continuation
    private static final String[] CN_CONTINUATIONS = {
            "而", "但", "却", "且", "并", "或", "及", "与", "以及", "但是",
            "然而", "因此", "所以", "故", "则", "又", "再", "还", "也", "就",
            "才", "即", "若", "如", "虽然", "尽管", "即使", "因为", "由于",
            "不过", "否则", "于是", "接着", "然后", "首先", "其次", "最后",
            "总之", "另外", "此外", "同时", "反之", "也就是说"
    };

    // English continuation words — lowercase, checked after lowering
    private static final String[] EN_CONTINUATIONS = {
            "and ", "but ", "or ", "nor ", "yet ", "so ", "for ",
            "however ", "therefore ", "moreover ", "furthermore ",
            "nevertheless ", "meanwhile ", "otherwise ", "thus ",
            "then ", "also ", "still ", "even ", "while ", "whereas ",
            "although ", "because ", "since ", "unless ", "until ",
            "additionally ", "consequently ", "hence ", "accordingly "
    };

    private final VectorStore vectorStore;
    private final int maxLookback;

    public SemanticContextRetriever(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        EnvConfig cfg = EnvConfig.get();
        this.maxLookback = cfg.getInt(EnvKey.RAG_CONTEXT_LOOKBACK_MAX, 2);
    }

    /**
     * Enhance retrieved chunks by checking semantic completeness.
     * Returns enriched chunks with lookback context where needed.
     */
    public List<EnhancedChunk> enhance(List<RagRetriever.RagDocument> chunks) {
        if (chunks == null || chunks.isEmpty()) return List.of();
        if (maxLookback <= 0 || vectorStore == null) {
            return chunks.stream().map(c -> new EnhancedChunk(c, List.of(), 0)).toList();
        }

        List<EnhancedChunk> enhanced = new ArrayList<>();
        Map<String, RagRetriever.RagDocument> lookedUp = new HashMap<>();

        for (RagRetriever.RagDocument chunk : chunks) {
            String content = chunk.content();
            int lookbackCount = 0;
            List<String> lookbackIds = new ArrayList<>();

            // Check semantic completeness, look back up to maxLookback times
            String currentId = chunk.id();
            for (int i = 0; i < maxLookback; i++) {
                if (isSemanticallyComplete(content)) break;

                String prevId = getPrevChunkId(currentId);
                if (prevId == null || lookedUp.containsKey(prevId)) break;

                RagRetriever.RagDocument prevChunk = fetchById(prevId);
                if (prevChunk == null) break;

                lookedUp.put(prevId, prevChunk);
                content = prevChunk.content() + "\n\n" + content;
                lookbackIds.add(prevId);
                lookbackCount++;
                currentId = prevId;
            }

            if (lookbackCount > 0) {
                log.debug("Lookback {} chunks for chunk {}", lookbackCount, chunk.id());
                // Create a new RagDocument with merged content
                RagRetriever.RagDocument merged = new RagRetriever.RagDocument(
                        chunk.id(), content, chunk.source(), chunk.score());
                enhanced.add(new EnhancedChunk(merged, lookbackIds, lookbackCount));
            } else {
                enhanced.add(new EnhancedChunk(chunk, List.of(), 0));
            }
        }

        return enhanced;
    }

    /**
     * Heuristic semantic completeness check — no LLM calls.
     * Checks both chunk ending (terminal punctuation) and chunk opening (continuation words).
     */
    private boolean isSemanticallyComplete(String text) {
        if (text == null || text.isBlank()) return true;
        String trimmed = text.stripTrailing();
        if (trimmed.isEmpty()) return true;

        // Layer 1: Terminal punctuation at end — likely complete
        char last = trimmed.charAt(trimmed.length() - 1);
        if (TERMINAL_PUNCTUATION.indexOf(last) >= 0) return true;

        // Layer 2: Structural closing — code blocks, quotes, tables
        if (STRUCTURAL_ENDINGS.indexOf(last) >= 0) return true;

        // Layer 3: Opening continuation detection — if chunk starts mid-sentence, it's incomplete
        String firstLine = trimmed.contains("\n")
                ? trimmed.substring(0, trimmed.indexOf('\n')).stripLeading()
                : trimmed.stripLeading();
        if (!firstLine.isEmpty()) {
            char first = firstLine.charAt(0);
            // Lowercase English letter — likely mid-sentence
            if (first >= 'a' && first <= 'z') return false;
            // Starts with continuation word or punctuation
            if (startsWithContinuation(firstLine)) return false;
        }

        return true;  // Default to complete when uncertain (conservative, avoids over-lookback)
    }

    private static boolean startsWithContinuation(String firstLine) {
        // Continuation punctuation
        char first = firstLine.charAt(0);
        if (first == ',' || first == '，' || first == ';' || first == '；'
                || first == ':' || first == '：') return true;

        // Chinese continuation words
        for (String w : CN_CONTINUATIONS) {
            if (firstLine.startsWith(w)) return true;
        }

        // English continuation words (case-insensitive)
        String lower = firstLine.toLowerCase();
        for (String w : EN_CONTINUATIONS) {
            if (lower.startsWith(w)) return true;
        }

        return false;
    }

    private String getPrevChunkId(String chunkId) {
        if (vectorStore == null) return null;
        try {
            return vectorStore.getPrevChunkId(chunkId);
        } catch (Exception e) {
            log.debug("Failed to get prev chunk id for {}: {}", chunkId, e.getMessage());
            return null;
        }
    }

    private RagRetriever.RagDocument fetchById(String id) {
        if (vectorStore == null) return null;
        try {
            VectorStore.Document doc = vectorStore.fetchById(id);
            if (doc == null) return null;
            return new RagRetriever.RagDocument(doc.id(), doc.content(), doc.source(), doc.score());
        } catch (Exception e) {
            log.debug("Failed to fetch chunk by id {}: {}", id, e.getMessage());
            return null;
        }
    }

    public record EnhancedChunk(
            RagRetriever.RagDocument document,
            List<String> lookbackChunkIds,
            int lookbackCount
    ) {}
}
