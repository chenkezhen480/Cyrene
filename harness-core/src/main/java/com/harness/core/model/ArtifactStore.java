package com.harness.core.model;

import java.util.List;
import java.util.Optional;

/**
 * Interface for artifact metadata persistence and retrieval.
 * Filesystem-backed implementation lives in harness-tool.
 */
public interface ArtifactStore {

    void save(Artifact artifact);

    Optional<Artifact> get(String id);

    void delete(String id);

    List<Artifact> listBySession(String sessionId);
}
