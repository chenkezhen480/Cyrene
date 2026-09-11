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
        if (encoded == null || !encoded.startsWith(PREFIX) || encoded.length() > MAX_ENCODED_LENGTH) {
            throw new IllegalArgumentException("invalid knowledge handle envelope");
        }
        try {
            String[] parts = encoded.substring(PREFIX.length()).split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]),
                    Base64.getUrlDecoder().decode(parts[1]))) {
                throw new IllegalArgumentException("invalid knowledge handle signature; search again");
            }
            byte[] json = Base64.getUrlDecoder().decode(parts[0]);
            return objectMapper.readValue(new String(json, StandardCharsets.UTF_8), KnowledgeHandle.class);
        } catch (IllegalArgumentException | JsonProcessingException e) {
            throw new IllegalArgumentException("invalid knowledge handle payload", e);
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
