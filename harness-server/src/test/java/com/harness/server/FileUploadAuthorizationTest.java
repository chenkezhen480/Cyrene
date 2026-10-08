package com.harness.server;

import com.harness.core.security.RequestPrincipal;
import com.harness.server.security.RequestPrincipalResolver;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileUploadAuthorizationTest {
    @TempDir Path directory;

    @Test
    void uploadedFilesRequireBothOwnerMetadataAndTheSameTenantAndUser() throws Exception {
        Path input = Files.createDirectories(directory.resolve("input"));
        String name = "12345678-1234-1234-1234-123456789abc.txt";
        Files.writeString(input.resolve(name), "private");
        var context = mock(Context.class);
        when(context.pathParam("fileName")).thenReturn(name);
        when(context.attribute(RequestPrincipalResolver.PRINCIPAL_ATTRIBUTE)).thenReturn(
                new RequestPrincipal("u1", "t1", "reader", RequestPrincipal.AuthenticationType.JWT));
        var handler = new FileUploadHandler(directory.toString());
        assertThatThrownBy(() -> handler.download(context)).isInstanceOf(SecurityException.class);
        Path metadata = input.resolve(name + ".owner.json");
        Files.writeString(metadata, "{\"userId\":\"u1\",\"tenantId\":\"t2\",\"mimeType\":\"text/plain\"}");
        assertThatThrownBy(() -> handler.download(context)).isInstanceOf(SecurityException.class);
        Files.writeString(metadata, "{\"userId\":\"u2\",\"tenantId\":\"t1\",\"mimeType\":\"text/plain\"}");
        assertThatThrownBy(() -> handler.download(context)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> handler.authorizeReference("/files/input/" + name,
                new RequestPrincipal("u1", "t1", "reader", RequestPrincipal.AuthenticationType.JWT)))
                .isInstanceOf(SecurityException.class);
        Files.writeString(metadata, "{\"userId\":\"u1\",\"tenantId\":\"t1\",\"mimeType\":\"text/plain\"}");
        var content = org.mockito.ArgumentCaptor.forClass(java.io.InputStream.class);
        handler.download(context);
        verify(context).result(content.capture());
        try (var stream = content.getValue()) {
            assertThat(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("private");
        }
        when(context.pathParam("fileName")).thenReturn("../outside.txt");
        assertThatThrownBy(() -> handler.download(context)).isInstanceOf(SecurityException.class);
    }
}
