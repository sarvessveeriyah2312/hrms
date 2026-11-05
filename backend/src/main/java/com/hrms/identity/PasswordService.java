package com.hrms.identity;

import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.mail.Mailer;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Forgot password (a one-time link by email) and change password (signed in, with the current password).
 *
 * <p>Asking for a link always gets the same answer, whether or not an account matches, so the form can't be used to
 * find out who has an account. Links are random 256-bit tokens; only their SHA-256 hash is stored. A link works once,
 * expires after {@code hrms.auth.reset-link-minutes}, and setting a new password signs the person out everywhere.
 */
@Service
class PasswordService {

    /** What the reset page shows before the new password is typed: which account the link is for. */
    record LinkInfo(String companyName, String email) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final UserDirectory users;
    private final RefreshTokens refreshTokens;
    private final PasswordEncoder passwords;
    private final Mailer mailer;
    private final AuditLog audit;
    private final Duration linkLife;
    private final int perHour;
    private final String webBase;

    PasswordService(JdbcClient jdbc, UserDirectory users, RefreshTokens refreshTokens, PasswordEncoder passwords, Mailer mailer,
                    AuditLog audit, @Value("${hrms.auth.reset-link-minutes:30}") long linkMinutes,
                    @Value("${hrms.auth.reset-requests-per-hour:3}") int perHour,
                    @Value("${hrms.web.base-url:http://localhost:5173}") String webBase) {
        this.jdbc = jdbc;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwords = passwords;
        this.mailer = mailer;
        this.audit = audit;
        this.linkLife = Duration.ofMinutes(linkMinutes);
        this.perHour = perHour;
        this.webBase = webBase.replaceAll("/+$", "");
    }

    // ---------- forgot password ----------

    /**
     * Emails a reset link to each active account that signs in with this email or mobile number (and has an email).
     * Says nothing about whether any did.
     */
    @Transactional
    void requestReset(String login, String companyCode) {
        if (login == null || login.isBlank()) return;
        String code = companyCode == null || companyCode.isBlank() ? null : companyCode.trim();
        for (UserDirectory.UserRow u : users.findByLogin(login.trim(), code)) {
            if (u.email() == null || !"ACTIVE".equals(u.status()) || !"ACTIVE".equals(u.tenantStatus())) continue;
            int recent = jdbc.sql("""
                    select count(*) from password_reset where user_id = :user and created_at > now() - interval '1 hour'""")
                .param("user", u.id()).query(Integer.class).single();
            if (recent >= perHour) continue; // quietly: the answer must look the same

            String token = newToken();
            jdbc.sql("""
                    insert into password_reset (user_id, token_hash, expires_at) values (:user, :hash, :expires)""")
                .param("user", u.id()).param("hash", hash(token))
                .param("expires", java.sql.Timestamp.from(Instant.now().plus(linkLife))).update();
            inTenant(u.tenantId(), () -> {
                audit.record(u.id(), "PASSWORD_RESET_REQUESTED", "user", u.id(), Map.of());
                return null;
            });
            mailer.send(u.tenantId(), u.email(), "Reset your " + u.companyName() + " HRMS password", """
                Hello,

                Someone asked to reset the password for your %s account (%s).

                Choose a new password here:
                %s/reset-password?token=%s

                The link works once and expires in %d minutes.

                If you didn't ask for this, you can ignore this email. Your password stays the same.
                """.formatted(u.companyName(), u.email(), webBase, token, linkLife.toMinutes()));
        }
    }

    /** Which account a link is for, if it's still valid. */
    LinkInfo checkLink(String token) {
        Link link = findLink(token, false).orElseThrow(PasswordService::badLink);
        UserDirectory.UserRow u = users.findById(link.userId()).orElseThrow(PasswordService::badLink);
        return new LinkInfo(u.companyName(), mask(u.email()));
    }

    /** Sets the new password from a reset link, then signs the person out everywhere. */
    @Transactional
    void resetWithLink(String token, String newPassword) {
        Link link = findLink(token, true).orElseThrow(PasswordService::badLink);
        UserDirectory.UserRow u = users.findById(link.userId()).orElseThrow(PasswordService::badLink);
        if (!"ACTIVE".equals(u.status()) || !"ACTIVE".equals(u.tenantStatus())) throw badLink();
        PasswordPolicy.check(newPassword, u.email());
        setPassword(u.id(), newPassword);
        // This link is used; any other links for this person stop working too.
        jdbc.sql("update password_reset set used_at = now() where user_id = :user and used_at is null")
            .param("user", u.id()).update();
        refreshTokens.revokeAll(u.id());
        inTenant(u.tenantId(), () -> {
            audit.record(u.id(), "PASSWORD_RESET", "user", u.id(), Map.of("via", "email link"));
            return null;
        });
        changedNotice(u);
    }

    // ---------- change password ----------

    /**
     * Changes the signed-in person's password. Their other sessions are signed out; {@code keepRefreshToken}
     * (this browser's session) stays signed in.
     */
    @Transactional
    void change(UUID userId, String currentPassword, String newPassword, String keepRefreshToken) {
        UserDirectory.UserRow u = users.findById(userId).orElseThrow(AuthService::sessionExpired);
        if (currentPassword == null || u.passwordHash() == null || !passwords.matches(currentPassword, u.passwordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WRONG_PASSWORD", "Your current password isn't right.")
                .with("fields", Map.of("currentPassword", "Your current password isn't right."));
        }
        PasswordPolicy.check(newPassword, u.email());
        if (passwords.matches(newPassword, u.passwordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED", "Choose a password different from your current one.")
                .with("fields", Map.of("newPassword", "Choose a password different from your current one."));
        }
        setPassword(u.id(), newPassword);
        refreshTokens.revokeAllExcept(u.id(), keepRefreshToken);
        audit.record(u.id(), "PASSWORD_CHANGED", "user", u.id(), Map.of());
        changedNotice(u);
    }

    // ---------- internals ----------

    private void setPassword(UUID userId, String password) {
        jdbc.sql("""
                update app_user set password_hash = :hash, must_change_password = false, password_changed_at = now()
                where id = :id""")
            .param("hash", passwords.encode(password)).param("id", userId).update();
    }

    private void changedNotice(UserDirectory.UserRow u) {
        if (u.email() == null) return;
        mailer.send(u.tenantId(), u.email(), "Your " + u.companyName() + " HRMS password was changed", """
            Hello,

            The password for your %s account (%s) was just changed, and other devices were signed out.

            If this wasn't you, reset your password straight away at %s/forgot-password and tell your HR administrator.
            """.formatted(u.companyName(), u.email(), webBase));
    }

    private record Link(UUID id, UUID userId) {
    }

    /** A link that exists, isn't used and hasn't expired. {@code lock} holds it until the transaction ends. */
    private Optional<Link> findLink(String token, boolean lock) {
        if (token == null || token.length() < 20 || token.length() > 100) return Optional.empty();
        return jdbc.sql("""
                select id, user_id from password_reset
                where token_hash = :hash and used_at is null and expires_at > now()""" + (lock ? " for update" : ""))
            .param("hash", hash(token)).query(Link.class).optional();
    }

    /**
     * Runs work for one company inside the current transaction. These requests start before sign-in, so the
     * transaction's connection wasn't bound to a company; bind it here (for this transaction only) as well.
     */
    private <T> T inTenant(UUID tenant, Supplier<T> work) {
        Optional<UUID> previous = TenantContext.current();
        TenantContext.set(tenant);
        jdbc.sql("select set_config('app.tenant_id', :tenant, true)").param("tenant", tenant.toString()).query(String.class).single();
        try {
            return work.get();
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** "we•••••@demo.hrms.local" */
    static String mask(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 2) return "•••" + email.substring(Math.max(at, 0));
        return email.substring(0, 2) + "•".repeat(Math.min(5, at - 2)) + email.substring(at);
    }

    private static ApiException badLink() {
        return new ApiException(HttpStatus.BAD_REQUEST, "RESET_LINK_INVALID",
            "This link has expired or has already been used. Ask for a new one.");
    }
}
