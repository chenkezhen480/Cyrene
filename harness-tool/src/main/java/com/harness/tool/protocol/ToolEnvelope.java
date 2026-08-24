package com.harness.tool.protocol;

import com.harness.core.model.PageInfo;

import java.util.Map;
import java.util.Objects;

/** Stable JSON envelope for successful and empty tool results. */
public record ToolEnvelope<T>(
        ToolEnvelopeStatus status,
        T data,
        PageInfo pageInfo,
        Map<String, Object> meta
) {
    public ToolEnvelope {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(data, "data");
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    public static <T> ToolEnvelope<T> success(
            T data, PageInfo pageInfo, Map<String, Object> meta) {
        return new ToolEnvelope<>(ToolEnvelopeStatus.SUCCESS, data, pageInfo, meta);
    }

    public static <T> ToolEnvelope<T> empty(
            T data, PageInfo pageInfo, Map<String, Object> meta) {
        return new ToolEnvelope<>(ToolEnvelopeStatus.EMPTY, data, pageInfo, meta);
    }
}
