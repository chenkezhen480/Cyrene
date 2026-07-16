package com.harness.preprocess.memory;

import com.harness.core.model.MemoryMessage;
import com.harness.core.model.MessageBlock;
import com.harness.core.model.Session;
import com.harness.env.EnvConfig;
import com.harness.env.MysqlConnectionPool;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class MysqlMessageStoreIT {

    static final String TEST_USER = "it_user_msg";
    static String sessionId;

    MysqlMessageStore store;

    @BeforeAll
    static void initEnv() {
        EnvConfig.init(Map.of(
                "HARNESS_AUDIT_DB_URL", "jdbc:mysql://localhost:3306/agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                "HARNESS_AUDIT_DB_USER", "root",
                "HARNESS_AUDIT_DB_PASS", "1234",
                "HARNESS_AUDIT_STORE", "mysql"
        ));
        // Create a test session for messages
        MysqlSessionStore sessionStore = new MysqlSessionStore();
        Session session = sessionStore.create(TEST_USER);
        sessionId = session.id();
    }

    @BeforeEach
    void setUp() {
        store = new MysqlMessageStore();
    }

    @AfterAll
    static void cleanUp() {
        try (Connection conn = MysqlConnectionPool.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM messages WHERE session_id IN (SELECT id FROM sessions WHERE user_id = ?)")) {
                ps.setString(1, TEST_USER);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sessions WHERE user_id = ?")) {
                ps.setString(1, TEST_USER);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            // ignore cleanup errors
        }
    }

    private List<MessageBlock> blocks(String text) {
        return List.of(new MessageBlock(MessageBlock.BlockType.TEXT, text, null));
    }

    @Test
    void save_andLoadForContext() {
        store.save(sessionId, "user", blocks("Hello"), false);
        store.save(sessionId, "assistant", blocks("Hi there"), false);

        List<MemoryMessage> messages = store.loadForContext(sessionId);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo("user");
        assertThat(messages.get(0).text()).isEqualTo("Hello");
        assertThat(messages.get(1).role()).isEqualTo("assistant");
        assertThat(messages.get(1).text()).isEqualTo("Hi there");
    }

    @Test
    void save_summaryMessage_markedCorrectly() {
        String sid = createTestSession();
        store.save(sid, "assistant", blocks("Summary of old messages"), true);

        List<MemoryMessage> messages = store.loadForContext(sid);
        assertThat(messages).anyMatch(m -> m.isSummary() && m.text().contains("Summary"));
    }

    @Test
    void loadForContext_orderedByCreation() {
        String sid = createTestSession();
        store.save(sid, "user", blocks("First"), false);
        store.save(sid, "assistant", blocks("Second"), false);
        store.save(sid, "user", blocks("Third"), false);

        List<MemoryMessage> messages = store.loadForContext(sid);

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).text()).isEqualTo("First");
        assertThat(messages.get(2).text()).isEqualTo("Third");
    }

    @Test
    void countUserMessages_returnsCount() {
        String sid = createTestSession();
        store.save(sid, "user", blocks("msg1"), false);
        store.save(sid, "assistant", blocks("reply1"), false);
        store.save(sid, "user", blocks("msg2"), false);

        int count = store.countUserMessages(sid);

        assertThat(count).isEqualTo(2);
    }

    @Test
    void sumUserContentLength_sumsChars() {
        String sid = createTestSession();
        store.save(sid, "user", blocks("Hello"), false);      // 5 chars
        store.save(sid, "user", blocks("World!"), false);     // 6 chars

        int total = store.sumUserContentLength(sid);

        assertThat(total).isEqualTo(11);
    }

    @Test
    void countConversationTurns_returnsUserAssistantPairs() {
        String sid = createTestSession();
        store.save(sid, "user", blocks("Q1"), false);
        store.save(sid, "assistant", blocks("A1"), false);
        store.save(sid, "user", blocks("Q2"), false);
        store.save(sid, "assistant", blocks("A2"), false);

        int turns = store.countConversationTurns(sid);

        assertThat(turns).isEqualTo(2);
    }

    @Test
    void countToolMessages_returnsCount() {
        String sid = createTestSession();
        store.save(sid, "user", blocks("query"), false);
        store.save(sid, "tool", blocks("tool result 1"), false);
        store.save(sid, "tool", blocks("tool result 2"), false);

        int count = store.countToolMessages(sid);

        assertThat(count).isEqualTo(2);
    }

    @Test
    void avgAssistantReplyLength_computesAverage() {
        String sid = createTestSession();
        store.save(sid, "assistant", blocks("Hello"), false);      // 5 chars
        store.save(sid, "assistant", blocks("Hello World!"), false); // 12 chars

        int avg = store.avgAssistantReplyLength(sid);

        assertThat(avg).isEqualTo(8); // (5 + 12) / 2 = 8
    }

    @Test
    void hasUserQuestions_trueWhenUserMessages() {
        String sid = createTestSession();
        store.save(sid, "user", blocks("What is this?"), false);

        assertThat(store.hasUserQuestions(sid)).isTrue();
    }

    @Test
    void hasUserQuestions_falseWhenNoUserMessages() {
        String sid = createTestSession();
        store.save(sid, "assistant", blocks("Hello"), false);

        assertThat(store.hasUserQuestions(sid)).isFalse();
    }

    @Test
    void loadPage_ascendingOrder() {
        String sid = createTestSession();
        store.save(sid, "user", blocks("First"), false);
        store.save(sid, "assistant", blocks("Second"), false);
        store.save(sid, "user", blocks("Third"), false);

        List<MemoryMessage> page = store.loadPage(sid, 0, 10, true);

        assertThat(page).hasSize(3);
        assertThat(page.get(0).text()).isEqualTo("First");
        assertThat(page.get(2).text()).isEqualTo("Third");
    }

    @Test
    void loadPage_descendingOrder_returnsChronological() {
        String sid = createTestSession();
        store.save(sid, "user", blocks("First"), false);
        store.save(sid, "assistant", blocks("Second"), false);
        store.save(sid, "user", blocks("Third"), false);

        List<MemoryMessage> page = store.loadPage(sid, Long.MAX_VALUE, 10, false);

        assertThat(page).hasSize(3);
        assertThat(page.get(0).text()).isEqualTo("First");
        assertThat(page.get(2).text()).isEqualTo("Third");
    }

    @Test
    void loadPage_withCursorAndLimit() {
        String sid = createTestSession();
        store.save(sid, "user", blocks("First"), false);
        store.save(sid, "assistant", blocks("Second"), false);
        store.save(sid, "user", blocks("Third"), false);

        List<MemoryMessage> all = store.loadPage(sid, 0, 10, true);
        long cursor = all.get(0).id();

        List<MemoryMessage> page2 = store.loadPage(sid, cursor, 10, true);
        assertThat(page2).hasSize(2);
        assertThat(page2.get(0).text()).isEqualTo("Second");
    }

    @Test
    void countByRole_returnsCount() {
        String sid = createTestSession();
        store.save(sid, "user", blocks("q1"), false);
        store.save(sid, "user", blocks("q2"), false);
        store.save(sid, "assistant", blocks("a1"), false);

        assertThat(store.countByRole(sid, "user")).isEqualTo(2);
        assertThat(store.countByRole(sid, "assistant")).isEqualTo(1);
        assertThat(store.countByRole(sid, "tool")).isEqualTo(0);
    }

    @Test
    void loadSessionStats_returnsCompleteStats() {
        String sid = createTestSession();
        store.save(sid, "user", blocks("What is Java?"), false);
        store.save(sid, "assistant", blocks("Java is a programming language."), false);
        store.save(sid, "tool", blocks("search results"), false);
        store.save(sid, "user", blocks("Tell me more"), false);
        store.save(sid, "assistant", blocks("Sure, here are details."), false);

        MessageStore.SessionStats stats = store.loadSessionStats(sid);

        assertThat(stats.userMsgCount()).isEqualTo(2);
        assertThat(stats.userCharCount()).isEqualTo(25); // "What is Java?" (13) + "Tell me more" (12)
        assertThat(stats.conversationTurns()).isEqualTo(2);
        assertThat(stats.toolMsgCount()).isEqualTo(1);
        assertThat(stats.hasUserQuestions()).isTrue();
    }

    private static String createTestSession() {
        MysqlSessionStore sessionStore = new MysqlSessionStore();
        Session s = sessionStore.create(TEST_USER);
        return s.id();
    }
}
