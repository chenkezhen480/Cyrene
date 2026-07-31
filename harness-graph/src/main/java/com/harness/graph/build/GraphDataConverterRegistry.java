package com.harness.graph.build;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

public final class GraphDataConverterRegistry {

    private final Map<ConverterKey, GraphDataConverter> converters = new ConcurrentHashMap<>();

    public GraphDataConverterRegistry(List<GraphDataConverter> converters) {
        if (converters != null) {
            converters.forEach(this::register);
        }
    }

    public static GraphDataConverterRegistry withDefaults(ObjectMapper objectMapper) {
        GraphDataConverterRegistry registry = new GraphDataConverterRegistry(
                List.of(new CanonicalJsonGraphDataConverter(objectMapper)));
        ServiceLoader.load(GraphDataConverter.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .forEach(registry::register);
        return registry;
    }

    public void register(GraphDataConverter converter) {
        Objects.requireNonNull(converter, "converter");
        String converterId = requireText(converter.converterId(), "converterId");
        GraphBuildSourceType sourceType = Objects.requireNonNull(
                converter.sourceType(), "converter sourceType");
        ConverterKey key = new ConverterKey(sourceType, converterId);
        GraphDataConverter previous = converters.putIfAbsent(key, converter);
        if (previous != null) {
            throw new IllegalStateException(
                    "Graph data converter already registered: " + sourceType.value() + "/" + converterId);
        }
    }

    public GraphDataConverter require(GraphBuildSourceType sourceType, String converterId) {
        ConverterKey key = new ConverterKey(
                Objects.requireNonNull(sourceType, "sourceType"),
                requireText(converterId, "converterId"));
        GraphDataConverter converter = converters.get(key);
        if (converter == null) {
            throw new IllegalArgumentException(
                    "No graph data converter registered for "
                            + sourceType.value() + "/" + converterId);
        }
        return converter;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    private record ConverterKey(GraphBuildSourceType sourceType, String converterId) {
    }
}
