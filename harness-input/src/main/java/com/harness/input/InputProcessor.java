package com.harness.input;

import com.harness.ai.model.ChatModelProvider;
import com.harness.ai.model.VisionModelProvider;
import com.harness.ai.model.VoiceModelProvider;
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
public class InputProcessor {

    private static final Logger log = LoggerFactory.getLogger(InputProcessor.class);
    private final Authenticator authenticator;
    private final MultimodalParser multimodalParser;

    public InputProcessor() {
        this.authenticator = new Authenticator();
        this.multimodalParser = new MultimodalParser();
    }

    public InputProcessor(ChatModelProvider chatProvider, VisionModelProvider visionProvider, VoiceModelProvider voiceProvider) {
        this.authenticator = new Authenticator();
        this.multimodalParser = new MultimodalParser(chatProvider, visionProvider, voiceProvider);
    }

    /**
     * Process raw input into a validated, parsed AgentMessage.
     *
     * @param token       auth token (nullable if auth mode is none)
     * @param text        input text (required)
     * @param attachments raw attachments (optional)
     * @return parsed message + userId
     */
    public InputResult process(String token, String text, List<MultimodalParser.RawAttachment> attachments) {
        int attachCount = attachments != null ? attachments.size() : 0;
        log.info("[L1-Input] Processing: textLen={}, attachments={}", text != null ? text.length() : 0, attachCount);

        // Step 1: Authenticate
        String userId = authenticator.authenticate(token);
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

        List<ParsedContent> parsedContents = parsedAttachments.stream()
                .map(MultimodalParser.ParsedAttachment::parsedContent)
                .filter(pc -> pc != null)
                .toList();

        // Step 4: Build unified message
        AgentMessage message = new AgentMessage(AgentMessage.Role.USER, text, agentAttachments, null, null);

        log.info("[L1-Input] Done: userId={}, attachments={}, parsedContents={}", userId, agentAttachments.size(), parsedContents.size());
        return new InputResult(userId, message, parsedContents);
    }

    public record InputResult(String userId, AgentMessage message, List<ParsedContent> parsedContents) {
        public InputResult(String userId, AgentMessage message) {
            this(userId, message, Collections.emptyList());
        }
    }
}
