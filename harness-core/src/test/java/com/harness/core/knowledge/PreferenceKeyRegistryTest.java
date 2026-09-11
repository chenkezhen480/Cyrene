package com.harness.core.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreferenceKeyRegistryTest {

    @Test
    void standardRegistry_fixesKnownKeysAndActivationPolicies() {
        PreferenceKeyRegistry registry = PreferenceKeyRegistry.standard();

        assertThat(registry.activationTagsFor("response.verbosity"))
                .containsExactly(PreferenceActivationTagRegistry.GENERAL_RESPONSE);
        assertThat(registry.activationTagsFor("code.namingStyle"))
                .containsExactly(PreferenceActivationTagRegistry.CODE_TASK);
        assertThat(registry.require(PreferenceKeyRegistry.OTHER_KEY).defaultActivationTags()).isEmpty();
    }

    @Test
    void registry_rejectsArbitraryKeyAndTag() {
        PreferenceKeyRegistry registry = PreferenceKeyRegistry.standard();

        assertThatThrownBy(() -> registry.require("response.detailLevel"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unregistered preference key");
        assertThatThrownBy(() -> registry.validateActivationTags(
                PreferenceKeyRegistry.OTHER_KEY, Set.of("MODEL_INVENTED_TASK")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unregistered activationTag");
        assertThatThrownBy(() -> registry.validateActivationTags(
                "response.verbosity", Set.of(PreferenceActivationTagRegistry.CODE_TASK)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("registered defaults");
    }

    @Test
    void otherPreference_keepsIndependentItemsAndUsesOrActivation() {
        PreferenceItem reportItem = new PreferenceItem(
                "other-item-1",
                "生成周报时先列风险",
                Set.of(PreferenceActivationTagRegistry.REPORT_TASK));
        PreferenceItem imageItem = new PreferenceItem(
                "other-item-2",
                "配图使用扁平风格",
                Set.of(PreferenceActivationTagRegistry.IMAGE_TASK));
        PreferenceRevisionData data = new PreferenceRevisionData(
                PreferenceKeyRegistry.OTHER_KEY,
                null,
                Set.of(),
                List.of(reportItem, imageItem));
        PreferenceActivationContext context = PreferenceActivationContext.forFinalResponse(
                Set.of(PreferenceActivationTagRegistry.REPORT_TASK));

        assertThat(data.items()).hasSize(2);
        assertThat(data.items().stream().filter(item -> context.matches(item.activationTags())))
                .containsExactly(reportItem);
    }

    @Test
    void registeredPreference_cannotStoreOtherItems() {
        PreferenceItem item = new PreferenceItem(
                "item-1", "statement", Set.of(PreferenceActivationTagRegistry.CODE_TASK));

        assertThatThrownBy(() -> new PreferenceRevisionData(
                "code.namingStyle",
                "camelCase",
                Set.of(PreferenceActivationTagRegistry.CODE_TASK),
                List.of(item)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valueText and activationTags only");
    }
}
