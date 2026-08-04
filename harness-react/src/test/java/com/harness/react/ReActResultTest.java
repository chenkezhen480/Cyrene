package com.harness.react;

import com.harness.core.model.ReActStep;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReActResultTest {

    @Test
    void ownsImmutableResultCollections() {
        List<ReActStep> steps = new ArrayList<>();
        ReActResult result = new ReActResult("done", steps, null, null);
        steps.add(new ReActStep(
                1, "thought", "action", List.of(), List.of(), "observation", null));

        assertThat(result.steps()).isEmpty();
        assertThat(result.artifacts()).isEmpty();
    }
}
