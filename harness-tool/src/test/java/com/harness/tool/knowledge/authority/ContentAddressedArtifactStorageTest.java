package com.harness.tool.knowledge.authority;

import com.harness.core.knowledge.KnowledgeArtifactType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContentAddressedArtifactStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void identicalContentUsesOneImmutableAddressAndCannotResolveOutsideRoot() {
        ContentAddressedArtifactStorage storage = new ContentAddressedArtifactStorage(tempDir);
        byte[] content = "canonical knowledge".getBytes(StandardCharsets.UTF_8);

        var first = storage.store(content, "tenant-1", "manuals", KnowledgeArtifactType.SOURCE_FILE);
        var second = storage.store(content, "tenant-1", "manuals", KnowledgeArtifactType.SOURCE_FILE);

        assertThat(second).isEqualTo(first);
        assertThat(Files.isRegularFile(storage.resolveStorageUri(first.storageUri()))).isTrue();
        assertThatThrownBy(() -> storage.resolveStorageUri(
                tempDir.resolveSibling("outside.txt").toUri().toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");
    }

    @Test
    void orphanReconcilerReportsButNeverDeletesUnregisteredObjects() {
        ContentAddressedArtifactStorage storage = new ContentAddressedArtifactStorage(tempDir);
        var stored = storage.store(
                "orphan".getBytes(StandardCharsets.UTF_8),
                null,
                "manuals",
                KnowledgeArtifactType.SOURCE_FILE);
        KnowledgeArtifactRepository repository = mock(KnowledgeArtifactRepository.class);
        when(repository.storageUriExists(stored.storageUri())).thenReturn(false);

        var page = new ArtifactOrphanReconciler(storage, repository)
                .findOrphans(Instant.now().plusSeconds(1), null, 10);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().storageUri()).isEqualTo(stored.storageUri());
        assertThat(Files.exists(storage.resolveStorageUri(stored.storageUri()))).isTrue();
    }
}
