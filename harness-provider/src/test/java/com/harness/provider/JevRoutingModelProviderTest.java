package com.harness.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.ThinkingLevel;
import com.harness.core.modelconfig.ModelConfig;
import com.harness.provider.impl.JevRoutingModelProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class JevRoutingModelProviderTest {
    @Test
    void sendsThreeTypedQuestionsAndRejectsMalformedAnswers() throws Exception {
        var mapper = new ObjectMapper();
        var request = new AtomicReference<String>();
        var token = new AtomicReference<String>();
        var body = new AtomicReference<>("""
                {"answers":{"thinkingLevel":{"type":"choice","choice":"high"},
                "needsKnowledgeBase":{"type":"choice","choice":"true"},
                "needsWebSearch":{"type":"choice","choice":"false"}}}
                """);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var provider = new JevRoutingModelProvider(ModelConfig.of(Map.of(
                    "routing.apiKey", "test-key", "routing.model", "jev-latest",
                    "routing.baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")));
            var result = provider.route("Analyze the internal architecture");
            assertThat(result.thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
            assertThat(result.needsKnowledgeBase()).isTrue();
            assertThat(result.needsWebSearch()).isFalse();
            assertThat(token.get()).isEqualTo("Bearer test-key");
            assertThat(mapper.readTree(request.get()).path("questions").size()).isEqualTo(3);
            body.set("{\"answers\":{}}");
            assertThatThrownBy(() -> provider.route("Hello")).hasMessageContaining("Invalid JEV routing answer");
            body.set("");
            assertThatThrownBy(() -> provider.route("Hello")).hasMessageContaining("Invalid JEV routing answer map");
        } finally {
            server.stop(0);
        }
    }
}
