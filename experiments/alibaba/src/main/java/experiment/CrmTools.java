package experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Independent host adapter: no Cyrene classes or schemas. */
public final class CrmTools {
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> events = new CopyOnWriteArrayList<>();

    public CrmTools(String baseUrl) {
        URI uri = URI.create(baseUrl);
        if (!Set.of("127.0.0.1", "localhost").contains(uri.getHost()) || uri.getPort() <= 0)
            throw new IllegalArgumentException("Use an explicit isolated loopback fixture URL");
        this.baseUrl = baseUrl.replaceAll("/$", "");
    }
    public List<JsonNode> events() { return List.copyOf(events); }
    public void clearEvents() { events.clear(); }

    @Tool(description = "List current user's customers with stable cursor pagination. Follow pageInfo.nextCursor until hasMore=false. expiresBefore and inactiveBefore are strict YYYY-MM-DD upper bounds. limit is the page size.")
    public String listCustomers(@ToolParam(required = false) Integer limit, @ToolParam(required = false) String cursor,
            @ToolParam(required = false) String expiresBefore, @ToolParam(required = false) String inactiveBefore, ToolContext context) {
        var query = new LinkedHashMap<String, Object>();
        query.put("limit", limit); query.put("cursor", cursor); query.put("expiresBefore", expiresBefore); query.put("inactiveBefore", inactiveBefore);
        return request("GET", "/customers", query, null, context);
    }
    @Tool(description = "Read one customer using its returned business ID. Authorization is enforced by the business system for the current user.")
    public String getCustomer(String id, ToolContext context) { return request("GET", "/customers/" + encode(id), Map.of(), null, context); }

    @Tool(description = "List current user's tasks with stable cursor pagination. Follow pageInfo.nextCursor until hasMore=false. Optional customerId filters one customer. Results include id, status and version.")
    public String listTasks(@ToolParam(required = false) Integer limit, @ToolParam(required = false) String cursor,
            @ToolParam(required = false) String customerId, ToolContext context) {
        var query = new LinkedHashMap<String, Object>(); query.put("limit", limit); query.put("cursor", cursor); query.put("customerId", customerId);
        return request("GET", "/tasks", query, null, context);
    }
    public record CreateTask(String customerId, String title, String dueDate, String idempotencyKey) {}
    public record UpdateTask(String status, Integer expectedVersion) {}
    @Tool(description = "Create a task for an authorized customer. All request fields are required; dueDate is YYYY-MM-DD. Repeating exactly the same idempotencyKey and body returns the same task, without another insert.")
    public String createTask(CreateTask request, ToolContext context) { return request("POST", "/tasks", Map.of(), request, context); }
    @Tool(description = "Update an authorized task's status (open, inProgress, done) using its current expectedVersion. Read the version first; success increments version. Business system enforces ownership and optimistic concurrency.")
    public String updateTask(String id, UpdateTask request, ToolContext context) { return request("PUT", "/tasks/" + encode(id), Map.of(), request, context); }

    private String request(String method, String path, Map<String, Object> query, Object body, ToolContext context) {
        Object credential = context == null ? null : context.getContext().get("fixtureToken");
        if (!(credential instanceof String token) || token.isBlank()) throw new IllegalArgumentException("Missing trusted fixture identity");
        var parameters = query.entrySet().stream().filter(e -> e.getValue() != null)
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue().toString())).toList();
        String target = path + (parameters.isEmpty() ? "" : "?" + String.join("&", parameters));
        var event = mapper.createObjectNode().put("method", method).put("path", target).put("startedAt", java.time.Instant.now().toString());
        long started = System.nanoTime();
        try {
            String content = body == null ? "" : mapper.writeValueAsString(body);
            event.set("arguments", body == null ? mapper.valueToTree(query) : mapper.readTree(content));
            var request = HttpRequest.newBuilder(URI.create(baseUrl + target)).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(content)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode payload = mapper.readTree(response.body());
            event.put("httpStatus", response.statusCode()).set("response", payload);
            if (response.statusCode() / 100 != 2)
                return mapper.writeValueAsString(mapper.createObjectNode().put("httpStatus", response.statusCode()).set("error", payload));
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Business request interrupted", exception);
        } catch (java.io.IOException exception) {
            event.put("errorType", exception.getClass().getSimpleName());
            throw new IllegalStateException("Business request failed: " + exception.getClass().getSimpleName(), exception);
        } finally {
            event.put("durationMs", (System.nanoTime() - started) / 1_000_000); events.add(event);
        }
    }
    private static String encode(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing nonblank business parameter");
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
