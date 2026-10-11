package com.harness.core.env;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.sql.SQLException;
import java.util.Map;
import redis.clients.jedis.exceptions.JedisAccessControlException;

import static org.assertj.core.api.Assertions.assertThat;

class MiddlewareConnectionDiagnosticsTest {
    @Test
    void identifiesEveryMiddlewareAndItsComposeServiceWithoutLeakingCredentials() {
        Map<MiddlewareConnectionDiagnostics.Service, String> services = Map.of(
                MiddlewareConnectionDiagnostics.Service.MYSQL, "mysql",
                MiddlewareConnectionDiagnostics.Service.REDIS, "redis",
                MiddlewareConnectionDiagnostics.Service.MILVUS, "milvus",
                MiddlewareConnectionDiagnostics.Service.NEO4J, "neo4j");
        services.forEach((service, dockerService) -> {
            String message = MiddlewareConnectionDiagnostics.failureMessage(service,
                    "http://privateUser:privatePassword@localhost:12345/privateDb?token=privateToken",
                    new IllegalStateException("privateDriverMessage", new ConnectException("refused")));
            assertThat(message).contains("localhost:12345", "服务可能未启动或不可达",
                    "docker compose --env-file .env -f docker/docker-compose.yml up -d " + dockerService)
                    .doesNotContain("privateUser", "privatePassword", "privateDb", "privateToken", "privateDriverMessage");
        });
        assertThat(MiddlewareConnectionDiagnostics.endpoint("jdbc:mysql://localhost:3306/agent?password=secret"))
                .isEqualTo("localhost:3306");
        assertThat(MiddlewareConnectionDiagnostics.endpoint("bolt://[::1]:7687")).contains("::1", ":7687");
    }

    @Test
    void distinguishesMysqlCredentialsPermissionsAndMissingDatabaseFromStoppedService() {
        assertThat(mysqlFailure(new SQLException("secret", "28000", 1045))).contains("认证失败").doesNotContain("未启动", "secret");
        assertThat(mysqlFailure(new SQLException("secret", "42000", 1044))).contains("访问被拒绝").doesNotContain("未启动");
        assertThat(mysqlFailure(new SQLException("secret", "42000", 1049))).contains("数据库不存在").doesNotContain("未启动");
        assertThat(mysqlFailure(new IllegalArgumentException("privateBadUrl"))).contains("配置无效").doesNotContain("privateBadUrl");
    }

    @Test
    void malformedUrlDoesNotExposeOriginalValue() {
        assertThat(MiddlewareConnectionDiagnostics.endpoint("malformed privatePassword"))
                .isEqualTo("连接地址无效");
    }

    @Test
    void redisAuthenticationErrorsAreNotReportedAsStoppedService() {
        String message = MiddlewareConnectionDiagnostics.failureMessage(MiddlewareConnectionDiagnostics.Service.REDIS,
                "redis://localhost:6379", new IllegalStateException("pool failed",
                        new JedisAccessControlException("WRONGPASS invalid credentials")));
        assertThat(message).contains("认证或授权失败").doesNotContain("未启动");
    }

    private String mysqlFailure(Throwable failure) {
        return MiddlewareConnectionDiagnostics.failureMessage(MiddlewareConnectionDiagnostics.Service.MYSQL,
                "jdbc:mysql://localhost:3306/agent", new IllegalStateException("pool failed", failure));
    }
}
