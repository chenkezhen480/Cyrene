package com.harness.tool.rag;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import io.grpc.*;
import io.milvus.grpc.MilvusServiceGrpc;

import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MilvusStartupTest {
    @AfterEach
    void closePool() { MilvusConnectionPool.shutdown(); }

    @Test
    void stoppedMilvusFailsAtInitializationAndDoesNotPublishClient() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        EnvConfig.init(Map.of(EnvKey.RAG_URL, "http://127.0.0.1:" + port,
                EnvKey.RAG_DATABASE, "default", EnvKey.RAG_API_KEY, "",
                EnvKey.RAG_CONNECT_TIMEOUT_MS, "100", EnvKey.RAG_STARTUP_TIMEOUT_MS, "100"));
        assertThatThrownBy(MilvusConnectionPool::init).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Milvus 启动连接检查失败").hasMessageContaining("127.0.0.1:" + port)
                .hasMessageContaining("up -d milvus");
        assertThatThrownBy(MilvusConnectionPool::getClient).hasMessageContaining("not initialized");
    }

    @Test
    void unboundedStartupTimeoutIsRejectedBeforeOpeningClient() {
        EnvConfig.init(Map.of(EnvKey.RAG_STARTUP_TIMEOUT_MS, "0", EnvKey.RAG_DATABASE, "custom"));
        assertThatThrownBy(MilvusConnectionPool::init).hasMessageContaining("Milvus")
                .hasMessageContaining("配置无效");
        assertThatThrownBy(MilvusConnectionPool::getClient).hasMessageContaining("not initialized");
    }

    @Test
    void grpcAuthenticationFailureIsNotReportedAsStoppedMilvus() throws Exception {
        Server server = ServerBuilder.forPort(0).addService(new MilvusServiceGrpc.MilvusServiceImplBase() {})
                .intercept(new ServerInterceptor() {
                    @Override
                    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                            Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                        call.close(Status.UNAUTHENTICATED.withDescription("invalid credential"), new Metadata());
                        return new ServerCall.Listener<>() {};
                    }
                }).build().start();
        try {
            EnvConfig.init(Map.of(EnvKey.RAG_URL, "http://127.0.0.1:" + server.getPort(), EnvKey.RAG_DATABASE, "default",
                    EnvKey.RAG_API_KEY, "invalidTestToken", EnvKey.RAG_CONNECT_TIMEOUT_MS, "1000",
                    EnvKey.RAG_STARTUP_TIMEOUT_MS, "1000"));
            assertThatThrownBy(MilvusConnectionPool::init).hasMessageContaining("Milvus")
                    .hasMessageContaining("认证或授权失败").hasMessageNotContaining("未启动")
                    .hasMessageNotContaining("invalidTestToken");
        } finally { server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); }
    }
}
