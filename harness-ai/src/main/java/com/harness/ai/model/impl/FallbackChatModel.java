package com.harness.ai.model.impl;

import com.harness.ai.model.*;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatModel wrapper that transparently falls back to Vision/Voice providers
 * when the delegate model lacks the required modal capability.
 */
public class FallbackChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(FallbackChatModel.class);

    private final ChatModel delegate;
    private final VisionModelProvider visionProvider;
    private final VoiceModelProvider voiceProvider;
    private final Set<ModalCapability> capabilities;

    private final ThreadLocal<Map<String, String>> lastFallbackMeta = new ThreadLocal<>();

    public FallbackChatModel(ChatModel delegate, VisionModelProvider visionProvider,
                             VoiceModelProvider voiceProvider, String modelName) {
        this.delegate = delegate;
        this.visionProvider = visionProvider;
        this.voiceProvider = voiceProvider;
        this.capabilities = ModalCapabilityRegistry.getCapabilities(modelName);
        log.info("FallbackChatModel initialized: model={}, capabilities={}", modelName, capabilities);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        try {
            return processChat(request);
        } catch (Exception e) {
            if (isModalException(e) && !lastFallbackMeta.get().isEmpty()) {
                log.warn("Runtime fallback triggered: {}", e.getMessage());
                return processChat(request);
            }
            throw e;
        } finally {
            lastFallbackMeta.remove();
        }
    }

    private ChatResponse processChat(ChatRequest request) {
        Map<String, String> fallbackMeta = new ConcurrentHashMap<>();
        lastFallbackMeta.set(fallbackMeta);

        List<ChatMessage> originalMessages = new ArrayList<>(request.messages());
        List<ChatMessage> transformedMessages = new ArrayList<>();
        boolean modified = false;

        for (ChatMessage msg : originalMessages) {
            if (msg instanceof UserMessage userMsg) {
                ChatMessage transformed = processUserMessage(userMsg, fallbackMeta);
                transformedMessages.add(transformed);
                if (transformed != msg) modified = true;
            } else {
                transformedMessages.add(msg);
            }
        }

        if (!modified) {
            return delegate.chat(request);
        }

        ChatRequest.Builder newReq = ChatRequest.builder().messages(transformedMessages);
        if (request.toolSpecifications() != null) {
            newReq.toolSpecifications(request.toolSpecifications());
        }
        if (request.temperature() != null) {
            newReq.temperature(request.temperature());
        }
        if (request.maxOutputTokens() != null) {
            newReq.maxOutputTokens(request.maxOutputTokens());
        }

        log.debug("Fallback: sending transformed request to delegate model");
        return delegate.chat(newReq.build());
    }

    private ChatMessage processUserMessage(UserMessage msg, Map<String, String> fallbackMeta) {
        List<Content> contents = new ArrayList<>(msg.contents());
        List<Content> newContents = new ArrayList<>();
        boolean modified = false;

        for (Content c : contents) {
            if (c instanceof ImageContent imageContent && !capabilities.contains(ModalCapability.IMAGE_INPUT)) {
                if (visionProvider != null && !(visionProvider instanceof NoOpVisionModelProvider)) {
                    try {
                        String base64 = imageContent.image() != null ? imageContent.image().base64Data() : null;
                        String mimeType = imageContent.image() != null ? imageContent.image().mimeType() : "image/png";
                        Image image = Image.builder()
                                .base64Data(base64)
                                .mimeType(mimeType)
                                .build();
                        String description = visionProvider.analyze("Describe this image in detail", image);
                        newContents.add(TextContent.from("[Image description: " + description + "]"));
                        modified = true;
                        fallbackMeta.put("fallback_triggered", "true");
                        fallbackMeta.put("fallback_reason", "IMAGE_INPUT not supported");
                        fallbackMeta.put("fallback_provider", visionProvider.providerName());
                        log.info("[harness] ChatModel 不支持 IMAGE_INPUT，已自动降级至 VisionModelProvider");
                    } catch (Exception e) {
                        log.error("Vision fallback failed: {}", e.getMessage());
                        newContents.add(TextContent.from("[Image could not be processed]"));
                        modified = true;
                    }
                } else {
                    newContents.add(TextContent.from("[Image could not be processed - no vision provider configured]"));
                    modified = true;
                }
            } else if (c instanceof AudioContent audioContent && !capabilities.contains(ModalCapability.AUDIO_INPUT)) {
                if (voiceProvider != null && !(voiceProvider instanceof NoOpVoiceModelProvider)) {
                    try {
                        String base64 = audioContent.audio() != null ? audioContent.audio().base64Data() : null;
                        String mimeType = audioContent.audio() != null ? audioContent.audio().mimeType() : "audio/wav";
                        byte[] audioBytes = base64 != null ? Base64.getDecoder().decode(base64) : new byte[0];
                        String transcription = voiceProvider.transcribe(
                                new ByteArrayInputStream(audioBytes), mimeType);
                        newContents.add(TextContent.from("[Audio transcription: " + transcription + "]"));
                        modified = true;
                        fallbackMeta.put("fallback_triggered", "true");
                        fallbackMeta.put("fallback_reason", "AUDIO_INPUT not supported");
                        fallbackMeta.put("fallback_provider", voiceProvider.providerName());
                        log.info("[harness] ChatModel 不支持 AUDIO_INPUT，已自动降级至 VoiceModelProvider");
                    } catch (Exception e) {
                        log.error("Voice fallback failed: {}", e.getMessage());
                        newContents.add(TextContent.from("[Audio could not be processed]"));
                        modified = true;
                    }
                } else {
                    newContents.add(TextContent.from("[Audio could not be processed - no voice provider configured]"));
                    modified = true;
                }
            } else {
                newContents.add(c);
            }
        }

        if (!modified) return msg;
        return UserMessage.from(newContents);
    }

    private boolean isModalException(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("image") || lower.contains("audio") || lower.contains("modal")
                || lower.contains("vision") || lower.contains("unsupported");
    }

    public Map<String, String> getLastFallbackMeta() {
        Map<String, String> meta = lastFallbackMeta.get();
        return meta != null ? Map.copyOf(meta) : Map.of();
    }
}
