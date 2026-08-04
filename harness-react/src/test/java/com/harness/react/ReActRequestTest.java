package com.harness.react;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReActRequestTest {

    @Test
    void normalizesOptionalRuntimeInputsAndCopiesHistory() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(UserMessage.from("previous"));

        ReActRequest request = new ReActRequest(
                "system", "question", history, null, null, null, null, null);
        history.clear();

        assertThat(request.historyMessages()).hasSize(1);
        assertThat(request.trace()).isNotNull();
    }

    @Test
    void usesEmptyHistoryWhenNoneIsProvided() {
        ReActRequest request = new ReActRequest(
                "system", "question", null, null, null, null, null, null);

        assertThat(request.historyMessages()).isEmpty();
    }
}
