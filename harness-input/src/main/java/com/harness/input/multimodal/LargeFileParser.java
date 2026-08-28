package com.harness.input.multimodal;

import com.harness.provider.ChatModelProvider;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.exception.AgentException;
import dev.langchain4j.model.chat.ChatModel;
import com.harness.core.model.ParsedContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Large file parser using merge-then-summarize approach.
 * <ol>
 *   <li>Receive canonical Markdown produced by the shared document converter</li>
 *   <li>Split by semantic boundaries via TextChunker</li>
 *   <li>Greedily merge consecutive chunks until reaching contextWindow × ratio</li>
 *   <li>Summarize each merged block (parallel, bounded by concurrency)</li>
 *   <li>Merge all block summaries into final result</li>
 * </ol>
 */
public class LargeFileParser {

    private static final Logger log = LoggerFactory.getLogger(LargeFileParser.class);
    private static final String SUMMARIZE_PROMPT = "请对以下文本进行摘要，保留关键信息，去除冗余内容：";
    private static final String MERGE_PROMPT = "请将以下多个摘要合并为一份连贯的最终摘要，保留所有关键信息：";

    private final ChatModelProvider chatProvider;
    private final int summaryConcurrency;

    public LargeFileParser(ChatModelProvider chatProvider) {
        this.chatProvider = java.util.Objects.requireNonNull(chatProvider, "chatProvider");

        EnvConfig cfg = EnvConfig.get();
        this.summaryConcurrency = cfg.getInt(EnvKey.LARGE_FILE_SUMMARY_CONCURRENCY, 3);

        log.info("[LargeFileParser] model={}, contextWindow={}, concurrency={}",
                chatProvider.modelName(), chatProvider.contextWindow(), summaryConcurrency);
    }

    public ParsedContent summarizeMarkdown(
            String markdown,
            String fileName,
            long fileSizeBytes
    ) {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("markdown must not be blank");
        }
        log.debug("Large Markdown summarization: file={}, size={}KB",
                fileName, fileSizeBytes / 1024);

        ChatModel noThinkingModel = chatProvider.chatModel();
        int blockTokenBudget = blockTokenBudget();

        // Step 1: Semantic splitting of the already converted Markdown
        List<String> chunks = TextChunker.split(markdown);


        // Step 3: Greedy merge into blocks
        List<String> blocks = mergeIntoBlocks(chunks, blockTokenBudget);


        // Step 4: Summarize blocks (parallel)
        List<String> summaries = summarizeBlocks(blocks, noThinkingModel);

        // Step 5: Final merge
        String finalText;
        if (summaries.size() == 1) {
            finalText = summaries.get(0);
        } else {
            finalText = mergeSummaries(summaries, noThinkingModel);
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("file_name", fileName);
        metadata.put("file_size_kb", fileSizeBytes / 1024);
        metadata.put("source_format", "markdown");
        metadata.put("original_chunks", chunks.size());
        metadata.put("merged_blocks", blocks.size());
        metadata.put("summary_calls", summaries.size());

        return new ParsedContent(finalText, ParsedContent.ParseStrategy.CHUNKED_REDUCE, chunks.size(), metadata);
    }

    /**
     * Greedily merge consecutive chunks into blocks, each up to blockTokenBudget tokens.
     */
    private List<String> mergeIntoBlocks(List<String> chunks, int blockTokenBudget) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;

        for (String chunk : chunks) {
            int chunkTokens = TextChunker.estimateTokens(chunk);
            if (currentTokens + chunkTokens > blockTokenBudget && !current.isEmpty()) {
                blocks.add(current.toString().strip());
                current = new StringBuilder();
                currentTokens = 0;
            }
            current.append(chunk).append("\n\n");
            currentTokens += chunkTokens;
        }
        if (!current.toString().isBlank()) {
            blocks.add(current.toString().strip());
        }
        return blocks;
    }

    /**
     * Summarize blocks with bounded parallelism.
     */
    private List<String> summarizeBlocks(List<String> blocks, ChatModel model) {
        if (blocks.size() == 1) {
            return List.of(summarizeChunk(blocks.get(0), 1, 1, model));
        }

        ExecutorService executor = Executors.newFixedThreadPool(summaryConcurrency);
        try {
            List<CompletableFuture<String>> futures = new ArrayList<>();
            for (int i = 0; i < blocks.size(); i++) {
                final int idx = i;
                futures.add(CompletableFuture.supplyAsync(
                        () -> summarizeChunk(
                                blocks.get(idx), idx + 1, blocks.size(), model),
                        executor));
            }
            try {
                CompletableFuture.allOf(
                        futures.toArray(new CompletableFuture[0])).join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new AgentException("Large document summarization failed", e);
            }

            List<String> results = new ArrayList<>();
            for (CompletableFuture<String> f : futures) {
                results.add(f.join());
            }
            return results;
        } finally {
            executor.shutdown();
        }
    }

    private String summarizeChunk(String chunk, int index, int total, ChatModel model) {
        try {
            String prompt = SUMMARIZE_PROMPT + "\n\n[" + index + "/" + total + "]\n" + chunk;
            return model.chat(ChatRequest.builder()
                    .messages(UserMessage.from(prompt))
                    .build()).aiMessage().text();
        } catch (Exception e) {
            throw new AgentException(
                    "Failed to summarize document block " + index + "/" + total
                            + ": " + e.getMessage(), e);
        }
    }

    private String mergeSummaries(List<String> summaries, ChatModel model) {
        if (summaries.size() == 1) return summaries.get(0);
        try {
            StringBuilder prompt = new StringBuilder(MERGE_PROMPT);
            for (int i = 0; i < summaries.size(); i++) {
                prompt.append("\n\n--- 摘要 ").append(i + 1).append(" ---\n").append(summaries.get(i));
            }
            return model.chat(ChatRequest.builder()
                    .messages(UserMessage.from(prompt.toString()))
                    .build()).aiMessage().text();
        } catch (Exception e) {
            throw new AgentException(
                    "Failed to merge document summaries: " + e.getMessage(), e);
        }
    }

    private int blockTokenBudget() {
        double ratio = EnvConfig.get().getDouble(EnvKey.LARGE_FILE_CONTEXT_RATIO, 0.4);
        return (int) (chatProvider.contextWindow() * ratio);
    }
}
