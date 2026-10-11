package com.harness.tool.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ArtifactStorageServiceTest {
    @TempDir Path directory;

    @Test void internalPayloadsAreImmutablePrivateAndReloadableWhileReferencesCanAdvance() {
        var metadata = new FilesystemArtifactStore(directory);
        var storage = new ArtifactStorageService(metadata, directory, 1);
        String id = UUID.randomUUID().toString();
        byte[] first = "{\"secret\":\"baseline\"}".getBytes(StandardCharsets.UTF_8);
        byte[] second = "{\"version\":2}".getBytes(StandardCharsets.UTF_8);
        storage.writeGraphDraftPayload(id, first);
        assertThat(metadata.get(id)).isEmpty();
        assertThat(metadata.listBySession("session")).isEmpty();
        assertThatThrownBy(() -> storage.writeGraphDraftPayload(id, second))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already exists");
        var reloaded = new ArtifactStorageService(new FilesystemArtifactStore(directory), directory, 1);
        assertThat(reloaded.readGraphDraftPayload(id)).isEqualTo(first);
        storage.writeGraphDraftReference(id, first);
        storage.writeGraphDraftReference(id, second);
        assertThat(reloaded.readGraphDraftReference(id)).hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(second));
    }

    @Test void internalPayloadsRejectInvalidIdsMissingFilesAndOversizedContent() {
        var storage = new ArtifactStorageService(new FilesystemArtifactStore(directory), directory, 1);
        assertThatThrownBy(() -> storage.writeGraphDraftPayload("../secret", new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.readGraphDraftPayload("../secret")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.readGraphDraftPayload(UUID.randomUUID().toString())).isInstanceOf(NoSuchElementException.class);
        String id = UUID.randomUUID().toString();
        assertThatThrownBy(() -> storage.writeGraphDraftPayload(id, new byte[1024 * 1024 + 1]))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> storage.readGraphDraftPayload(id)).isInstanceOf(NoSuchElementException.class);
    }
}
