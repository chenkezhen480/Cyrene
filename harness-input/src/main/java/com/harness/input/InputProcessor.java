package com.harness.input;

import com.harness.core.model.AgentMessage;
import com.harness.core.model.ParsedContent;
import com.harness.input.auth.Authenticator;
import com.harness.input.multimodal.MultimodalParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * Layer 1: Input Processing.
 * Validates auth, parses multimodal content, produces a unified AgentMessage.
 */
public class InputProcessor implements InputStage {

    private static final Logger log = LoggerFactory.getLogger(InputProcessor.class);
    private final Authenticator authenticator;
    private final MultimodalParser multimodalParser;

    public InputProcessor(Authenticator authenticator, MultimodalParser multimodalParser) {
        this.authenticator = java.util.Objects.requireNonNull(authenticator, "authenticator");
        this.multimodalParser = java.util.Objects.requireNonNull(multimodalParser, "multimodalParser");
    }

    /**
     * Process raw input into a validated, parsed AgentMessage.
     *
     * @param token       auth token (nullable if auth mode is none)
     * @param text        input text (required)
     * @param attachments raw attachments (optional)
     * @return parsed message + userId
     */
    public ProcessedInput process(String token, String text, List<MultimodalParser.RawAttachment> attachments) {
        return process(token, text, attachments, null);
    }

    /**
     * Process raw input with optional context userId override.
     *
     * @param token         auth token (nullable if auth mode is none)
     * @param text          input text (required)
     * @param attachments   raw attachments (optional)
     * @param contextUserId userId from request context (used when auth mode is none)
     */
    @Override
    public ProcessedInput process(String token, String text, List<MultimodalParser.RawAttachment> attachments,
                                  String contextUserId) {
        int attachCount = attachments != null ? attachments.size() : 0;
        log.debug("[L1-Input] Processing: textLen={}, attachments={}", text != null ? text.length() : 0, attachCount);

        // Step 1: Authenticate
        String userId = authenticator.authenticate(token);
        // When auth is disabled, prefer the userId from request context
        if ("anonymous".equals(userId) && contextUserId != null && !contextUserId.isBlank()) {
            userId = contextUserId;
            log.debug("[L1-Input] Auth=none, using context userId: {}", userId);
        }
        log.debug("[L1-Input] Authenticated: userId={}", userId);

        // Step 2: Validate text
        if (text == null || text.isBlank()) {
            log.warn("[L1-Input] Rejected: empty input text");
            throw new com.harness.core.exception.AgentException("Input text is required");
        }

        // Step 3: Parse multimodal (with large-file content extraction)
        List<MultimodalParser.ParsedAttachment> parsedAttachments =
                attachments != null ? multimodalParser.parseWithContent(attachments) : Collections.emptyList();

        List<AgentMessage.Attachment> agentAttachments = parsedAttachments.stream()
                .map(MultimodalParser.ParsedAttachment::attachment)
                .toList();

        // Keep nulls to preserve 1:1 index mapping with attachments (used by skill detection for URL attachments)
        List<ParsedContent> parsedContents = parsedAttachments.stream()
                .map(MultimodalParser.ParsedAttachment::parsedContent)
                .toList();

        // Step 4: Build unified message
        AgentMessage message = new AgentMessage(AgentMessage.Role.USER, text, agentAttachments, null, null);

        log.debug("[L1-Input] Done: userId={}, attachments={}, parsedContents={}", userId, agentAttachments.size(), parsedContents.size());
        return new ProcessedInput(userId, message, parsedContents);
    }
}
