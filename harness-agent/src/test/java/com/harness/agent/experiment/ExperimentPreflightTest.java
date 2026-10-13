package com.harness.agent.experiment;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExperimentPreflightTest {
    @Test
    void rejectsUnrecognizedFixtureBeforeAdminStateOrModelSetup() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var stateRequests = new AtomicInteger();
        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"ok\",\"fixture\":\"other-system\",\"benchmarkDate\":\"2026-10-12\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/_experiment/state", exchange -> { stateRequests.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            assertThatThrownBy(() -> new CyreneExperimentIT().preflight(base, "synthetic-admin", "2026-10-12"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(base + "/health").hasMessageContaining("fixture marker");
            assertThat(stateRequests.get()).isZero();
        } finally { server.stop(0); }
    }

    @Test
    void preservesExactEndpointWhenHealthHttpRequestFails() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> { exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            assertThatThrownBy(() -> new CyreneExperimentIT().preflight(base, "synthetic-admin", "2026-10-12"))
                    .hasMessageContaining(base + "/health").hasMessageContaining("HTTP 503");
        } finally { server.stop(0); }
    }

    @Test
    void adminResetUsesFixtureJsonRequestContract() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/_experiment/reset", exchange -> {
            boolean valid = "POST".equals(exchange.getRequestMethod())
                    && "application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))
                    && "{}".equals(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"status\":\"reset\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(valid ? 200 : 400, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            assertThat(new CyreneExperimentIT().request(base + "/_experiment/reset", "synthetic-admin", "POST")
                    .path("status").asText()).isEqualTo("reset");
        } finally { server.stop(0); }
    }
}
