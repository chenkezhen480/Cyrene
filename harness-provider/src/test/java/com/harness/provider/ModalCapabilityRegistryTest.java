package com.harness.provider;

import com.harness.core.env.EnvConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ModalCapabilityRegistryTest {

    @BeforeEach
    void setUp() throws Exception {
        // Reset the static userOverride field between tests
        Field override = ModalCapabilityRegistry.class.getDeclaredField("userOverride");
        override.setAccessible(true);
        override.set(null, null);

        // Init EnvConfig with empty capabilities
        EnvConfig.init(Map.of("HARNESS_MODEL_CHAT_CAPABILITIES", ""));
    }

    @Test
    void getCapabilities_gpt4o_returnsFull() {
        Set<ModalCapability> caps = ModalCapabilityRegistry.getCapabilities("gpt-4o");
        assertThat(caps).containsExactlyInAnyOrder(
                ModalCapability.TEXT, ModalCapability.IMAGE_INPUT,
                ModalCapability.AUDIO_INPUT, ModalCapability.PDF_INPUT);
    }

    @Test
    void getCapabilities_gpt4oMini_returnsImageAndPdf() {
        Set<ModalCapability> caps = ModalCapabilityRegistry.getCapabilities("gpt-4o-mini");
        assertThat(caps).containsExactlyInAnyOrder(
                ModalCapability.TEXT, ModalCapability.IMAGE_INPUT, ModalCapability.PDF_INPUT);
    }

    @Test
    void getCapabilities_gpt35Turbo_returnsTextOnly() {
        Set<ModalCapability> caps = ModalCapabilityRegistry.getCapabilities("gpt-3.5-turbo");
        assertThat(caps).containsExactly(ModalCapability.TEXT);
    }

    @Test
    void getCapabilities_claudeSonnet_returnsImageAndPdf() {
        Set<ModalCapability> caps = ModalCapabilityRegistry.getCapabilities("claude-3-5-sonnet");
        assertThat(caps).containsExactlyInAnyOrder(
                ModalCapability.TEXT, ModalCapability.IMAGE_INPUT, ModalCapability.PDF_INPUT);
    }

    @Test
    void getCapabilities_llava_returnsTextAndImage() {
        Set<ModalCapability> caps = ModalCapabilityRegistry.getCapabilities("llava");
        assertThat(caps).containsExactlyInAnyOrder(ModalCapability.TEXT, ModalCapability.IMAGE_INPUT);
    }

    @Test
    void getCapabilities_prefixMatch_works() {
        // "gpt-4o-2024-08-06" should match "gpt-4o" prefix
        Set<ModalCapability> caps = ModalCapabilityRegistry.getCapabilities("gpt-4o-2024-08-06");
        assertThat(caps).contains(ModalCapability.IMAGE_INPUT);
    }

    @Test
    void getCapabilities_unknownModel_returnsTextOnly() {
        Set<ModalCapability> caps = ModalCapabilityRegistry.getCapabilities("some-random-model");
        assertThat(caps).containsExactly(ModalCapability.TEXT);
    }

    @Test
    void getCapabilities_null_returnsTextOnly() {
        Set<ModalCapability> caps = ModalCapabilityRegistry.getCapabilities(null);
        assertThat(caps).containsExactly(ModalCapability.TEXT);
    }

    @Test
    void getCapabilities_userOverride_takesPriority() throws Exception {
        Field override = ModalCapabilityRegistry.class.getDeclaredField("userOverride");
        override.setAccessible(true);
        override.set(null, Set.of(ModalCapability.TEXT, ModalCapability.IMAGE_INPUT));

        // Even for gpt-3.5-turbo which normally has TEXT only, override wins
        Set<ModalCapability> caps = ModalCapabilityRegistry.getCapabilities("gpt-3.5-turbo");
        assertThat(caps).containsExactlyInAnyOrder(ModalCapability.TEXT, ModalCapability.IMAGE_INPUT);
    }

    @Test
    void getCapabilities_envOverride_parsed() throws Exception {
        // Reset override so it reads from EnvConfig
        Field override = ModalCapabilityRegistry.class.getDeclaredField("userOverride");
        override.setAccessible(true);
        override.set(null, null);

        EnvConfig.init(Map.of("HARNESS_MODEL_CHAT_CAPABILITIES", "text,image_input,pdf_input"));

        Set<ModalCapability> caps = ModalCapabilityRegistry.getCapabilities("unknown-model");
        assertThat(caps).containsExactlyInAnyOrder(
                ModalCapability.TEXT, ModalCapability.IMAGE_INPUT, ModalCapability.PDF_INPUT);
    }
}
