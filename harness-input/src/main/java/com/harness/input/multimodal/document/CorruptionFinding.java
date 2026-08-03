package com.harness.input.multimodal.document;

import java.util.List;

public record CorruptionFinding(
        String blockId,
        double score,
        List<String> reasons
) {
    public CorruptionFinding {
        reasons = reasons != null ? List.copyOf(reasons) : List.of();
    }
}
