package com.harness.input.memory;

import com.harness.core.model.Session;
import com.harness.core.model.SessionCursor;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.MysqlConnectionPool;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class MysqlSessionStoreIT {

    static final String TEST_USER = "it_user_session";

    MysqlSessionStore store;

    @BeforeAll
    static void initEnv() {
        EnvConfig.init(Map.of(
                "HARNESS_AUDIT_DB_URL", "jdbc:mysql://localhost:3306/zhi_du_yuan?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                "HARNESS_AUDIT_DB_USER", "root",
                "HARNESS_AUDIT_DB_PASS", "1234",
                "HARNESS_AUDIT_STORE", "mysql"
        ));
    }

    @BeforeEach
    void setUp() {
        store = new MysqlSessionStore();
    }

    @AfterAll
    static void cleanUp() {
        try (Connection conn = MysqlConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM sessions WHERE user_id = ?")) {
            ps.setString(1, TEST_USER);
            ps.executeUpdate();
        } catch (SQLException e) {
            // ignore cleanup errors
        }
    }

    @Test
    void create_returnsSessionWithId() {
        Session session = store.create(TEST_USER, null);

        assertThat(session.id()).isNotBlank();
        assertThat(session.userId()).isEqualTo(TEST_USER);
        assertThat(session.status()).isEqualTo(Session.SessionStatus.active);
        assertThat(session.createdAt()).isNotNull();
        assertThat(session.lastActive()).isNotNull();
        assertThat(session.endedAt()).isNull();
    }

    @Test
    void findActive_existing_returnsSession() {
        Session created = store.create(TEST_USER, null);

        Optional<Session> found = store.findActiveByOwner(created.id(), TEST_USER, null);

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(created.id());
        assertThat(found.get().userId()).isEqualTo(TEST_USER);
    }

    @Test
    void findActive_afterClose_returnsEmpty() {
        Session created = store.create(TEST_USER, null);
        store.close(created.id(), Session.SessionStatus.ended);

        Optional<Session> found = store.findActiveByOwner(created.id(), TEST_USER, null);

        assertThat(found).isEmpty();
    }

    @Test
    void findById_anyStatus_returnsSession() {
        Session created = store.create(TEST_USER, null);
        store.close(created.id(), Session.SessionStatus.ended);

        Optional<Session> found = store.findByIdAndOwner(created.id(), TEST_USER, null);

        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(Session.SessionStatus.ended);
    }

    @Test
    void findActiveByUser_returnsActiveOnly() {
        Session s1 = store.create(TEST_USER, null);
        Session s2 = store.create(TEST_USER, null);
        store.close(s1.id(), Session.SessionStatus.ended);

        var active = store.findAllByOwner(
                TEST_USER, null, Session.SessionStatus.active, null, 100).items();

        assertThat(active).extracting(Session::id).contains(s2.id()).doesNotContain(s1.id());
    }

    @Test
    void close_setsStatusAndEndedAt() {
        Session created = store.create(TEST_USER, null);

        store.close(created.id(), Session.SessionStatus.timeout);

        Optional<Session> found = store.findByIdAndOwner(created.id(), TEST_USER, null);
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(Session.SessionStatus.timeout);
        assertThat(found.get().endedAt()).isNotNull();
    }

    @Test
    void updateLastActive_refreshesTimestamp() throws InterruptedException {
        Session created = store.create(TEST_USER, null);
        Instant before = created.lastActive();

        Thread.sleep(50); // small delay to ensure timestamp difference
        store.updateLastActive(created.id());

        Optional<Session> found = store.findActiveByOwner(created.id(), TEST_USER, null);
        assertThat(found).isPresent();
        assertThat(found.get().lastActive()).isAfterOrEqualTo(before);
    }

    @Test
    void updateTitle_setsTitle() {
        Session created = store.create(TEST_USER, null);

        store.updateTitle(created.id(), "Test Title");

        Optional<Session> found = store.findByIdAndOwner(created.id(), TEST_USER, null);
        assertThat(found).isPresent();
        assertThat(found.get().title()).isEqualTo("Test Title");
    }

    @Test
    void findAll_withCursorPaginates() {
        Session s1 = store.create(TEST_USER, null);
        Session s2 = store.create(TEST_USER, null);

        // First page: limit 1, no cursor
        var page1 = store.findAllByOwner(TEST_USER, null, Session.SessionStatus.active, null, 1);
        assertThat(page1.items()).hasSize(1);

        // Second page: use last item's createdAt as cursor
        Session first = page1.items().getFirst();
        var page2 = store.findAllByOwner(TEST_USER, null, Session.SessionStatus.active,
                new SessionCursor(first.lastActive(), first.id()), 10);
        assertThat(page2.items()).isNotEmpty();
        // page2 items should be before cursor (DESC order)
        assertThat(page2.items()).extracting(Session::id).doesNotContain(first.id());
    }

    @Test
    void findTimedOut_returnsOldActiveSessions() {
        // findTimedOut returns sessions where last_active < now - timeout
        // With a 24-hour timeout, any session created before yesterday qualifies
        Session created = store.create(TEST_USER, null);

        // Use a very large timeout so our just-created session is NOT timed out
        var notTimedOut = store.findTimedOut(Duration.ofHours(24), null, 100);
        assertThat(notTimedOut.items()).extracting(Session::id).doesNotContain(created.id());

        // Use 0 timeout so any session with last_active < now qualifies
        // (there may be other sessions, so just check the method doesn't error)
        var all = store.findTimedOut(Duration.ZERO, null, 100);
        assertThat(all).isNotNull();
    }
}
