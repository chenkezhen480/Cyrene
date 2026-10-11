package com.harness.server;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.env.MysqlConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MysqlStartupTest {
    @AfterEach
    void closePool() { MysqlConnectionPool.shutdown(); }

    @Test
    void stoppedMysqlProducesActionableStartupError() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        EnvConfig.init(Map.of(EnvKey.AUDIT_DB_URL, "jdbc:mysql://127.0.0.1:" + port + "/agent?connectTimeout=100",
                EnvKey.AUDIT_DB_USER, "test", EnvKey.AUDIT_DB_PASS, "privatePassword"));
        assertThatThrownBy(MysqlConnectionPool::init).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MySQL 启动连接检查失败").hasMessageContaining("127.0.0.1:" + port)
                .hasMessageContaining("HARNESS_AUDIT_DB_URL").hasMessageContaining("up -d mysql")
                .hasMessageNotContaining("privatePassword");
    }
}
