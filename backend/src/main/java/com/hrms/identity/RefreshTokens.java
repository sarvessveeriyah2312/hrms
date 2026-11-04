package com.hrms.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque refresh tokens. Only a SHA-256 hash is stored. Each refresh rotates the token; presenting an
 * already-used token revokes every session of that user (a sign the token was copied).
 */
@Component
class RefreshTokens {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokens.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    record Rotated(UUID userId, String token) {
    }

    private final JdbcClient jdbc;
    private final Duration ttl;

    RefreshTokens(JdbcClient jdbc, @Value("${hrms.auth.refresh-token-days:30}") long ttlDays) {
        this.jdbc = jdbc;
        this.ttl = Duration.ofDays(ttlDays);
    }

    Duration ttl() {
        return ttl;
    }

    String create(UUID userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("insert into refresh_token (user_id, token_hash, expires_at) values (:user, :hash, :expires)")
            .param("user", userId)
            .param("hash", hash(token))
            .param("expires", java.sql.Timestamp.from(Instant.now().plus(ttl)))
            .update();
        return token;
    }

    @Transactional
    Optional<Rotated> rotate(String token) {
        record Row(UUID id, UUID userId, Instant expiresAt, Instant revokedAt) {
        }
        Optional<Row> found = jdbc.sql("""
                select id, user_id, expires_at, revoked_at from refresh_token
                where token_hash = :hash for update""")
            .param("hash", hash(token))
            .query(Row.class)
            .optional();
        if (found.isEmpty()) return Optional.empty();

        Row row = found.get();
        if (row.revokedAt() != null) {
            log.warn("Refresh token reused for user {}; revoking all of their sessions", row.userId());
            revokeAll(row.userId());
            return Optional.empty();
        }
        if (row.expiresAt().isBefore(Instant.now())) return Optional.empty();

        jdbc.sql("update refresh_token set revoked_at = now() where id = :id").param("id", row.id()).update();
        return Optional.of(new Rotated(row.userId(), create(row.userId())));
    }

    void revoke(String token) {
        jdbc.sql("update refresh_token set revoked_at = now() where token_hash = :hash and revoked_at is null")
            .param("hash", hash(token))
            .update();
    }

    void revokeAll(UUID userId) {
        jdbc.sql("update refresh_token set revoked_at = now() where user_id = :user and revoked_at is null")
            .param("user", userId)
            .update();
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
