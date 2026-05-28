package com.harness.preprocess.rag;

import com.harness.ai.model.ChatModelProvider;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Wraps RAG retrieval to add semantic completeness checking.
 * When a chunk is semantically truncated, it fetches the previous chunk and merges.
 */
public class SemanticContextRetriever {

    private static final Logger log = LoggerFactory.getLogger(SemanticContextRetriever.class);

    private static final String COMPLETENESS_PROMPT =
            "判断以下文本是否语义完整（是否在句子或段落中间被截断）。只回答 COMPLETE 或 INCOMPLETE。";

    private final PgVectorRagRetriever pgVectorRetriever;
    private final ChatModelProvider chatProvider;
    private final int maxLookback;

    public SemanticContextRetriever(PgVectorRagRetriever pgVectorRetriever, ChatModelProvider chatProvider) {
        this.pgVectorRetriever = pgVectorRetriever;
        this.chatProvider = chatProvider;
        EnvConfig cfg = EnvConfig.get();
        this.maxLookback = cfg.getInt(EnvKey.RAG_CONTEXT_LOOKBACK_MAX, 2);
    }

    /**
     * Enhance retrieved chunks by checking semantic completeness.
     * Returns enriched chunks with lookback context where needed.
     */
    public List<EnhancedChunk> enhance(List<RagRetriever.RagDocument> chunks) {
        if (chunks == null || chunks.isEmpty()) return List.of();
        if (maxLookback <= 0 || pgVectorRetriever == null) {
            return chunks.stream().map(c -> new EnhancedChunk(c, List.of(), 0)).toList();
        }

        List<EnhancedChunk> enhanced = new ArrayList<>();
        Map<String, RagRetriever.RagDocument> lookedUp = new HashMap<>();

        for (RagRetriever.RagDocument chunk : chunks) {
            String content = chunk.content();
            int lookbackCount = 0;
            List<String> lookbackIds = new ArrayList<>();

            // Check semantic completeness, look back up to maxLookback times
            for (int i = 0; i < maxLookback; i++) {
                if (isSemanticallyComplete(content)) break;

                String prevId = getPrevChunkId(chunk.id());
                if (prevId == null || lookedUp.containsKey(prevId)) break;

                RagRetriever.RagDocument prevChunk = fetchById(prevId);
                if (prevChunk == null) break;

                lookedUp.put(prevId, prevChunk);
                content = prevChunk.content() + "\n\n" + content;
                lookbackIds.add(prevId);
                lookbackCount++;
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

    private boolean isSemanticallyComplete(String text) {
        if (text == null || text.isBlank()) return true;
        // Heuristic: if text ends with terminal punctuation, likely complete
        String trimmed = text.stripTrailing();
        if (trimmed.endsWith("。") || trimmed.endsWith(".") || trimmed.endsWith("！")
                || trimmed.endsWith("!") || trimmed.endsWith("？") || trimmed.endsWith("?")
                || trimmed.endsWith("]") || trimmed.endsWith(")")) {
            return true;
        }
        // Use LLM for ambiguous cases
        try {
            String prompt = COMPLETENESS_PROMPT + "\n\n" + text;
            String response = chatProvider.chatModel().chat(ChatRequest.builder()
                    .messages(UserMessage.from(prompt))
                    .build()).aiMessage().text().trim().toUpperCase();
            return response.contains("COMPLETE") && !response.contains("INCOMPLETE");
        } catch (Exception e) {
            log.warn("Completeness check failed, assuming complete: {}", e.getMessage());
            return true;
        }
    }

    private String getPrevChunkId(String chunkId) {
        if (pgVectorRetriever == null) return null;
        try {
            return pgVectorRetriever.getPrevChunkId(chunkId);
        } catch (Exception e) {
            log.debug("Failed to get prev chunk id for {}: {}", chunkId, e.getMessage());
            return null;
        }
    }

    private RagRetriever.RagDocument fetchById(String id) {
        if (pgVectorRetriever == null) return null;
        try {
            return pgVectorRetriever.retrieveById(id);
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
