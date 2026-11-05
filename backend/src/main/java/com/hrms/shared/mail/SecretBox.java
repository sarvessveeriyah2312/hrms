package com.hrms.shared.mail;

import com.hrms.shared.web.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Encrypts small secrets (such as a company's SMTP password) before they're stored: AES-256-GCM with the key in
 * {@code hrms.secrets.key} and a random 12-byte nonce per value. Stored as base64(nonce + ciphertext + tag).
 */
@Component
public class SecretBox {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    SecretBox(@Value("${hrms.secrets.key:}") String base64Key) {
        SecretKeySpec k = null;
        if (base64Key != null && !base64Key.isBlank()) {
            byte[] raw = Base64.getDecoder().decode(base64Key.trim());
            if (raw.length != 32) throw new IllegalStateException("hrms.secrets.key must be 32 bytes, base64-encoded");
            k = new SecretKeySpec(raw, "AES");
        }
        this.key = k;
    }

    public boolean ready() {
        return key != null;
    }

    public String seal(String plain) {
        if (key == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SECRETS_NOT_CONFIGURED",
                "The server can't store passwords yet: set HRMS_SECRET_KEY first.");
        }
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] sealed = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(12 + sealed.length).put(nonce).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Couldn't encrypt a secret", e);
        }
    }

    /** The plain value, or null when there's no key or the value can't be opened (e.g. the key changed). */
    public String open(String stored) {
        if (key == null || stored == null) return null;
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }
}
