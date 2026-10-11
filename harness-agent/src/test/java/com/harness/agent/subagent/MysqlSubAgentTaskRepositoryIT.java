package com.harness.agent.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.*;
import com.harness.core.model.CancellationToken;
import com.harness.core.model.MessageBlock;
import com.harness.input.memory.*;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class MysqlSubAgentTaskRepositoryIT {
    private static String database;
    private static String baseUrl;
    private static String isolatedUrl;
    private static String user;
    private static String password;
    private final AgentRunContext.Owner owner = new AgentRunContext.Owner("owner", "tenant", "business");
    private MysqlSubAgentTaskRepository repository;

    @BeforeAll static void createIsolatedDatabase() throws Exception {
        String configured = System.getenv("HARNESS_TEST_MYSQL_URL");
        Assumptions.assumeTrue(configured != null && configured.matches("jdbc:mysql://[^/]+/[^?]*_test[^?]*(\\?.*)?"),
                "An explicit isolated MySQL URL containing _test is required");
        user = System.getenv("HARNESS_TEST_MYSQL_USER");
        password = System.getenv("HARNESS_TEST_MYSQL_PASSWORD");
        Assumptions.assumeTrue(user != null && password != null, "Explicit test credentials required");
        int start = configured.indexOf('/', "jdbc:mysql://".length());
        int query = configured.indexOf('?', start);
        String options = query < 0 ? "" : configured.substring(query);
        baseUrl = configured.substring(0, start + 1) + options;
        database = "cyrene_subagent_it_" + UUID.randomUUID().toString().replace("-", "");
        isolatedUrl = configured.substring(0, start + 1) + database + options;
        try (Connection connection = DriverManager.getConnection(baseUrl, user, password); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        }
        Path schema = Path.of("..", "sql", "schema-mysql.sql");
        String sql = Files.readString(schema);
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            for (String table : List.of("`messages`", "subagent_tasks")) {
                var match = Pattern.compile("CREATE TABLE IF NOT EXISTS `?" + Pattern.quote(table) + "`? \\([\\s\\S]*?;").matcher(sql);
                if (!match.find()) throw new IllegalStateException("Missing schema: " + table);
                statement.execute(match.group());
            }
        }
    }

    @BeforeEach void repository() throws Exception {
        repository = new MysqlSubAgentTaskRepository(MysqlSubAgentTaskRepositoryIT::connection, new ObjectMapper(), Duration.ofDays(1));
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM messages");
            statement.executeUpdate("DELETE FROM subagent_tasks");
        }
    }

    @AfterAll static void removeExactIsolatedDatabase() throws Exception {
        if (database == null) return;
        if (!database.matches("cyrene_subagent_it_[0-9a-f]{32}")) throw new IllegalStateException("Unsafe test database target");
        try (Connection connection = DriverManager.getConnection(baseUrl, user, password); Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE `" + database + "`");
        }
    }

    @Test void completePersistsBeforeFutureNotificationAndRecallIsRepeatableAndScoped() {
        var task = task();
        var observed = task.completion().thenApply(result -> repository.findAuthorized(owner, "session", task.taskId()).orElseThrow());
        task.succeed(result(task));
        assertThat(observed.join().result().output()).isEqualTo("done");
        assertThat(observed.join().eventId()).isEqualTo("subagent:" + task.taskId());
        assertThat(repository.findAuthorized(owner, "session", task.taskId()).orElseThrow().deliveryState()).isEqualTo(ResultDeliveryState.INLINE_PENDING);
        assertThat(repository.findAuthorized(owner, "other", task.taskId())).isEmpty();
        assertThat(repository.findAuthorized(new AgentRunContext.Owner("other", "tenant", "business"), "session", task.taskId())).isEmpty();
        assertThat(repository.findAuthorized(new AgentRunContext.Owner("owner", "other", "business"), "session", task.taskId())).isEmpty();
        assertThatThrownBy(() -> repository.create(task)).isInstanceOf(IllegalStateException.class);
        assertThat(task.taskId()).matches("sub-[0-9a-f-]{36}");
    }

    @Test void restartInterruptsWithoutReplayAndDirectoryCursorReturnsDistinctTasks() {
        var first = task();
        var second = task();
        second.start();
        assertThat(repository.interruptUnfinished(100)).isEqualTo(2);
        var restarted = new MysqlSubAgentTaskRepository(MysqlSubAgentTaskRepositoryIT::connection, new ObjectMapper(), Duration.ofDays(1));
        assertThat(restarted.findAuthorized(owner, "session", first.taskId()).orElseThrow().status()).isEqualTo(SubAgentStatus.INTERRUPTED);
        var page = restarted.listAuthorized(owner, "session", "", 1);
        var next = restarted.listAuthorized(owner, "session", page.pageInfo().nextCursor(), 1);
        assertThat(page.items()).hasSize(1);
        assertThat(next.items()).hasSize(1);
        assertThat(next.items().getFirst().taskId()).isNotEqualTo(page.items().getFirst().taskId());
        assertThat(next.pageInfo().hasMore()).isFalse();
    }

    @Test void leasesFenceStaleAcknowledgementsAndSuppressionPreventsResume() throws Exception {
        var task = task(); task.detach(); task.succeed(result(task));
        var first = repository.claimDeliveries("session", Duration.ofMinutes(1), 10).getFirst();
        assertThat(repository.claimDeliveries("session", Duration.ofMinutes(1), 10)).isEmpty();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE subagent_tasks SET delivery_lease_until='2000-01-01' WHERE task_id=?")) {
            statement.setString(1, task.taskId()); statement.executeUpdate();
        }
        var retry = repository.claimDeliveries("session", Duration.ofMinutes(1), 10).getFirst();
        assertThat(retry.eventId()).isEqualTo(first.eventId());
        assertThat(retry.leaseToken()).isNotEqualTo(first.leaseToken());
        repository.acknowledgeDelivery(first.eventId(), first.leaseToken());
        assertThat(repository.findAuthorized(owner, "session", task.taskId()).orElseThrow().deliveryState()).isEqualTo(ResultDeliveryState.DELIVERY_CLAIMED);
        repository.releaseDelivery(retry.eventId(), retry.leaseToken());
        var finalClaim = repository.claimDeliveries("session", Duration.ofMinutes(1), 10).getFirst();
        repository.acknowledgeDelivery(finalClaim.eventId(), finalClaim.leaseToken());
        assertThat(repository.findAuthorized(owner, "session", task.taskId()).orElseThrow().deliveryState()).isEqualTo(ResultDeliveryState.SESSION_RESUMED);
        var suppressed = task(); suppressed.detach(); suppressed.succeed(result(suppressed));
        assertThat(repository.suppressSession("session")).isEqualTo(1);
        assertThat(repository.claimDeliveries("session", Duration.ofMinutes(1), 10)).isEmpty();
    }

    @Test void taskSuppressionSurvivesRestartWithoutSuppressingAnotherRunOrConsumedResults() {
        var cancelled = task(); cancelled.succeed(result(cancelled));
        var current = task();
        cancelled.suppressDelivery();
        repository.interruptUnfinished(100);
        var restarted = new MysqlSubAgentTaskRepository(MysqlSubAgentTaskRepositoryIT::connection, new ObjectMapper(), Duration.ofDays(1));
        assertThat(restarted.findAuthorized(owner, "session", cancelled.taskId()).orElseThrow().deliveryState())
                .isEqualTo(ResultDeliveryState.SUPPRESSED);
        assertThat(restarted.findAuthorized(owner, "session", current.taskId()).orElseThrow().deliveryState())
                .isEqualTo(ResultDeliveryState.DETACHED);
        var delivered = restarted.claimDeliveries("session", Duration.ofMinutes(1), 10);
        assertThat(delivered).extracting(SessionInbox.SubAgentCompletedEvent::taskId).containsExactly(current.taskId());
        var event = delivered.getFirst();
        restarted.acknowledgeDelivery(event.eventId(), event.leaseToken());
        restarted.suppressTask(current.taskId());
        assertThat(restarted.findAuthorized(owner, "session", current.taskId()).orElseThrow().deliveryState())
                .isEqualTo(ResultDeliveryState.SESSION_RESUMED);
        var inline = task(); inline.succeed(result(inline)); inline.consumeInline();
        restarted.suppressTask(inline.taskId());
        assertThat(restarted.findAuthorized(owner, "session", inline.taskId()).orElseThrow().deliveryState())
                .isEqualTo(ResultDeliveryState.INLINE_CONSUMED);
    }

    @Test void suppressingAClaimedTaskFencesItsLateAcknowledgement() {
        var cancelled = task(); cancelled.detach(); cancelled.succeed(result(cancelled));
        var event = repository.claimDeliveries("session", Duration.ofMinutes(1), 1).getFirst();
        cancelled.suppressDelivery();
        repository.acknowledgeDelivery(event.eventId(), event.leaseToken());
        repository.releaseDelivery(event.eventId(), event.leaseToken());
        assertThat(repository.findAuthorized(owner, "session", cancelled.taskId()).orElseThrow().deliveryState())
                .isEqualTo(ResultDeliveryState.SUPPRESSED);
        assertThat(repository.claimDeliveries("session", Duration.ofMinutes(1), 1)).isEmpty();
    }

    @Test void repeatedEventsAndReplyMarkersPersistOnlyOneMessageAndExpireInBoundedBatches() throws Exception {
        var store = new MysqlMessageStore(MysqlSubAgentTaskRepositoryIT::connection);
        var event = new MessageWrite("session", "trace", "subagent_event", List.of(new MessageBlock(MessageBlock.BlockType.TEXT, "done", null)), false);
        assertThat(store.appendOnce(event, "event")).isEqualTo(store.appendOnce(event, "event"));
        var reply = new MessageWrite("session", "trace", "assistant", event.content(), false);
        var marker = new MessageWrite("session", "trace", "subagent_resume_done", List.of(), false);
        var writes = List.of(new MessageStore.EventMessage(reply, "reply"), new MessageStore.EventMessage(marker, "marker"));
        assertThat(store.appendOnceBatch(writes)).isEqualTo(store.appendOnceBatch(writes));
        assertThat(store.loadForContext("session")).hasSize(3);
        var task = task(); task.succeed(result(task));
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("UPDATE subagent_tasks SET expires_at='2000-01-01' WHERE task_id=?")) {
            statement.setString(1, task.taskId()); statement.executeUpdate();
        }
        assertThat(repository.findAuthorized(owner, "session", task.taskId())).isEmpty();
        assertThat(repository.deleteExpired(1)).isEqualTo(1);
        assertThat(repository.deleteExpired(1)).isZero();
    }

    private SubAgentTaskRecord task() {
        String id = SubAgentManager.generateTaskId();
        var definition = SubAgentTask.create(id, "task", null, null, null, List.of(), List.of(), null);
        var record = new SubAgentTaskRecord(id, "run", "session", "turn", definition, new CancellationToken(), owner, "call-" + id, "root", repository);
        repository.create(record); return record;
    }
    private SubAgentResult result(SubAgentTaskRecord task) {
        return new SubAgentResult(task.taskId(), "done", null, true, SubAgentStatus.SUCCEEDED, List.of(), ToolExecutionSummary.empty(), ContractValidation.notDeclared(), null, 1, "child");
    }
    private static Connection connection() throws SQLException { return DriverManager.getConnection(isolatedUrl, user, password); }
}
