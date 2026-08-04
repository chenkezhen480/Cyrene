package com.harness.react;

import com.harness.core.model.Artifact;
import com.harness.core.model.ReActStep;

import java.util.List;

/** Result returned by any ReAct loop implementation. */
public record ReActResult(
        String output,
        List<ReActStep> steps,
        List<Artifact> artifacts,
        ReActLoopStats loopStats
) {
    public ReActResult {
        steps = steps != null ? List.copyOf(steps) : List.of();
        artifacts = artifacts != null ? List.copyOf(artifacts) : List.of();
    }

    public ReActResult(String output, List<ReActStep> steps) {
        this(output, steps, List.of(), null);
    }

    public ReActResult(String output, List<ReActStep> steps, List<Artifact> artifacts) {
        this(output, steps, artifacts, null);
    }
}
