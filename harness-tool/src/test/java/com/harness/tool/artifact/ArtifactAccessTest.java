package com.harness.tool.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.Artifact;
import com.harness.core.model.ArtifactStore;
import com.harness.core.model.ToolCall;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;
import com.harness.tool.ToolExecutor;
import com.harness.tool.confirmation.ConfirmationManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArtifactAccessTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void uploadedFilesRequireExactOwnerAndConfinedPaths() throws Exception {
        Path input = Files.createDirectory(root.resolve("input"));
        String name = "12345678-1234-1234-1234-123456789abc.png";
        Files.writeString(input.resolve(name), "image");
        mapper.writeValue(input.resolve(name + ".owner.json").toFile(),
                new UploadedFileAccess.FileOwner("user-a", "tenant-a", "image/png"));
        var access = new UploadedFileAccess(root, mapper);
        assertThat(access.authorize("/files/input/" + name, "user-a", "tenant-a").path()).isEqualTo(input.resolve(name));
        for (String user : new String[]{"user-b", "User-a"}) {
            assertThatThrownBy(() -> access.authorize("/files/input/" + name, user, "tenant-a")).isInstanceOf(SecurityException.class);
        }
        assertThatThrownBy(() -> access.authorize("/files/input/" + name, "user-a", "tenant-b")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> access.authorize("/files/input/../secret", "user-a", "tenant-a")).isInstanceOf(SecurityException.class);
        Files.delete(input.resolve(name + ".owner.json"));
        assertThatThrownBy(() -> access.authorize("/files/input/" + name, "user-a", "tenant-a")).isInstanceOf(SecurityException.class);
    }

    @Test void toolsBindGeneratedArtifactsWithoutConfirmationAndClearScopeAfterFailure() {
        var metadata = mock(ArtifactStore.class);
        var storage = new ArtifactStorageService(metadata, root, 1);
        var saved = new AtomicReference<Artifact>();
        Tool tool = new Tool() {
            public ToolSpec spec() { return new ToolSpec("artifact_test", "test", mapper.createObjectNode()); }
            public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                saved.set(storage.store(new byte[]{1}, "output.png", "image/png", null));
                assertThat(ArtifactSessionContext.requireAccess(saved.get())).isEqualTo(saved.get());
                assertThatThrownBy(() -> storage.store(new byte[]{1}, "other.png", "image/png", "foreign-session"))
                        .isInstanceOf(SecurityException.class);
                throw new IllegalStateException("after write");
            }
        };
        var executor = new ToolExecutor(new ConfirmationManager(java.time.Duration.ofMinutes(1)));
        var result = executor.executeAuthorized(ToolCall.of("artifact_test", mapper.createObjectNode()), tool, null, "owned-session");
        assertThat(result.success()).isFalse();
        assertThat(saved.get().sessionId()).isEqualTo("owned-session");
        verify(metadata).save(saved.get());
        assertThat(ArtifactSessionContext.current()).isNull();
        assertThatThrownBy(() -> ArtifactSessionContext.requireAccess(saved.get())).isInstanceOf(SecurityException.class);
        ArtifactSessionContext.set("foreign-session");
        try { assertThatThrownBy(() -> ArtifactSessionContext.requireAccess(saved.get())).isInstanceOf(SecurityException.class); }
        finally { ArtifactSessionContext.clear(); }
    }
}
