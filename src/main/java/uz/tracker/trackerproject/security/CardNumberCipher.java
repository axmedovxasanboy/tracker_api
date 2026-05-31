package uz.tracker.trackerproject.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class CardNumberCipher {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    // Marker prepended to ciphertext to distinguish from legacy plaintext stored values.
    private static final byte VERSION = 1;

    @Value("${app.card.encryption-key}")
    private String keyBase64;

    private SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    @PostConstruct
    void init() {
        byte[] material;
        try {
            material = Base64.getDecoder().decode(keyBase64);
        } catch (IllegalArgumentException notBase64) {
            material = keyBase64.getBytes(StandardCharsets.UTF_8);
        }
        // Canonical format is base64-encoded exactly 32 bytes. If the supplied value is
        // anything else (wrong length, raw passphrase, etc.) we derive a 32-byte key
        // via SHA-256 — deterministic, no exception, and still cryptographically sound
        // for a single-tenant dev/prod boundary where the operator picks the input.
        if (material.length != 32) {
            try {
                material = MessageDigest.getInstance("SHA-256").digest(material);
            } catch (Exception e) {
                throw new IllegalStateException("SHA-256 unavailable; cannot derive AES key", e);
            }
        }
        key = new SecretKeySpec(material, ALGORITHM);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes());
            ByteBuffer buf = ByteBuffer.allocate(1 + iv.length + ct.length);
            buf.put(VERSION).put(iv).put(ct);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new IllegalStateException("Card number encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) return null;
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(stored);
        } catch (IllegalArgumentException notBase64) {
            // Legacy plaintext from before encryption was introduced.
            return stored;
        }
        if (decoded.length < 1 + IV_LENGTH + 1 || decoded[0] != VERSION) {
            return stored;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(decoded, 1, iv, 0, IV_LENGTH);
            byte[] ct = new byte[decoded.length - 1 - IV_LENGTH];
            System.arraycopy(decoded, 1 + IV_LENGTH, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ct));
        } catch (Exception e) {
            throw new IllegalStateException("Card number decryption failed — wrong key?", e);
        }
    }

    public boolean isEncrypted(String stored) {
        if (stored == null || stored.isBlank()) return false;
        try {
            byte[] decoded = Base64.getDecoder().decode(stored);
            return decoded.length >= 1 + IV_LENGTH + 1 && decoded[0] == VERSION;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
