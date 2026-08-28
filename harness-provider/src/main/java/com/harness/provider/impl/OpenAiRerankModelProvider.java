package com.harness.provider.impl;

import com.harness.provider.RerankModelProvider;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class OpenAiRerankModelProvider implements RerankModelProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiRerankModelProvider.class);
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private volatile OkHttpClient client;
    private volatile ObjectMapper mapper;

    public OpenAiRerankModelProvider() {
        this(EnvConfig.get());
    }

    public OpenAiRerankModelProvider(EnvConfig cfg) {
        this.apiKey = cfg.requireString(EnvKey.MODEL_RERANK_API_KEY);
        this.baseUrl = cfg.getString(EnvKey.MODEL_RERANK_BASE_URL, "https://api.openai.com/v1");
        this.model = cfg.getString(EnvKey.MODEL_RERANK_MODEL, "rerank-english-v3.0");
    }

    private OkHttpClient getClient() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(30, TimeUnit.SECONDS)
                            .readTimeout(60, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return client;
    }

    private ObjectMapper getMapper() {
        if (mapper == null) {
            synchronized (this) {
                if (mapper == null) {
                    mapper = new ObjectMapper();
                }
            }
        }
        return mapper;
    }

    @Override
    public double score(String query, String document) {
        List<String> docs = List.of(document);
        List<RankedResult> results = rerank(query, docs, 1);
        return results.isEmpty() ? 0.0 : results.get(0).score();
    }

    @Override
    public List<RankedResult> rerank(String query, List<String> documents, int topN) {
        if (documents == null || documents.isEmpty()) return List.of();

        ObjectMapper m = getMapper();
        OkHttpClient c = getClient();
        try {
            ObjectNode body = m.createObjectNode();
            body.put("model", model);
            body.put("query", query);
            body.put("top_n", Math.min(topN, documents.size()));
            ArrayNode docsArray = body.putArray("documents");
            for (String doc : documents) {
                docsArray.add(doc);
            }

            String url = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(m.writeValueAsString(body), JSON_TYPE))
                    .build();

            try (Response response = c.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Rerank API returned {}: {}", response.code(), response.body() != null ? response.body().string() : "");
                    return fallbackByIndex(documents, topN);
                }

                JsonNode json = m.readTree(response.body().string());
                JsonNode results = json.get("results");
                if (results == null || !results.isArray()) {
                    log.warn("Unexpected rerank response format");
                    return fallbackByIndex(documents, topN);
                }

                List<RankedResult> ranked = new ArrayList<>();
                for (JsonNode r : results) {
                    int idx = r.get("index").asInt();
                    double score = r.get("relevance_score").asDouble();
                    if (idx >= 0 && idx < documents.size()) {
                        ranked.add(new RankedResult(idx, documents.get(idx), score));
                    }
                }
                return ranked;
            }
        } catch (IOException e) {
            log.error("Rerank API call failed: {}", e.getMessage(), e);
            return fallbackByIndex(documents, topN);
        }
    }

    private List<RankedResult> fallbackByIndex(List<String> documents, int topN) {
        List<RankedResult> fallback = new ArrayList<>();
        int limit = Math.min(topN, documents.size());
        for (int i = 0; i < limit; i++) {
            fallback.add(new RankedResult(i, documents.get(i), 0.0));
        }
        return fallback;
    }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public String providerName() { return "openai"; }

    @Override
    public String modelName() { return model; }
}
