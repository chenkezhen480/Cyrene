package com.harness.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageResponseTest {

    @Test
    void createsCursorPageFromLimitPlusOneFetch() {
        PageResponse<String> response = PageResponse.fromFetched(
                List.of("a", "b", "c"), 2, value -> value);

        assertThat(response.items()).containsExactly("a", "b");
        assertThat(response.pageInfo()).isEqualTo(new PageInfo(2, "b", true));
    }

    @Test
    void createsTerminalPageWithoutCursor() {
        PageResponse<String> response = PageResponse.fromFetched(
                List.of("a"), 2, value -> value);

        assertThat(response.items()).containsExactly("a");
        assertThat(response.pageInfo()).isEqualTo(new PageInfo(2, "", false));
    }

    @Test
    void rejectsMissingCursorForNonTerminalPage() {
        assertThatThrownBy(() -> new PageInfo(20, "", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nextCursor");
    }
}
