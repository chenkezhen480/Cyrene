package experiment;

import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

final class ExperimentEvaluation {
    static List<String> validate(JsonNode expectations, String answer, JsonNode before, JsonNode after) {
        List<String> diagnostics = new ArrayList<>();
        String normalizedAnswer = Normalizer.normalize(answer, Normalizer.Form.NFKC);
        for (JsonNode text : expectations.path("answerContainsAll")) {
            if (!containsPhrase(normalizedAnswer, text.asText())) diagnostics.add("missing answer text: " + text.asText());
        }
        JsonNode alternatives = expectations.path("answerContainsAny");
        if (alternatives.size() > 0 && StreamSupport.stream(alternatives.spliterator(), false)
                .noneMatch(text -> containsPhrase(normalizedAnswer, text.asText()))) {
            diagnostics.add("answer contains none of: " + alternatives);
        }
        for (JsonNode text : expectations.path("answerNotContains")) {
            if (containsPhrase(normalizedAnswer, text.asText())) diagnostics.add("forbidden answer text: " + text.asText());
        }
        JsonNode state = expectations.path("state");
        if (!before.path("customers").isArray() || !before.path("tasks").isArray()
                || !after.path("customers").isArray() || !after.path("tasks").isArray()) {
            throw new IllegalArgumentException("Fixture state must expose customers and tasks arrays");
        }
        if (state.path("unchanged").asBoolean() && !before.equals(after)) diagnostics.add("fixture state changed");
        if (state.path("customersUnchanged").asBoolean()
                && !before.get("customers").equals(after.get("customers"))) diagnostics.add("customers changed");
        if (state.has("tasksAdded")) {
            int added = after.get("tasks").size() - before.get("tasks").size();
            if (added != state.get("tasksAdded").asInt()) diagnostics.add("tasksAdded: expected "
                    + state.get("tasksAdded").asInt() + ", actual " + added);
        }
        for (JsonNode partial : state.path("tasksInclude")) {
            if (!containsPartial(after.get("tasks"), partial)) diagnostics.add("missing task: " + partial);
        }
        for (JsonNode partial : state.path("tasksAbsent")) {
            if (containsPartial(after.get("tasks"), partial)) diagnostics.add("forbidden task: " + partial);
        }
        for (JsonNode id : state.path("unchangedTaskIds")) {
            JsonNode initial = StreamSupport.stream(before.get("tasks").spliterator(), false)
                    .filter(task -> id.equals(task.path("id"))).findFirst().orElseThrow(
                            () -> new IllegalArgumentException("unchangedTaskIds not in initial fixture: " + id.asText()));
            JsonNode current = StreamSupport.stream(after.get("tasks").spliterator(), false)
                    .filter(task -> id.equals(task.path("id"))).findFirst().orElse(null);
            if (!initial.equals(current)) diagnostics.add("task changed: " + id.asText());
        }
        return List.copyOf(diagnostics);
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("(?U)\\s+", "");
    }

    private static boolean containsPhrase(String answer, String expected) {
        String phrase = normalize(expected);
        var tokens = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]*").matcher(phrase);
        var regex = new StringBuilder("(?U)");
        int position = 0;
        while (tokens.find()) {
            regex.append(flexibleLiteral(phrase.substring(position, tokens.start()))).append("\\s*")
                    .append("(?<![A-Za-z\\p{N}_.+-])").append(flexibleLiteral(tokens.group()))
                    .append("(?![A-Za-z\\p{N}_+-]|\\.\\p{N})\\s*");
            position = tokens.end();
        }
        regex.append(flexibleLiteral(phrase.substring(position)));
        return Pattern.compile(regex.toString()).matcher(answer).find();
    }

    private static String flexibleLiteral(String text) {
        return text.codePoints().mapToObj(point -> Pattern.quote(new String(Character.toChars(point))))
                .collect(Collectors.joining("\\s*"));
    }

    private static boolean containsPartial(JsonNode rows, JsonNode partial) {
        return StreamSupport.stream(rows.spliterator(), false).anyMatch(row -> {
            var fields = partial.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!field.getValue().equals(row.path(field.getKey()))) return false;
            }
            return true;
        });
    }
}
