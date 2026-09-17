package com.harness.core.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Versioned, bounded wire encoding for model-visible knowledge handles. */
public final class KnowledgeHandleCodec {

    private static final String PREFIX = "kh1.";
    private static final int MAX_ENCODED_LENGTH = 4096;
    private static final java.util.regex.Pattern HANDLE_WHITESPACE =
            java.util.regex.Pattern.compile("\\s+");

    private final ObjectMapper objectMapper;
    private final SecretKeySpec signingKey;

    public KnowledgeHandleCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        this.signingKey = new SecretKeySpec(key, "HmacSHA256");
    }

    public String encode(KnowledgeHandle handle) {
        Objects.requireNonNull(handle, "handle");
        try {
            String payload = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(handle));
            String encoded = PREFIX + payload + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(sign(payload));
            if (encoded.length() > MAX_ENCODED_LENGTH) {
                throw new IllegalArgumentException("knowledge handle exceeds maximum encoded length");
            }
            return encoded;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to encode knowledge handle", e);
        }
    }

    public KnowledgeHandle decode(String encoded) {
        // Models re-wrap long opaque tokens when copying them back, so a handle may come in with
        // line breaks a JSON string cannot show. Whitespace is never part of the encoding: drop it
        // rather than reporting a signature failure the model has no way to act on.
        String candidate = encoded == null
                ? null
                : HANDLE_WHITESPACE.matcher(encoded).replaceAll("");
        if (candidate == null || !candidate.startsWith(PREFIX)
                || candidate.length() > MAX_ENCODED_LENGTH) {
            throw new IllegalArgumentException("invalid knowledge handle envelope");
        }
        String[] parts = candidate.substring(PREFIX.length()).split("\\.", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "invalid knowledge handle envelope; pass back a handle exactly as returned "
                            + "by knowledge_search, without editing it");
        }
        if (!MessageDigest.isEqual(sign(parts[0]), signatureBytes(parts[1]))) {
            throw new IllegalArgumentException(
                    "knowledge handle was altered or belongs to another server session; run "
                            + "knowledge_search again and pass the returned handle unmodified");
        }
        try {
            byte[] json = Base64.getUrlDecoder().decode(parts[0]);
            return objectMapper.readValue(new String(json, StandardCharsets.UTF_8), KnowledgeHandle.class);
        } catch (IllegalArgumentException | JsonProcessingException e) {
            throw new IllegalArgumentException("invalid knowledge handle payload", e);
        }
    }

    /** Kept outside the payload try-block so an unreadable signature keeps its own reason. */
    private static byte[] signatureBytes(String encodedSignature) {
        try {
            return Base64.getUrlDecoder().decode(encodedSignature);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "knowledge handle was altered or belongs to another server session; run "
                            + "knowledge_search again and pass the returned handle unmodified", e);
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey);
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Knowledge handle signing is unavailable", exception);
        }
    }
}
