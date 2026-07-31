package com.harness.tool.web;

import com.harness.core.exception.ToolExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizedUrlContextTest {

    @AfterEach
    void tearDown() {
        AuthorizedUrlContext.clear();
    }

    @Test
    void acceptsOnlyExactUrlExplicitlyPresentInUserText() {
        AuthorizedUrlContext.setFromUserText(
                "请读取 https://Example.com/article?id=1。");

        AuthorizedUrlContext.requireAuthorized(
                "https://example.com/article?id=1", "test");
        assertThatThrownBy(() -> AuthorizedUrlContext.requireAuthorized(
                "https://example.com/another", "test"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("not explicitly provided");
    }

    @Test
    void failsClosedWithoutRequestContext() {
        assertThatThrownBy(() -> AuthorizedUrlContext.requireAuthorized(
                "https://example.com/", "test"))
                .isInstanceOf(ToolExecutionException.class);
    }
}
