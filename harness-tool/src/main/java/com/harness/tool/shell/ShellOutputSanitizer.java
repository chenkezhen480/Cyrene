package com.harness.tool.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps plaintext credentials out of everything downstream: ToolOutput, trace, conversation history
 * and the model's context. Once a secret reaches the model it has reached all four.
 *
 * <p>{@code docker inspect} is the reason this exists. It prints the container's environment
 * verbatim, which is where compose puts {@code MYSQL_ROOT_PASSWORD}. The container's own config is
 * handled structurally — the JSON is parsed and {@code Config.Env} redacted field by field — and
 * everything else gets a pattern pass.
 *
 * <p>Best effort, and honest about it: a secret stored under an innocuous name, or embedded in a
 * value that does not look like one, will get through. This raises the cost of an accidental leak;
 * it is not a data-loss-prevention product.
 */
public final class ShellOutputSanitizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String MASK = "***";

    /**
     * Matched against <em>key</em> names, not against values, so prose mentioning "token" is left
     * alone. Bare {@code pass} is deliberately absent — it matches compass, bypass and so on.
     */
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|passwd|pwd|pass_|_pass|secret|token|api[_-]?key|access[_-]?key"
                    + "|private[_-]?key|credential|auth[_-]?key).*");

    /** {@code KEY=value} and {@code KEY: value} in free text, e.g. a stack trace or a shell script. */
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?im)([A-Za-z0-9_.\\-]*(?:password|passwd|pwd|secret|token|api[_-]?key"
                    + "|access[_-]?key|private[_-]?key|credential)[A-Za-z0-9_.\\-]*)(\\s*[=:]\\s*)(\\S+)");

    /** Credentials embedded in a URL: {@code mysql://user:pw@host/db}. */
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(//[^/@\\s:]+):[^/@\\s]+@");

    private ShellOutputSanitizer() {
    }

    /**
     * @param command the executable that was run
     * @param args    its arguments, used to recognise {@code docker inspect}
     * @param output  combined stdout/stderr
     * @return output safe to hand to the model
     */
    public static String sanitize(String command, List<String> args, String output) {
        String sanitized = output == null ? "" : output;
        if (isDockerInspect(command, args)) {
            sanitized = sanitizeInspectJson(sanitized);
        }
        sanitized = maskUrlCredentials(sanitized);
        return maskKeyValues(sanitized);
    }

    private static boolean isDockerInspect(String command, List<String> args) {
        return "docker".equalsIgnoreCase(command) && !args.isEmpty()
                && "inspect".equalsIgnoreCase(args.get(0));
    }

    /**
     * Parses the inspect payload and redacts it field by field, so the useful parts — port
     * mappings, health checks, mounts, state — survive intact. Re-serialised rather than returned
     * verbatim: the output is now something we produced, not something docker produced.
     */
    private static String sanitizeInspectJson(String output) {
        String trimmed = output.trim();
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) {
            return output;
        }
        try {
            JsonNode root = MAPPER.readTree(trimmed);
            redactNode(root);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            // Not the JSON we expected; the pattern pass below is still applied by the caller.
            return output;
        }
    }

    private static void redactNode(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                if (SENSITIVE_KEY.matcher(field.getKey()).matches() && value.isTextual()) {
                    field.setValue(new TextNode(MASK));
                } else if ("env".equalsIgnoreCase(field.getKey()) && value.isArray()) {
                    redactEnv((ArrayNode) value);
                } else {
                    redactNode(value);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (JsonNode child : array) {
                redactNode(child);
            }
        }
    }

    /** {@code Config.Env} holds {@code KEY=VALUE} strings; only the sensitive ones are blanked. */
    private static void redactEnv(ArrayNode env) {
        for (int i = 0; i < env.size(); i++) {
            JsonNode entry = env.get(i);
            if (!entry.isTextual()) {
                continue;
            }
            String text = entry.asText();
            int separator = text.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = text.substring(0, separator);
            if (SENSITIVE_KEY.matcher(key).matches()) {
                env.set(i, new TextNode(key + "=" + MASK));
            }
        }
    }

    private static String maskKeyValues(String text) {
        // $1 and $2 are group references and must survive; MASK is literal asterisks, so it needs
        // no escaping either. Quoting the whole replacement would emit a literal "$1$2".
        return KEY_VALUE.matcher(text).replaceAll("$1$2" + MASK);
    }

    private static String maskUrlCredentials(String text) {
        return URL_CREDENTIALS.matcher(text).replaceAll("$1:" + MASK + "@");
    }

    /** Exposed for tests that want to assert a key is treated as sensitive without running a process. */
    static boolean isSensitiveKey(String key) {
        return SENSITIVE_KEY.matcher(key.toLowerCase(Locale.ROOT)).matches();
    }
}
