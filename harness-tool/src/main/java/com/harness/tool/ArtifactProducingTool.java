package com.harness.tool;

/**
 * Marker interface for tools that produce downloadable artifacts.
 * ReActEngine only parses artifact JSON from tools implementing this interface,
 * avoiding false positives from regular tool outputs.
 */
public interface ArtifactProducingTool extends Tool {
}
