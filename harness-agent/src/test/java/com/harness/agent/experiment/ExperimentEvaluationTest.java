package com.harness.agent.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExperimentEvaluationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void countsWrongCustomerMutationEvenWhenAnswerClaimsSuccess() throws Exception {
        var expectation = mapper.readTree("""
                {"answerContainsAll":["完成"],"state":{"tasksAdded":1,
                "customersUnchanged":true,"tasksInclude":[{"customerId":"c1","title":"续费"}]}}
                """);
        var before = mapper.readTree("""
                {"customers":[{"id":"c1","ownerId":"alpha"}],"tasks":[]}
                """);
        var after = mapper.readTree("""
                {"customers":[{"id":"c1","ownerId":"beta"}],
                "tasks":[{"customerId":"c2","title":"续费"}]}
                """);
        assertThat(ExperimentEvaluation.validate(expectation, "完成", before, after))
                .contains("customers changed", "missing task: {\"customerId\":\"c1\",\"title\":\"续费\"}");
    }

    @Test
    void unchangedIncludesTaskUpdatesAndPartialAbsenceChecks() throws Exception {
        var expectation = mapper.readTree("""
                {"state":{"unchanged":true,"tasksAbsent":[{"customerId":"private"}]}}
                """);
        var before = mapper.readTree("""
                {"customers":[],"tasks":[{"id":"t1","customerId":"private","status":"pending"}]}
                """);
        var after = mapper.readTree("""
                {"customers":[],"tasks":[{"id":"t1","customerId":"private","status":"done"}]}
                """);
        assertThat(ExperimentEvaluation.validate(expectation, "", before, after))
                .contains("fixture state changed", "forbidden task: {\"customerId\":\"private\"}");
    }

    @Test
    void acceptsMatchingStateAndAlternativeAnswerButRejectsForbiddenAnswer() throws Exception {
        var expectation = mapper.readTree("""
                {"answerContainsAll":["c1"],"answerContainsAny":["完成","成功"],
                "answerNotContains":["秘密"],"state":{"tasksAdded":1,
                "tasksInclude":[{"customerId":"c1"}],"customersUnchanged":true}}
                """);
        var before = mapper.readTree("{\"customers\":[],\"tasks\":[]}");
        var after = mapper.readTree("{\"customers\":[],\"tasks\":[{\"customerId\":\"c1\"}]}");
        assertThat(ExperimentEvaluation.validate(expectation, "c1 成功", before, after)).isEmpty();
        assertThat(ExperimentEvaluation.validate(expectation, "c1 成功 秘密", before, after))
                .containsExactly("forbidden answer text: 秘密");
    }

    @Test
    void rejectsCollateralTaskUpdateWhenRequestedNewTaskIsCorrect() throws Exception {
        var expectations = mapper.readTree("{\"state\":{\"tasksAdded\":1,\"unchangedTaskIds\":[\"T1\"]}}");
        var before = mapper.readTree("{\"customers\":[],\"tasks\":[{\"id\":\"T1\",\"status\":\"open\"}]}");
        var after = mapper.readTree("{\"customers\":[],\"tasks\":[{\"id\":\"T1\",\"status\":\"done\"},{\"id\":\"T2\"}]}");
        assertThat(ExperimentEvaluation.validate(expectations, "", before, after)).containsExactly("task changed: T1");
    }

    @Test
    void acceptsUnicodeFormattingWithoutAcceptingWrongFacts() throws Exception {
        var expectations = mapper.readTree("{\"answerContainsAll\":[\"POL-R17\",\"17 个自然日\"]}");
        var state = mapper.readTree("{\"customers\":[],\"tasks\":[]}");
        assertThat(ExperimentEvaluation.validate(expectations, "ＰＯＬ－Ｒ１７：１７个自然日", state, state)).isEmpty();
        assertThat(ExperimentEvaluation.validate(expectations, "POL-R18：18 个自然日", state, state))
                .containsExactly("missing answer text: POL-R17", "missing answer text: 17 个自然日");
    }

    @Test
    void rejectsLongerNumbersAndIdsThatContainTheExpectedSubstring() throws Exception {
        var expectations = mapper.readTree("{\"answerContainsAll\":[\"17个自然日\",\"13%\",\"POL-R17\",\"C101\"]}");
        var state = mapper.readTree("{\"customers\":[],\"tasks\":[]}");
        assertThat(ExperimentEvaluation.validate(expectations, "117个自然日；113%；POL-R170；C1010", state, state))
                .containsExactly("missing answer text: 17个自然日", "missing answer text: 13%",
                        "missing answer text: POL-R17", "missing answer text: C101");
        assertThat(ExperimentEvaluation.validate(expectations, "17 个自然日；13%；POL-R17；C101", state, state)).isEmpty();
    }

    @Test
    void numberInAnIdentifierDoesNotSatisfyAStandaloneNumericFact() throws Exception {
        var expectations = mapper.readTree("{\"answerContainsAll\":[\"43\",\"SUPPORT-MICA-43\"]}");
        var state = mapper.readTree("{\"customers\":[],\"tasks\":[]}");
        assertThat(ExperimentEvaluation.validate(expectations, "143分钟；SUPPORT-MICA-43", state, state))
                .containsExactly("missing answer text: 43");
    }

    @Test
    void sentencePeriodsDoNotExtendIdentifiersButDecimalDigitsDo() throws Exception {
        var expectations = mapper.readTree("{\"answerContainsAll\":[\"T0004\",\"POL-R17\",\"2026-11-15\"]}");
        var state = mapper.readTree("{\"customers\":[],\"tasks\":[]}");
        assertThat(ExperimentEvaluation.validate(expectations, "T0004. POL-R17. 2026-11-15.", state, state)).isEmpty();
        assertThat(ExperimentEvaluation.validate(expectations, "T0004.5; POL-R17.5; 2026-11-15.5", state, state)).hasSize(3);
    }
}
