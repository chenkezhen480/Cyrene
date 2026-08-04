package com.harness.graph.model;

import java.util.Map;

public record GraphAggregate(
        String key,
        Map<String, Object> values
) {
    public GraphAggregate {
        key = GraphModelSupport.requireText(key, "key");
        values = GraphModelSupport.copyProperties(values);
    }
}
