package com.harness.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Lightweight, heuristic-based auditor for agent final replies.
 * No model calls — runs fast checks on the reply text to flag potential quality issues.
 *
 * Usage:
 *   ReplyAuditor auditor = new ReplyAuditor();
 *   ReplyAuditResult result = auditor.audit("the final reply text");
 *   // result.passed(), result.reason(), result.score()
 */
public class ReplyAuditor {

    private static final Logger log = LoggerFactory.getLogger(ReplyAuditor.class);

    /** Minimum reply length (characters) to not be flagged as too short. */
    private static final int MIN_REPLY_LENGTH = 20;

    /** Pattern matching common error / uncertainty indicators in replies. */
    private static final List<Pattern> ERROR_INDICATORS = List.of(
            Pattern.compile("(?i)\\bi (?:don'?t|do not) know\\b"),
            Pattern.compile("(?i)\\bi can'?t (?:help|assist|do|answer|find|determine)\\b"),
            Pattern.compile("(?i)\\bunable to\\b"),
            Pattern.compile("(?i)\\bsorry.{0,30}(?:can'?t|cannot|unable)\\b"),
            Pattern.compile("(?i)\\berror\\b"),
            Pattern.compile("(?i)\\bfailed\\b"),
            Pattern.compile("(?i)\\bnot (?:found|available|supported|possible)\\b")
    );

    /** Pattern matching leftover tool artifacts: JSON code blocks, raw tool output. */
    private static final List<Pattern> TOOL_ARTIFACTS = List.of(
            Pattern.compile("```json\\s*\\{", Pattern.DOTALL),
            Pattern.compile("```\\s*\\[\\s*\\{", Pattern.DOTALL),
            Pattern.compile("\\{\\s*\"(?:name|tool|function)\"\\s*:", Pattern.DOTALL),
            Pattern.compile("Tool execution (?:result|output):", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\btool_call_id\\b"),
            Pattern.compile("\\bfunction_call\\b")
    );

    /**
     * Audit a final reply for quality issues.
     *
     * @param reply the final reply text from the ReAct loop
     * @return an audit result with passed, reason, and score
     */
    public ReplyAuditResult audit(String reply) {
        try {
            return doAudit(reply);
        } catch (Exception e) {
            log.warn("[ReplyAuditor] Audit failed, defaulting to pass: {}", e.getMessage());
            return new ReplyAuditResult(true, "audit error, defaulting to pass", 70);
        }
    }

    private ReplyAuditResult doAudit(String reply) {
        if (reply == null || reply.isBlank()) {
            return new ReplyAuditResult(false, "Reply is null or empty", 0);
        }

        int score = 100;
        StringBuilder reasons = new StringBuilder();

        // Check 1: Length
        if (reply.length() < MIN_REPLY_LENGTH) {
            score -= 30;
            appendReason(reasons, "Reply is very short (" + reply.length() + " chars)");
        }

        // Check 2: Error indicators
        int errorMatches = 0;
        for (Pattern p : ERROR_INDICATORS) {
            if (p.matcher(reply).find()) {
                errorMatches++;
            }
        }
        if (errorMatches > 0) {
            score -= Math.min(30, errorMatches * 10);
            appendReason(reasons, "Contains " + errorMatches + " error/uncertainty indicator(s)");
        }

        // Check 3: Tool artifacts
        int artifactMatches = 0;
        for (Pattern p : TOOL_ARTIFACTS) {
            if (p.matcher(reply).find()) {
                artifactMatches++;
            }
        }
        if (artifactMatches > 0) {
            score -= Math.min(25, artifactMatches * 10);
            appendReason(reasons, "Contains " + artifactMatches + " tool artifact(s)");
        }

        // Clamp score
        score = Math.max(0, Math.min(100, score));
        boolean passed = score >= 50;
        String reason = reasons.length() > 0 ? reasons.toString() : "All checks passed";

        return new ReplyAuditResult(passed, reason, score);
    }

    private void appendReason(StringBuilder sb, String reason) {
        if (sb.length() > 0) sb.append("; ");
        sb.append(reason);
    }

    /**
     * Result of a reply quality audit.
     */
    public record ReplyAuditResult(
            boolean passed,
            String reason,
            int score
    ) {}
}
