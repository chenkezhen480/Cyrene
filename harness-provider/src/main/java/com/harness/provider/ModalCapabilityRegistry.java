package com.harness.provider;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Registry of known model modal capabilities.
 * Priority: user env override > static table.
 */
public final class ModalCapabilityRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModalCapabilityRegistry.class);

    private static final Map<String, Set<ModalCapability>> KNOWN = Map.of(
            "gpt-4o",           Set.of(ModalCapability.TEXT, ModalCapability.IMAGE_INPUT, ModalCapability.AUDIO_INPUT, ModalCapability.PDF_INPUT),
            "gpt-4o-mini",      Set.of(ModalCapability.TEXT, ModalCapability.IMAGE_INPUT, ModalCapability.PDF_INPUT),
            "gpt-3.5-turbo",    Set.of(ModalCapability.TEXT),
            "claude-3-5-sonnet", Set.of(ModalCapability.TEXT, ModalCapability.IMAGE_INPUT, ModalCapability.PDF_INPUT),
            "claude-3-5-haiku", Set.of(ModalCapability.TEXT, ModalCapability.IMAGE_INPUT, ModalCapability.PDF_INPUT),
            "claude-3-opus",    Set.of(ModalCapability.TEXT, ModalCapability.IMAGE_INPUT, ModalCapability.PDF_INPUT),
            "llava",            Set.of(ModalCapability.TEXT, ModalCapability.IMAGE_INPUT)
    );

    private ModalCapabilityRegistry() {}

    /**
     * Get capabilities for a model name. Checks user override first, then static table.
     */
    public static Set<ModalCapability> getCapabilities(String modelName) {
        Set<ModalCapability> override = getUserOverride();
        if (override != null) {
            log.debug("Using user-overridden capabilities for model '{}': {}", modelName, override);
            return override;
        }

        if (modelName == null) return Set.of(ModalCapability.TEXT);

        // Try exact match, then prefix match
        Set<ModalCapability> caps = KNOWN.get(modelName);
        if (caps != null) return caps;

        for (Map.Entry<String, Set<ModalCapability>> entry : KNOWN.entrySet()) {
            if (modelName.startsWith(entry.getKey()) || entry.getKey().startsWith(modelName)) {
                return entry.getValue();
            }
        }

        // Default: TEXT only
        return Set.of(ModalCapability.TEXT);
    }

    private static Set<ModalCapability> getUserOverride() {
        String envVal = EnvConfig.get().getString(EnvKey.MODEL_CHAT_CAPABILITIES, "");
        if (envVal.isBlank()) return null;

        Set<ModalCapability> caps = EnumSet.noneOf(ModalCapability.class);
        for (String part : envVal.split(",")) {
            String trimmed = part.trim().toUpperCase();
            try {
                caps.add(ModalCapability.valueOf(trimmed));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown modal capability in env override: {}", trimmed);
            }
        }
        return caps.isEmpty() ? null : Set.copyOf(caps);
    }
}
