package com.hrms.identity;

import com.hrms.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Password sign-in and session refresh.
 * TODO(identity): rate-limit attempts per login and IP (Redis), 2FA challenge, SSO (OIDC + PKCE).
 */
@Service
class AuthService {

    record Session(AuthenticatedUser user, TokenService.Issued access, String refreshToken) {
    }

    private final UserDirectory users;
    private final RefreshTokens refreshTokens;
    private final TokenService tokens;
    private final PasswordEncoder passwords;
    /** Checked when no user matches, so a wrong email takes as long as a wrong password. */
    private final String dummyHash;

    AuthService(UserDirectory users, RefreshTokens refreshTokens, TokenService tokens, PasswordEncoder passwords) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.tokens = tokens;
        this.passwords = passwords;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    /**
     * Signs in with an email or mobile number. {@code companyCode} is optional: when the password matches
     * accounts in more than one company, the API answers COMPANY_REQUIRED with those companies so the user
     * can pick one. Companies are listed only after the password has been proven for each of them.
     */
    Session login(String login, String password, String companyCode) {
        String code = companyCode == null || companyCode.isBlank() ? null : companyCode.trim();
        List<UserDirectory.UserRow> candidates = users.findByLogin(login.trim(), code);
        if (candidates.isEmpty()) {
            passwords.matches(password, dummyHash);
            throw invalidCredentials();
        }
        List<UserDirectory.UserRow> matched = candidates.stream()
            .filter(u -> u.passwordHash() != null && passwords.matches(password, u.passwordHash()))
            .toList();
        if (matched.isEmpty()) throw invalidCredentials();
        if (matched.size() > 1) {
            throw new ApiException(HttpStatus.CONFLICT, "COMPANY_REQUIRED",
                "You have accounts in more than one company. Choose the one to sign in to.")
                .with("companies", matched.stream()
                    .map(u -> Map.of("code", u.companyCode(), "name", u.companyName()))
                    .toList());
        }
        UserDirectory.UserRow user = matched.getFirst();
        requireActive(user);
        users.recordLogin(user.id());
        return start(user, refreshTokens.create(user.id()));
    }

    Session refresh(String refreshToken) {
        var rotated = refreshTokens.rotate(refreshToken).orElseThrow(AuthService::sessionExpired);
        var user = users.findById(rotated.userId()).orElseThrow(AuthService::sessionExpired);
        requireActive(user);
        return start(user, rotated.token());
    }

    /** A new access token for a signed-in user (after their details changed), keeping their refresh token. */
    Session reissue(UUID userId, String refreshToken) {
        var user = users.findById(userId).orElseThrow(AuthService::sessionExpired);
        requireActive(user);
        return start(user, refreshToken);
    }

    void logout(String refreshToken) {
        refreshTokens.revoke(refreshToken);
    }

    private Session start(UserDirectory.UserRow row, String refreshToken) {
        AuthenticatedUser principal = users.principalFor(row);
        return new Session(principal, tokens.issue(principal), refreshToken);
    }

    private static void requireActive(UserDirectory.UserRow user) {
        if (!"ACTIVE".equals(user.tenantStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "COMPANY_SUSPENDED",
                "This company's account is suspended. Contact your HR administrator.");
        }
        if (!"ACTIVE".equals(user.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED",
                "This account is disabled. Ask your HR administrator to turn it back on.");
        }
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
            "The email, mobile number or password is wrong.");
    }

    static ApiException sessionExpired() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Your session has ended. Sign in again.");
    }
}
