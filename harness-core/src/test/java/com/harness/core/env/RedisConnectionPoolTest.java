package com.harness.core.env;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.ServerSocket;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class RedisConnectionPoolTest {
    @AfterEach
    void closePool() { RedisConnectionPool.shutdown(); }

    @Test
    void startupActuallyConnectsAndFailedPoolIsNotPublished() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        EnvConfig.init(Map.of(EnvKey.MEMORY_REDIS_URL, "redis://127.0.0.1:" + port,
                EnvKey.MEMORY_REDIS_PASSWORD, "", EnvKey.MEMORY_REDIS_DB, "0"));
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(RedisConnectionPool::init).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Redis 启动连接检查失败")
                    .hasMessageContaining("127.0.0.1:" + port).hasMessageContaining("up -d redis");
        }
    }

    @Test
    void invalidUrlReportsConfigurationErrorInsteadOfConnectingToDefaultHost() {
        EnvConfig.init(Map.of(EnvKey.MEMORY_REDIS_URL, "redis:/privatePassword"));
        assertThatThrownBy(RedisConnectionPool::init).hasMessageContaining("Redis")
                .hasMessageContaining("配置无效").hasMessageNotContaining("privatePassword");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "HARNESS_TEST_REDIS_URL", matches = "redis://127\\.0\\.0\\.1:[0-9]+")
    void realRedisChecksAuthenticationAndCanInitializeAfterFailure() {
        EnvConfig.init(Map.of(EnvKey.MEMORY_REDIS_URL, System.getenv("HARNESS_TEST_REDIS_URL"),
                EnvKey.MEMORY_REDIS_PASSWORD, "invalidTestPassword", EnvKey.MEMORY_REDIS_DB, "0"));
        assertThatThrownBy(RedisConnectionPool::init).hasMessageContaining("Redis")
                .hasMessageContaining("认证或授权失败").hasMessageNotContaining("未启动");
        EnvConfig.init(Map.of(EnvKey.MEMORY_REDIS_URL, System.getenv("HARNESS_TEST_REDIS_URL"),
                EnvKey.MEMORY_REDIS_PASSWORD, System.getenv("HARNESS_TEST_REDIS_PASSWORD"), EnvKey.MEMORY_REDIS_DB, "0"));
        RedisConnectionPool.init();
        RedisConnectionPool.init();
        try (var connection = RedisConnectionPool.getConnection()) { assertThat(connection.ping()).isEqualTo("PONG"); }
    }
}
