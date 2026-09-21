package com.harness.provider.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.model.ThinkingLevel;
import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigKey;
import com.harness.provider.RoutingModelProvider;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** TypeSafe System One: three independent decisions in one HTTP request. */
public final class JevRoutingModelProvider implements RoutingModelProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final OkHttpClient http;
    private final String endpoint;
    private final String apiKey;
    private final String model;

    public JevRoutingModelProvider(ModelConfig config) {
        apiKey = config.requireString(ModelConfigKey.ROUTING_API_KEY);
        model = config.getString(ModelConfigKey.ROUTING_MODEL, "jev-latest");
        endpoint = config.getString(ModelConfigKey.ROUTING_BASE_URL, "https://api.typesafe.ai/v1")
                .replaceAll("/+$", "") + "/systemone";
        int timeout = config.getInt(ModelConfigKey.ROUTING_TIMEOUT_SECONDS, 30);
        if (timeout <= 0) throw new IllegalArgumentException("routing.timeoutSeconds must be positive");
        HttpUrl url = HttpUrl.get(endpoint);
        if (!url.username().isEmpty() || !url.password().isEmpty()) {
            throw new IllegalArgumentException("Routing URL must not contain credentials");
        }
        http = new OkHttpClient.Builder().callTimeout(timeout, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).build();
    }

    @Override
    public Decision route(String query) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("Routing query is required");
        ObjectNode body = MAPPER.createObjectNode().put("model", model).put("state", query);
        ObjectNode questions = body.putObject("questions");
        ObjectNode thinking = questions.putObject("thinkingLevel").put("type", "choice")
                .put("instructions", "Choose the minimum reasoning effort needed to answer this user request correctly.");
        thinking.putObject("criteria")
                .put("off", "Greeting, direct translation, copying, or a trivial factual answer")
                .put("low", "Simple explanation or straightforward single-step task")
                .put("medium", "Several reasoning steps, ordinary coding or comparison")
                .put("high", "Complex debugging, architecture, difficult analysis or planning")
                .put("xhigh", "Exceptionally difficult multi-stage reasoning, proof or research synthesis");
        addBooleanQuestion(questions, "needsKnowledgeBase",
                "Does answering require the agent's internal LLM Wiki: stored documents, user episodes, operation playbooks or business graph knowledge? General knowledge alone does not require the Wiki.");
        addBooleanQuestion(questions, "needsWebSearch",
                "Does answering require searching the public web for current information, external evidence or discovering sources? Reading a URL already supplied by the user alone does not require web search.");
        Request request = new Request.Builder().url(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body.toString(), MediaType.get("application/json"))).build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("JEV routing returned HTTP " + response.code());
            }
            if (response.body() == null) throw new IllegalStateException("JEV routing returned no body");
            JsonNode root = MAPPER.readTree(response.body().string());
            if (root == null || !root.path("answers").isObject()) {
                throw new IllegalStateException("Invalid JEV routing answer map");
            }
            JsonNode answers = root.get("answers");
            return new Decision(ThinkingLevel.parse(choice(answers, "thinkingLevel")),
                    booleanChoice(answers, "needsKnowledgeBase"), booleanChoice(answers, "needsWebSearch"));
        } catch (IOException e) {
            throw new IllegalStateException("JEV routing request or response failed", e);
        }
    }

    private static void addBooleanQuestion(ObjectNode questions, String id, String instructions) {
        questions.putObject(id).put("type", "choice").put("instructions", instructions)
                .putObject("criteria").put("true", "Required").put("false", "Not required");
    }

    private static String choice(JsonNode answers, String id) {
        JsonNode answer = answers.path(id);
        if (!"choice".equals(answer.path("type").asText()) || !answer.path("choice").isTextual()) {
            throw new IllegalStateException("Invalid JEV routing answer: " + id);
        }
        return answer.get("choice").textValue();
    }

    private static boolean booleanChoice(JsonNode answers, String id) {
        return switch (choice(answers, id)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalStateException("Invalid JEV boolean choice: " + id);
        };
    }
}
