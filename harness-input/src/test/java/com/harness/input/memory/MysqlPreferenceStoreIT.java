package com.harness.input.memory;

import com.harness.core.model.Preference;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.MysqlConnectionPool;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class MysqlPreferenceStoreIT {

    static final String TEST_USER = "it_user_pref";

    MysqlPreferenceStore store;

    @BeforeAll
    static void initEnv() {
        EnvConfig.init(Map.of(
                "HARNESS_AUDIT_DB_URL", "jdbc:mysql://localhost:3306/agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                "HARNESS_AUDIT_DB_USER", "root",
                "HARNESS_AUDIT_DB_PASS", "1234",
                "HARNESS_AUDIT_STORE", "mysql"
        ));
    }

    @BeforeEach
    void setUp() {
        store = new MysqlPreferenceStore();
        // Clean before each test to ensure isolation
        try (Connection conn = MysqlConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM user_preferences WHERE user_id = ?")) {
            ps.setString(1, TEST_USER);
            ps.executeUpdate();
        } catch (SQLException e) {
            // ignore
        }
    }

    @AfterAll
    static void cleanUp() {
        try (Connection conn = MysqlConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM user_preferences WHERE user_id = ?")) {
            ps.setString(1, TEST_USER);
            ps.executeUpdate();
        } catch (SQLException e) {
            // ignore cleanup errors
        }
    }

    @Test
    void upsert_createsNewPreference() {
        store.upsert(TEST_USER, "style", "Be concise", "session-1");

        List<Preference> prefs = store.loadByUser(TEST_USER);

        assertThat(prefs).hasSize(1);
        assertThat(prefs.get(0).userId()).isEqualTo(TEST_USER);
        assertThat(prefs.get(0).category()).isEqualTo("style");
        assertThat(prefs.get(0).content()).isEqualTo("Be concise");
        assertThat(prefs.get(0).sourceSessionId()).isEqualTo("session-1");
    }

    @Test
    void upsert_updatesExistingSameCategory() {
        store.upsert(TEST_USER, "tone", "Formal", "session-1");
        store.upsert(TEST_USER, "tone", "Casual", "session-2");

        List<Preference> prefs = store.loadByUser(TEST_USER);

        // Should still be 1 entry for "tone", updated to latest
        List<Preference> tonePrefs = prefs.stream()
                .filter(p -> p.category().equals("tone"))
                .toList();
        assertThat(tonePrefs).hasSize(1);
        assertThat(tonePrefs.get(0).content()).isEqualTo("Casual");
        assertThat(tonePrefs.get(0).sourceSessionId()).isEqualTo("session-2");
    }

    @Test
    void loadByUser_multipleCategories_returnsAll() {
        store.upsert(TEST_USER, "lang", "English", "s1");
        store.upsert(TEST_USER, "verbosity", "Detailed", "s1");

        List<Preference> prefs = store.loadByUser(TEST_USER);

        assertThat(prefs).extracting(Preference::category).contains("lang", "verbosity");
    }

    @Test
    void loadByUser_nonexistentUser_returnsEmpty() {
        List<Preference> prefs = store.loadByUser("nonexistent_user_12345");

        assertThat(prefs).isEmpty();
    }
}
