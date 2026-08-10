package com.harness.input.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkItDownDocumentConversionServiceTest {

    private HttpServer server;
    private String workerUrl;
    private AtomicReference<String> authorization;
    private AtomicReference<String> contentType;
    private AtomicReference<String> requestBody;
    private AtomicReference<ResponseStub> response;

    @BeforeEach
    void setUp() throws Exception {
        authorization = new AtomicReference<>();
        contentType = new AtomicReference<>();
        requestBody = new AtomicReference<>();
        response = new AtomicReference<>(new ResponseStub(200, successResponse()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/convert", this::handleConvert);
        server.start();
        workerUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsMultipartDocumentAndParsesStructuredResult() {
        MarkItDownDocumentConversionService service = service("parser-secret");

        DocumentConversionResult result = service.convert(
                "%PDF-test".getBytes(StandardCharsets.UTF_8),
                "report.pdf",
                "application/pdf");

        assertThat(result.markdown()).isEqualTo("# Report\n\nParsed text");
        assertThat(result.title()).isEqualTo("Report");
        assertThat(result.detectedMimeType()).isEqualTo("application/pdf");
        assertThat(result.diagnostics().converter()).isEqualTo("markitdown");
        assertThat(result.diagnostics().model()).isEqualTo("vision-model");
        assertThat(result.diagnostics().ocrEnabled()).isTrue();
        assertThat(result.diagnostics().warnings()).containsExactly("one warning");
        assertThat(result.diagnostics().visionSource()).isEqualTo("vision");
        assertThat(result.diagnostics().visionCalls()).isEqualTo(2);
        assertThat(result.diagnostics().elapsedMs()).isEqualTo(125);
        assertThat(result.diagnostics().inputBytes()).isEqualTo(9);

        assertThat(authorization.get()).isEqualTo("Bearer parser-secret");
        assertThat(contentType.get()).startsWith("multipart/form-data; boundary=");
        assertThat(requestBody.get())
                .contains("name=\"file\"; filename=\"report.pdf\"")
                .contains("Content-Type: application/pdf")
                .contains("%PDF-test")
                .contains("name=\"fileName\"")
                .contains("report.pdf")
                .contains("name=\"mimeType\"")
                .contains("application/pdf");
    }

    @Test
    void omitsAuthorizationWhenTokenIsBlank() {
        service("").convert(
                "hello".getBytes(StandardCharsets.UTF_8),
                "note.txt",
                "text/plain");

        assertThat(authorization.get()).isNull();
    }

    @Test
    void exposesStructuredRemoteErrorWithoutFallback() {
        response.set(new ResponseStub(422, """
                {
                  "error": {
                    "code": "DOCUMENT_CONVERSION_FAILED",
                    "message": "MarkItDown could not parse this file",
                    "details": {"fileName": "broken.pdf"}
                  }
                }
                """));

        assertThatThrownBy(() -> service("secret").convert(
                new DocumentConversionRequest(
                        "broken".getBytes(StandardCharsets.UTF_8),
                        "broken.pdf",
                        "application/pdf")))
                .isInstanceOfSatisfying(
                        DocumentConversionException.class,
                        error -> {
                            assertThat(error.statusCode()).isEqualTo(422);
                            assertThat(error.errorCode())
                                    .isEqualTo("DOCUMENT_CONVERSION_FAILED");
                            assertThat(error).hasMessageContaining(
                                    "MarkItDown could not parse this file");
                        });
    }

    @Test
    void rejectsSuccessfulResponseWithoutMarkdown() {
        response.set(new ResponseStub(200, """
                {
                  "markdown": "",
                  "title": null,
                  "detectedMimeType": "application/pdf",
                  "diagnostics": {
                    "converter": "markitdown",
                    "model": null,
                    "ocrEnabled": false,
                    "warnings": [],
                    "visionSource": "disabled",
                    "visionCalls": 0,
                    "elapsedMs": 5,
                    "inputBytes": 2
                  }
                }
                """));

        assertThatThrownBy(() -> service("").convert(
                "hi".getBytes(StandardCharsets.UTF_8),
                "empty.pdf",
                "application/pdf"))
                .isInstanceOf(DocumentConversionException.class)
                .hasMessageContaining("invalid response")
                .hasMessageContaining("markdown must not be blank");
    }

    @Test
    void requestDefensivelyCopiesFileData() {
        byte[] fileData = "original".getBytes(StandardCharsets.UTF_8);
        DocumentConversionRequest request = new DocumentConversionRequest(
                fileData, "note.txt", null);

        fileData[0] = 'X';
        byte[] returned = request.fileData();
        returned[1] = 'X';

        assertThat(new String(request.fileData(), StandardCharsets.UTF_8))
                .isEqualTo("original");
        assertThat(request.mimeType()).isEqualTo("application/octet-stream");
    }

    @Test
    void validatesWorkerUrlAtConstruction() {
        assertThatThrownBy(() -> new MarkItDownDocumentConversionService(
                new OkHttpClient(),
                new ObjectMapper(),
                "file:///tmp/parser",
                ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute HTTP(S)");
    }

    private MarkItDownDocumentConversionService service(String token) {
        return new MarkItDownDocumentConversionService(
                new OkHttpClient(), new ObjectMapper(), workerUrl, token);
    }

    private void handleConvert(HttpExchange exchange) throws IOException {
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
        requestBody.set(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        ResponseStub stub = response.get();
        byte[] body = stub.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(stub.statusCode(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String successResponse() {
        return """
                {
                  "markdown": "# Report\\n\\nParsed text",
                  "title": "Report",
                  "detectedMimeType": "application/pdf",
                  "diagnostics": {
                    "converter": "markitdown",
                    "model": "vision-model",
                    "ocrEnabled": true,
                    "warnings": ["one warning"],
                    "visionSource": "vision",
                    "visionCalls": 2,
                    "elapsedMs": 125,
                    "inputBytes": 9
                  }
                }
                """;
    }

    private record ResponseStub(int statusCode, String body) {}
}
