package com.harness.core.model;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMessageTest {

    @Test
    void user_createsUserMessage() {
        var msg = AgentMessage.user("hello");
        assertThat(msg.role()).isEqualTo(AgentMessage.Role.USER);
        assertThat(msg.text()).isEqualTo("hello");
        assertThat(msg.attachments()).isEmpty();
        assertThat(msg.userId()).isNull();
        assertThat(msg.sessionId()).isNull();
    }

    @Test
    void assistant_createsAssistantMessage() {
        var msg = AgentMessage.assistant("reply");
        assertThat(msg.role()).isEqualTo(AgentMessage.Role.ASSISTANT);
        assertThat(msg.text()).isEqualTo("reply");
    }

    @Test
    void system_createsSystemMessage() {
        var msg = AgentMessage.system("sys");
        assertThat(msg.role()).isEqualTo(AgentMessage.Role.SYSTEM);
        assertThat(msg.text()).isEqualTo("sys");
    }

    @Test
    void toolResult_createsToolMessage() {
        var msg = AgentMessage.toolResult("web_search", "result data");
        assertThat(msg.role()).isEqualTo(AgentMessage.Role.TOOL);
        assertThat(msg.text()).isEqualTo("[web_search] result data");
    }

    @Test
    void withSession_setsUserIdAndSessionId() {
        var original = AgentMessage.user("hello");
        var withSession = original.withSession("user1", "sess1");

        assertThat(withSession.userId()).isEqualTo("user1");
        assertThat(withSession.sessionId()).isEqualTo("sess1");
        assertThat(withSession.text()).isEqualTo("hello");
        assertThat(withSession.role()).isEqualTo(AgentMessage.Role.USER);
    }

    @Test
    void withSession_doesNotMutateOriginal() {
        var original = AgentMessage.user("hello");
        original.withSession("user1", "sess1");

        assertThat(original.userId()).isNull();
        assertThat(original.sessionId()).isNull();
    }

    @Test
    void user_withAttachments_preservesAttachments() {
        var attachment = new AgentMessage.Attachment(
                AgentMessage.Attachment.AttachmentType.IMAGE, "photo.png", new byte[]{1, 2}, "image/png");
        var msg = AgentMessage.user("check this", List.of(attachment));

        assertThat(msg.attachments()).hasSize(1);
        assertThat(msg.attachments().get(0).name()).isEqualTo("photo.png");
    }
}
