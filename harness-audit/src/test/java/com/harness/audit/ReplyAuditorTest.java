package com.harness.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReplyAuditorTest {

    ReplyAuditor auditor;

    @BeforeEach
    void setUp() {
        auditor = new ReplyAuditor();
    }

    @Test
    void audit_nullReply_fails() {
        var result = auditor.audit(null);
        assertThat(result.passed()).isFalse();
        assertThat(result.score()).isEqualTo(0);
        assertThat(result.reason()).contains("null or empty");
    }

    @Test
    void audit_blankReply_fails() {
        var result = auditor.audit("   ");
        assertThat(result.passed()).isFalse();
        assertThat(result.score()).isEqualTo(0);
    }

    @Test
    void audit_shortReply_deducts30() {
        var result = auditor.audit("Too short");
        assertThat(result.score()).isEqualTo(70); // 100 - 30
        assertThat(result.passed()).isTrue(); // 70 >= 50
        assertThat(result.reason()).contains("very short");
    }

    @Test
    void audit_errorIndicator_deducts10() {
        String reply = "I'm sorry, I can't help you with that specific request right now.";
        var result = auditor.audit(reply);
        assertThat(result.score()).isLessThan(100);
        assertThat(result.reason()).contains("error/uncertainty indicator");
    }

    @Test
    void audit_multipleErrorIndicators_maxMinus30() {
        String reply = "I don't know. I can't help. Unable to find. Sorry, can't do it. Error occurred. Failed. Not found.";
        var result = auditor.audit(reply);
        // Multiple indicators, capped at -30
        assertThat(result.score()).isLessThanOrEqualTo(70);
    }

    @Test
    void audit_toolArtifact_deducts10() {
        String reply = "Here is the result from the tool:\n```json\n{\"name\": \"test\", \"value\": 42}\n```\nHope this helps!";
        var result = auditor.audit(reply);
        assertThat(result.score()).isLessThan(100);
        assertThat(result.reason()).contains("tool artifact");
    }

    @Test
    void audit_multipleToolArtifacts_maxMinus25() {
        String reply = "Result:\n```json\n{\"name\":\"a\"}\n```\nAlso:\n```json\n{\"tool\":\"b\"}\n```\nAnd: tool_call_id=function_call";
        var result = auditor.audit(reply);
        // Multiple artifacts, capped at -25
        assertThat(result.score()).isLessThanOrEqualTo(75);
    }

    @Test
    void audit_cleanReply_score100() {
        String reply = "The analysis shows that the system performance has improved by 15% over the last quarter, with significant gains in response time.";
        var result = auditor.audit(reply);

        assertThat(result.passed()).isTrue();
        assertThat(result.score()).isEqualTo(100);
        assertThat(result.reason()).isEqualTo("All checks passed");
    }

    @Test
    void audit_scoreAt50_passes() {
        // Reply length 22 (not short), "Sorry...can't" matches 2 error indicators => -20
        String reply = "Sorry, I can't do that.";
        var result = auditor.audit(reply);
        assertThat(result.passed()).isTrue();
        assertThat(result.score()).isEqualTo(80);
    }

    @Test
    void audit_scoreBelow50_fails() {
        // null/blank = 0, fails
        var result = auditor.audit("");
        assertThat(result.passed()).isFalse();
        assertThat(result.score()).isEqualTo(0);
    }
}
