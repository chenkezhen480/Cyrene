package com.harness.input.multimodal;

import com.harness.ai.model.ChatModelProvider;
import com.harness.ai.model.VisionModelProvider;
import com.harness.ai.model.VoiceModelProvider;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import dev.langchain4j.model.chat.ChatModel;
import com.harness.core.model.ParsedContent;
import com.harness.input.multimodal.impl.TextExtractorRegistry;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Large file parser using merge-then-summarize approach.
 * <ol>
 *   <li>Extract text via TextExtractorRegistry (supports PDF, DOCX, XLSX, etc.)</li>
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
    private final ChatModel noThinkingModel;
    private final int blockTokenBudget;
    private final int summaryConcurrency;

    public LargeFileParser(ChatModelProvider chatProvider, VisionModelProvider visionProvider, VoiceModelProvider voiceProvider) {
        this.chatProvider = chatProvider;
        this.noThinkingModel = chatProvider.chatModelNoThinking();

        EnvConfig cfg = EnvConfig.get();
        int contextWindow = chatProvider.contextWindow();
        double ratio = cfg.getDouble(EnvKey.LARGE_FILE_CONTEXT_RATIO, 0.4);
        this.blockTokenBudget = (int) (contextWindow * ratio);
        this.summaryConcurrency = cfg.getInt(EnvKey.LARGE_FILE_SUMMARY_CONCURRENCY, 3);

        log.info("[LargeFileParser] model={}, contextWindow={}, ratio={}, blockTokenBudget={}, concurrency={}",
                chatProvider.modelName(), contextWindow, ratio, blockTokenBudget, summaryConcurrency);
    }

    public ParsedContent parse(byte[] fileData, String fileName, String mimeType) {
        log.info("Large file parsing: file={}, size={}KB, mimeType={}", fileName, fileData.length / 1024, mimeType);

        // Step 1: Extract raw text
        String rawText = TextExtractorRegistry.extract(fileData, fileName, mimeType);

        // Step 2: Semantic splitting
        List<String> chunks = TextChunker.split(rawText);
        log.info("Split into {} chunks (blockTokenBudget={})", chunks.size(), blockTokenBudget);

        // Step 3: Greedy merge into blocks
        List<String> blocks = mergeIntoBlocks(chunks);
        log.info("Merged into {} blocks", blocks.size());

        // Step 4: Summarize blocks (parallel)
        List<String> summaries = summarizeBlocks(blocks);

        // Step 5: Final merge
        String finalText;
        if (summaries.size() == 1) {
            finalText = summaries.get(0);
        } else {
            finalText = mergeSummaries(summaries);
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("file_name", fileName);
        metadata.put("file_size_kb", fileData.length / 1024);
        metadata.put("original_chunks", chunks.size());
        metadata.put("merged_blocks", blocks.size());
        metadata.put("summary_calls", summaries.size());

        return new ParsedContent(finalText, ParsedContent.ParseStrategy.CHUNKED_REDUCE, chunks.size(), metadata);
    }

    /**
     * Greedily merge consecutive chunks into blocks, each up to blockTokenBudget tokens.
     */
    private List<String> mergeIntoBlocks(List<String> chunks) {
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
    private List<String> summarizeBlocks(List<String> blocks) {
        if (blocks.size() == 1) {
            return List.of(summarizeChunk(blocks.get(0), 1, 1));
        }

        ExecutorService executor = Executors.newFixedThreadPool(summaryConcurrency);
        try {
            List<CompletableFuture<String>> futures = new ArrayList<>();
            for (int i = 0; i < blocks.size(); i++) {
                final int idx = i;
                futures.add(CompletableFuture.supplyAsync(
                        () -> summarizeChunk(blocks.get(idx), idx + 1, blocks.size()),
                        executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<String> results = new ArrayList<>();
            for (CompletableFuture<String> f : futures) {
                results.add(f.join());
            }
            return results;
        } finally {
            executor.shutdown();
        }
    }

    private String summarizeChunk(String chunk, int index, int total) {
        try {
            String prompt = SUMMARIZE_PROMPT + "\n\n[" + index + "/" + total + "]\n" + chunk;
            return noThinkingModel.chat(ChatRequest.builder()
                    .messages(UserMessage.from(prompt))
                    .build()).aiMessage().text();
        } catch (Exception e) {
            log.warn("Failed to summarize block {}/{}: {}", index, total, e.getMessage());
            return chunk;
        }
    }

    private String mergeSummaries(List<String> summaries) {
        if (summaries.size() == 1) return summaries.get(0);
        try {
            StringBuilder prompt = new StringBuilder(MERGE_PROMPT);
            for (int i = 0; i < summaries.size(); i++) {
                prompt.append("\n\n--- 摘要 ").append(i + 1).append(" ---\n").append(summaries.get(i));
            }
            return noThinkingModel.chat(ChatRequest.builder()
                    .messages(UserMessage.from(prompt.toString()))
                    .build()).aiMessage().text();
        } catch (Exception e) {
            log.warn("Failed to merge summaries: {}", e.getMessage());
            return String.join("\n\n", summaries);
        }
    }
}
