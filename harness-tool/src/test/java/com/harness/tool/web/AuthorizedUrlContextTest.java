package com.harness.tool.web;

import com.harness.core.exception.ToolExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
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
                .hasMessageContaining("outside the scope authorized");
    }

    @Test
    void failsClosedWithoutRequestContext() {
        // 与「URL 不在作用域内」必须区分开：症状像，根因完全不同。
        assertThatThrownBy(() -> AuthorizedUrlContext.requireAuthorized(
                "https://example.com/", "test"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("scope is not initialized");
    }

    @Test
    void extractFromUserTextDoesNotTouchTheCurrentScope() {
        AuthorizedUrlContext.setFromUserText("只认 https://example.com/kept");

        Set<String> extracted = AuthorizedUrlContext.extractFromUserText(
                "历史里的 https://history.example/page");

        assertThat(extracted).containsExactly("https://history.example:443/page");
        // 纯提取：不得污染当前作用域
        assertThat(AuthorizedUrlContext.snapshot())
                .containsExactly("https://example.com:443/kept");
    }

    @Test
    void setReplacesTheScopeSoSessionsOnAReusedThreadCannotLeak() {
        AuthorizedUrlContext.setFromUserText("会话A https://a.example/secret");
        // resume dispatcher 是单线程跨会话复用的：播种必须整体替换，不能是合并
        AuthorizedUrlContext.set(AuthorizedUrlContext.extractFromUserText(
                "会话B https://b.example/page"));

        assertThat(AuthorizedUrlContext.snapshot())
                .containsExactly("https://b.example:443/page");
        assertThatThrownBy(() -> AuthorizedUrlContext.requireAuthorized(
                "https://a.example/secret", "test"))
                .isInstanceOf(ToolExecutionException.class);
    }

    @Test
    void authorizeAllMergesTrustedUrlsWithUserTextUrls() {
        AuthorizedUrlContext.setFromUserText("请读取 https://Example.com/article。");

        AuthorizedUrlContext.authorizeAll(List.of("https://result.example/page#frag"));

        AuthorizedUrlContext.requireAuthorized("https://example.com/article", "test");
        AuthorizedUrlContext.requireAuthorized("https://result.example/page", "test");
        assertThatThrownBy(() -> AuthorizedUrlContext.requireAuthorized(
                "https://invented.example/", "test"))
                .isInstanceOf(ToolExecutionException.class);
    }

    @Test
    void authorizeAllWithoutRunScopeKeepsFailClosed() {
        AuthorizedUrlContext.authorizeAll(List.of("https://result.example/page"));

        assertThatThrownBy(() -> AuthorizedUrlContext.requireAuthorized(
                "https://result.example/page", "test"))
                .isInstanceOf(ToolExecutionException.class);
    }
}
