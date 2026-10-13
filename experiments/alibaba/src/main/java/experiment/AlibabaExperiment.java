package experiment;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Explicit standalone benchmark entry; production Cyrene is not on the classpath. */
public final class AlibabaExperiment {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final List<String> secrets = new ArrayList<>();
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Supply one private experiment settings path");
        new AlibabaExperiment().run(Path.of(arguments[0]).toRealPath());
    }
    private void run(Path settingsPath) throws Exception {
        JsonNode config = mapper.readTree(settingsPath.toFile());
        Path output = Path.of(required(config, "outputDirectory")); Files.createDirectories(output);
        Path raw = output.resolve("raw.jsonl");
        if (Files.exists(raw)) throw new IllegalStateException("Use a new result directory; raw.jsonl already exists");
        Path modelPath = Path.of(required(config, "modelConfigPath")).toRealPath();
        if (!modelPath.startsWith(settingsPath.getParent())) throw new IllegalArgumentException("Model must be a private copy beside settings");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(modelPath));
        Map<String, String> settings = readSettings(modelPath);
        secrets.add(settings.get("chat.apiKey")); secrets.add(settings.get("chat.baseUrl"));
        var tokens = mapper.readTree(Path.of(required(config, "fixtureTokensPath")).toFile());
        for (String owner : List.of("ownerAlpha", "ownerBeta", "admin")) secrets.add(required(tokens, owner));
        String fixture = required(config, "fixtureUrl");
        var tools = new CrmTools(fixture);
        var health = request(fixture + "/health", required(tokens, "admin"), "GET");
        if (!"cyrene-synthetic-crm-v1".equals(health.path("fixture").asText())) throw new IllegalStateException("Unexpected fixture marker");
        var taskFile = mapper.readTree(Path.of(required(config, "taskFilePath")).toFile());
        if (!"2026-10-12".equals(taskFile.path("benchmarkDate").asText())) throw new IllegalArgumentException("Unexpected benchmark date");
        long setupStarted = System.nanoTime();
        var model = new MeasuredModel(AlibabaRuntime.model(settings));
        var runtime = new AlibabaRuntime(model, tools, required(config, "systemPrompt"), config.path("maxModelCalls").asInt(10));
        var setup = mapper.createObjectNode().put("framework", "Spring AI Alibaba ReactAgent 1.1.2.0 / Spring AI 1.1.2")
                .put("setupDurationMs", (System.nanoTime() - setupStarted) / 1_000_000)
                .put("modelConfigSha256", HexFormat.of().formatHex(digest))
                .put("chatModel", settings.get("chat.model")).put("temperature", settings.get("chat.temperature"))
                .put("maxTokens", settings.get("chat.maxTokens")).put("thinkingLevel", "off")
                .put("credentialBoundary", "RunnableConfig trusted metadata -> native ToolContext -> business Authorization header; absent from model definitions")
                .put("apiIntegration", "Five independent annotated host methods -> Spring AI ToolCallbacks; no Cyrene tool/parser/runtime dependency")
                .put("timingLimit", "Automated JVM initialization, not human integration time; excluded from per-task duration")
                .put("jevBenchmark", false);
        setup.set("tools", mapper.valueToTree(Arrays.stream(org.springframework.ai.support.ToolCallbacks.from(tools)).map(t -> t.getToolDefinition()).toList()));
        write(output.resolve("setup.json"), setup);
        for (int repetition = 1; repetition <= config.path("repetitions").asInt(1); repetition++) {
            for (JsonNode task : taskFile.path("tasks")) {
                String category = required(task, "category");
                if (Set.of("knowledge", "mixed").contains(category)) throw new IllegalArgumentException("Business-only comparison cannot include knowledge tasks");
                String owner = required(task, "principal");
                if (!Set.of("ownerAlpha", "ownerBeta").contains(owner)) throw new IllegalArgumentException("Unexpected task identity");
                request(fixture + "/_experiment/reset", required(tokens, "admin"), "POST");
                var before = request(fixture + "/_experiment/state", required(tokens, "admin"), "GET");
                model.clear(); tools.clearEvents();
                var row = mapper.createObjectNode().put("schemaVersion", 1).put("condition", "alibaba_react")
                        .put("repetition", repetition).put("taskId", required(task, "id")).put("category", category)
                        .put("prompt", required(task, "prompt")).put("principal", owner).put("sessionId", UUID.randomUUID().toString())
                        .put("startedAt", Instant.now().toString());
                String answer = "";
                List<String> diagnostics = new ArrayList<>();
                long started = System.nanoTime();
                try {
                    answer = runtime.call(required(task, "prompt"), required(tokens, owner), row.path("sessionId").asText());
                    if (model.calls().stream().anyMatch(c -> c.has("errorType"))) throw new IllegalStateException("A model call failed; inspect modelCalls");
                    row.put("status", "completed");
                } catch (Exception exception) {
                    row.put("status", "error").put("errorType", exception.getClass().getName()).put("error", redact(String.valueOf(exception.getMessage())));
                    diagnostics.add("runtime error: " + exception.getClass().getSimpleName());
                }
                row.put("durationMs", (System.nanoTime() - started) / 1_000_000).put("answer", answer);
                var after = request(fixture + "/_experiment/state", required(tokens, "admin"), "GET");
                diagnostics.addAll(ExperimentEvaluation.validate(task.path("expectations"), answer, before, after));
                row.put("passed", diagnostics.isEmpty()).set("validationDiagnostics", mapper.valueToTree(diagnostics));
                row.set("expectations", task.path("expectations")); row.set("initialFixtureState", before); row.set("finalFixtureState", after);
                row.set("trace", mapper.createObjectNode().set("metadata", model.metadata()));
                row.set("modelCalls", mapper.valueToTree(model.calls())); row.set("httpEvents", mapper.valueToTree(tools.events()));
                Files.writeString(raw, redact(mapper.writeValueAsString(row)) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                System.out.println("Experiment alibaba_react " + required(task, "id") + " passed=" + row.path("passed").asBoolean() + " durationMs=" + row.path("durationMs").asLong());
                if (model.calls().isEmpty() || model.calls().stream().anyMatch(c -> c.has("errorType"))) throw new IllegalStateException("Stopped after recorded structural/model failure");
            }
        }
        if (!MessageDigest.isEqual(digest, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(modelPath)))) throw new IllegalStateException("Private model settings changed");
    }
    private JsonNode request(String url, String token, String method) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").method(method, "POST".equals(method) ? HttpRequest.BodyPublishers.ofString("{}") : HttpRequest.BodyPublishers.noBody()).build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() / 100 != 2) throw new IllegalStateException("Fixture control HTTP " + res.statusCode());
        return mapper.readTree(res.body());
    }
    private Map<String, String> readSettings(Path path) throws Exception {
        var result = new LinkedHashMap<String, String>();
        for (String line : Files.readAllLines(path)) {
            line = line.strip(); if (line.isEmpty() || line.startsWith("#")) continue;
            int split = line.indexOf('='); if (split < 1) throw new IllegalArgumentException("Invalid model assignment");
            String value = line.substring(split + 1).strip();
            if (value.startsWith("\"")) value = mapper.readValue(value, String.class);
            if (result.put(line.substring(0, split).strip(), value) != null) throw new IllegalArgumentException("Duplicate model key");
        }
        return result;
    }
    private void write(Path path, JsonNode value) throws Exception { Files.writeString(path, redact(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)), StandardCharsets.UTF_8); }
    private String redact(String value) { for (String secret : secrets) if (secret != null && !secret.isBlank()) value = value.replace(secret, "[REDACTED]"); return value; }
    private static String required(JsonNode node, String field) {
        if (!node.path(field).isTextual() || node.path(field).asText().isBlank()) throw new IllegalArgumentException("Missing string: " + field);
        return node.path(field).asText();
    }
}
