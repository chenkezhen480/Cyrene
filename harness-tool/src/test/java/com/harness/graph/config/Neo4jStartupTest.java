package com.harness.graph.config;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.graph.schema.GraphSchemaRegistry;
import com.harness.graph.store.GraphStoreException;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Neo4jStartupTest {
    @Test
    void stoppedNeo4jIdentifiesServiceAndComposeCommand() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        EnvConfig.init(Map.of(EnvKey.GRAPH_PROVIDER, "neo4j", EnvKey.GRAPH_NEO4J_URI, "bolt://127.0.0.1:" + port,
                EnvKey.GRAPH_NEO4J_PASSWORD, "privatePassword", EnvKey.GRAPH_CONNECT_TIMEOUT_SECONDS, "1"));
        assertThatThrownBy(() -> KnowledgeGraphStoreFactory.create(new GraphSchemaRegistry()))
                .isInstanceOf(GraphStoreException.class).hasMessageContaining("Neo4j 启动连接检查失败")
                .hasMessageContaining("127.0.0.1:" + port).hasMessageContaining("up -d neo4j")
                .hasMessageNotContaining("privatePassword");
    }
}
