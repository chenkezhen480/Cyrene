package com.harness.provider.impl;

import com.harness.provider.RerankModelProvider;
import java.util.ArrayList;
import java.util.List;

public class NoOpRerankModelProvider implements RerankModelProvider {
    @Override public double score(String query, String document) { return 0; }
    @Override public List<RankedResult> rerank(String query, List<String> documents, int topN) {
        List<RankedResult> result = new ArrayList<>();
        int limit = Math.min(topN, documents.size());
        for (int i = 0; i < limit; i++) {
            result.add(new RankedResult(i, documents.get(i), 0.0));
        }
        return result;
    }
    @Override public boolean isAvailable() { return false; }
    @Override public String providerName() { return "none"; }
    @Override public String modelName() { return "none"; }
}
