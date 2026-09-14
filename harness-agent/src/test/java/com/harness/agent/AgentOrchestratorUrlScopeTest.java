package com.harness.agent;

import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.MemoryMessage;
import com.harness.core.model.MessageBlock;
import com.harness.tool.web.AuthorizedUrlContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * resume 轮跑在 session-resume-dispatcher 线程上，不经过 AgentRunPreparer，
 * URL 授权作用域必须从会话历史重建。这里守住「只认持久化的 user 消息」这条边界。
 */
class AgentOrchestratorUrlScopeTest {

    @AfterEach
    void tearDown() {
        AuthorizedUrlContext.clear();
    }

    @Test
    void rebuildsScopeFromPersistedUserMessagesOnly() {
        List<MemoryMessage> history = List.of(
                message("user", "请读取 https://asked.example/page"),
                message("assistant", "已派发子任务，参考 https://model-invented.example/x"),
                message("user", "再看看 https://second.example/page"),
                message("tool", "https://tool-output.example/y"));

        assertThat(AgentOrchestrator.authorizedUrlsFromUserHistory(history))
                .containsExactlyInAnyOrder(
                        "https://asked.example:443/page",
                        "https://second.example:443/page");
    }

    @Test
    void neverAuthorizesUrlsProducedByTheAgentItself() {
        // 模型/子 Agent 输出的 URL 出现在 assistant 与 tool 消息里，绝不能因此获得授权。
        List<MemoryMessage> history = List.of(
                message("assistant", "https://model-invented.example/x"),
                message("tool", "https://tool-output.example/y"));

        assertThat(AgentOrchestrator.authorizedUrlsFromUserHistory(history)).isEmpty();
    }

    @Test
    void toleratesEmptyAndNullHistory() {
        assertThat(AgentOrchestrator.authorizedUrlsFromUserHistory(List.of())).isEmpty();
        assertThat(AgentOrchestrator.authorizedUrlsFromUserHistory(null)).isEmpty();
    }

    @Test
    void ignoresUserMessagesWithoutAnyUrl() {
        assertThat(AgentOrchestrator.authorizedUrlsFromUserHistory(
                List.of(message("user", "帮我查一下原神的冰之女皇")))).isEmpty();
    }

    @Test
    void resumeSeedsUrlsTheUserPastedEarlierInTheSession() {
        AgentOrchestrator.initializeUrlScopeForResume(List.of(
                message("user", "第一轮 请读取 https://asked.example/page"),
                message("assistant", "已派发子任务，参考 https://invented.example/x"),
                message("user", "第二轮 再看看 https://second.example/page")));

        // 用户原文里的 URL 在 resume 轮必须仍然可读 —— 这正是原先 fail-closed 打断的工作流
        AuthorizedUrlContext.requireAuthorized(
                "https://asked.example/page", "read_url_content");
        AuthorizedUrlContext.requireAuthorized(
                "https://second.example/page", "read_url_content");
    }

    @Test
    void resumeLeavesAnInitializedScopeEvenWhenHistoryHasNoUrls() {
        AgentOrchestrator.initializeUrlScopeForResume(
                List.of(message("assistant", "这一轮没有任何 URL")));

        // 作用域必须是「已初始化但为空」，不能是 null：
        // 否则报错会退化成 scope-is-not-initialized，把真实原因掩盖掉。
        assertThatThrownBy(() -> AuthorizedUrlContext.requireAuthorized(
                "https://anything.example/", "read_url_content"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("outside the scope authorized");
    }

    @Test
    void consecutiveSessionsOnTheSameDispatcherThreadCannotLeakUrls() {
        // dispatcher 是单线程跨会话复用的，同一个线程会先后处理 Session A 和 Session B
        AgentOrchestrator.initializeUrlScopeForResume(
                List.of(message("user", "会话A https://a.example/secret")));
        AuthorizedUrlContext.requireAuthorized(
                "https://a.example/secret", "read_url_content");

        AgentOrchestrator.initializeUrlScopeForResume(
                List.of(message("user", "会话B https://b.example/page")));

        AuthorizedUrlContext.requireAuthorized(
                "https://b.example/page", "read_url_content");
        assertThatThrownBy(() -> AuthorizedUrlContext.requireAuthorized(
                "https://a.example/secret", "read_url_content"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("outside the scope authorized");
    }

    private static MemoryMessage message(String role, String text) {
        return new MemoryMessage(
                0, "session-1", "trace-1", role,
                List.of(new MessageBlock(MessageBlock.BlockType.TEXT, text, null)),
                false, null);
    }
}
