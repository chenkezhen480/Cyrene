package com.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ApiEndpoint;
import com.harness.core.model.AuthMode;
import com.harness.core.model.ProjectApiConfig;
import com.harness.core.model.TokenInjection;
import com.harness.tool.discovery.OpenApiSpecParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HttpApiToolQueryTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final LinkedBlockingQueue<ReceivedRequest> requests = new LinkedBlockingQueue<>();
    private HttpServer server;
    private String baseUrl;
    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                requests.add(new ReceivedRequest(exchange.getRequestMethod(), exchange.getRequestURI(),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                if ("HEAD".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        HttpApiTool.clearCurrentCredentials();
        if (server != null) server.stop(0);
    }

    @Test
    void getSendsEncodedPaginationFiltersAndRepeatedScalarArrays() throws Exception {
        tool("GET", "/records?fixed=on", null).execute(mapper.readTree("""
                {"cursor":"页 &next=1","limit":2,"filter":"小 明","enabled":true,
                 "tags":["first","中 文",4,false,null],"optional":null}
                """));

        ReceivedRequest request = receive();
        assertEquals("GET", request.method());
        assertEquals("", request.body());
        assertEquals(Map.of("fixed", List.of("on"), "cursor", List.of("页 &next=1"),
                "limit", List.of("2"), "filter", List.of("小 明"), "enabled", List.of("true"),
                "tags", List.of("first", "中 文", "4", "false")), query(request.uri()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "DELETE"})
    void bodylessMethodsSendQueryParameters(String method) throws Exception {
        tool(method, "/records", null).execute(mapper.readTree("{\"cursor\":\"next\",\"limit\":1}"));

        ReceivedRequest request = receive();
        assertEquals(method, request.method());
        assertEquals(Map.of("cursor", List.of("next"), "limit", List.of("1")), query(request.uri()));
        assertEquals("", request.body());
    }

    @Test
    void excludesPathHeaderCookieAndBodyMetadataFromQuery() throws Exception {
        JsonNode parameters = mapper.readTree("""
                {"type":"object","properties":{
                  "id":{"type":"string","in":"path"},
                  "cursor":{"type":"string","in":"query"},
                  "X-Request-Id":{"type":"string","in":"header"},
                  "session":{"type":"string","in":"cookie"},
                  "__body__":{"type":"object"}}}
                """
        );
        tool("GET", "/records/{id}", parameters).execute(mapper.readTree("""
                {"id":"42","cursor":"next","X-Request-Id":"request","session":"session",
                 "__body__":{"ignored":true}}
                """));

        ReceivedRequest request = receive();
        assertEquals("/records/42", request.uri().getRawPath());
        assertEquals(Map.of("cursor", List.of("next")), query(request.uri()));
    }

    @Test
    void encodesPathValuesWithoutAllowingNewPathOrQuery() throws Exception {
        tool("GET", "/records/{id}/suffix", null).execute(mapper.readTree("""
                {"id":"目录/part?admin=true#片 %2F","cursor":"safe"}
                """));

        ReceivedRequest request = receive();
        assertEquals("/records/%E7%9B%AE%E5%BD%95%2Fpart%3Fadmin=true%23%E7%89%87%20%252F/suffix",
                request.uri().getRawPath());
        assertEquals(Map.of("cursor", List.of("safe")), query(request.uri()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"id\":null}", "{\"id\":{\"value\":1}}", "{\"id\":[1,2]}"})
    void rejectsMissingOrNonScalarPathArgumentsBeforeSendingRequest(String json) throws Exception {
        JsonNode arguments = mapper.readTree(json);

        ToolExecutionException error = assertThrows(ToolExecutionException.class,
                () -> tool("GET", "/records/{id}", null).execute(arguments));

        assertTrue(error.getMessage().contains("id"));
        assertTrue(requests.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"filter\":{\"status\":\"active\"}}", "{\"filter\":[{\"status\":\"active\"}]}"})
    void rejectsObjectQueryValuesRatherThanSilentlyDroppingThem(String json) throws Exception {
        JsonNode arguments = mapper.readTree(json);

        ToolExecutionException error = assertThrows(ToolExecutionException.class,
                () -> tool("GET", "/records", null).execute(arguments));

        assertTrue(error.getMessage().contains("filter"));
        assertTrue(requests.isEmpty());
    }

    @Test
    void trustedQueryCredentialReplacesConfiguredAndModelValuesWithEncoding() throws Exception {
        HttpApiTool.setCurrentCredentials(Map.of("localService", "trusted &role=admin 中"));
        ApiEndpoint endpoint = new ApiEndpoint("queryAuth", "queryAuth", "Query auth", "GET",
                "/records?fixed=on&accessToken=configured", baseUrl, "openapi",
                AuthMode.USER_PASSTHROUGH, "localService", new TokenInjection("query", "accessToken", "prefix "),
                null, true, false);

        new HttpApiTool(endpoint).execute(mapper.readTree("{\"accessToken\":\"model\",\"limit\":2}"));

        assertEquals(Map.of("fixed", List.of("on"), "limit", List.of("2"),
                "accessToken", List.of("prefix trusted &role=admin 中")), query(receive().uri()));
    }

    @Test
    void userPassthroughStillRequiresTrustedCredentials() throws Exception {
        ApiEndpoint endpoint = new ApiEndpoint("queryAuth", "queryAuth", "Query auth", "GET",
                "/records", baseUrl, "openapi", AuthMode.USER_PASSTHROUGH, "localService",
                new TokenInjection("query", "accessToken", ""), null, true, false);

        assertThrows(ToolExecutionException.class,
                () -> new HttpApiTool(endpoint).execute(mapper.readTree("{\"accessToken\":\"model\"}")));
        assertTrue(requests.isEmpty());
    }

    @Test
    void postKeepsFlatJsonBodyAndOmitsOnlyPathArguments() throws Exception {
        tool("POST", "/records/{id}?fixed=on", null).execute(mapper.readTree("""
                {"id":"42","cursor":"a & b","payload":{"value":3},"tags":["one","two"]}
                """));

        ReceivedRequest request = receive();
        assertEquals("POST", request.method());
        assertEquals("/records/42", request.uri().getRawPath());
        assertEquals(Map.of("fixed", List.of("on")), query(request.uri()));
        assertEquals(mapper.readTree("""
                {"cursor":"a & b","payload":{"value":3},"tags":["one","two"]}
                """), mapper.readTree(request.body()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH"})
    void parsedOpenApiSendsReferencedJsonBodyWithoutWrapper(String method) throws Exception {
        ProjectApiConfig config = parsedOpenApi(method);
        ApiEndpoint endpoint = config.endpoints().stream()
                .filter(item -> item.name().equals("updateRecord")).findFirst().orElseThrow();

        new HttpApiTool(endpoint).execute(mapper.readTree("""
                {"id":"record/42","__body__":{"id":"bodyId","status":"done","expectedVersion":2}}
                """));

        ReceivedRequest request = receive();
        assertEquals(method, request.method());
        assertEquals("/records/record%2F42", request.uri().getRawPath());
        assertEquals(mapper.readTree("{\"id\":\"bodyId\",\"status\":\"done\",\"expectedVersion\":2}"),
                mapper.readTree(request.body()));
    }

    @Test
    void parsedOpenApiGetStillSendsPaginationQuery() throws Exception {
        ApiEndpoint endpoint = parsedOpenApi("PUT").endpoints().stream()
                .filter(item -> item.name().equals("listRecords")).findFirst().orElseThrow();

        new HttpApiTool(endpoint).execute(mapper.readTree("{\"cursor\":\"页 &next=1\",\"limit\":2}"));

        assertEquals(Map.of("cursor", List.of("页 &next=1"), "limit", List.of("2")), query(receive().uri()));
    }

    private ProjectApiConfig parsedOpenApi(String method) throws Exception {
        Path spec = tempDir.resolve("openapi.json");
        Files.writeString(spec, """
                {"openapi":"3.0.3","servers":[{"url":"%s"}],"paths":{
                  "/records":{"get":{"operationId":"listRecords","parameters":[
                    {"name":"cursor","in":"query","schema":{"type":"string"}},
                    {"name":"limit","in":"query","schema":{"type":"integer"}}]}},
                  "/records/{id}":{"%s":{"operationId":"updateRecord","parameters":[
                    {"name":"id","in":"path","required":true,"schema":{"type":"string"}}],
                    "requestBody":{"required":true,"content":{"application/json":{
                      "schema":{"$ref":"#/components/schemas/RecordUpdate"}}}}}}},
                  "components":{"schemas":{"RecordUpdate":{"type":"object","properties":{
                    "id":{"type":"string"},"status":{"type":"string"},"expectedVersion":{"type":"integer"}},
                    "required":["status","expectedVersion"]}}}}
                """.formatted(baseUrl, method.toLowerCase(java.util.Locale.ROOT)));
        return new OpenApiSpecParser().parse(spec, tempDir);
    }

    private HttpApiTool tool(String method, String path, JsonNode parameters) {
        return new HttpApiTool(new ApiEndpoint("local", "local", "Local API", method, path, baseUrl,
                "openapi", AuthMode.BOT, null, null, parameters, true, true));
    }

    private ReceivedRequest receive() throws InterruptedException {
        ReceivedRequest request = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull(request, "The endpoint did not receive a request");
        return request;
    }

    private Map<String, List<String>> query(URI uri) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (uri.getRawQuery() == null) return result;
        for (String parameter : uri.getRawQuery().split("&")) {
            String[] pair = parameter.split("=", 2);
            String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8);
            result.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        return result;
    }

    private record ReceivedRequest(String method, URI uri, String body) {}
}
