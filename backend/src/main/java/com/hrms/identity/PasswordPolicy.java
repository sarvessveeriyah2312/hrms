package com.hrms.identity;

import com.hrms.shared.web.ApiException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * What a new password must be. Length matters more than mixing character types (NIST SP 800-63B), so the rules
 * are: 10 to 200 characters, not one of the most common passwords, and not built from the person's email.
 */
final class PasswordPolicy {

    static final int MIN = 10;
    static final int MAX = 200;

    private static final Set<String> COMMON = Set.of(
        "password", "password1", "password12", "password123", "password1234", "passw0rd", "p@ssw0rd", "p@ssword1",
        "1234567890", "12345678910", "0123456789", "qwertyuiop", "qwerty1234", "qwerty12345", "1q2w3e4r5t",
        "iloveyou12", "welcome123", "welcome1234", "letmein123", "admin12345", "abc1234567", "abcdefghij",
        "aaaaaaaaaa", "1111111111", "0000000000", "changeme123", "hrms123456", "infotech123", "malaysia123", "singapore1");

    private PasswordPolicy() {
    }

    static void check(String password, String email) {
        if (password == null || password.length() < MIN) {
            throw invalid("PASSWORD_TOO_SHORT", "Use at least " + MIN + " characters. A few words together is easy to remember.");
        }
        if (password.length() > MAX) throw invalid("PASSWORD_TOO_LONG", "Use at most " + MAX + " characters.");
        String lower = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lower) || lower.chars().distinct().count() <= 2) {
            throw invalid("PASSWORD_TOO_COMMON", "That password is too easy to guess. Choose something less common.");
        }
        if (email != null) {
            String name = email.toLowerCase(Locale.ROOT).split("@")[0];
            if (name.length() >= 4 && lower.contains(name)) {
                throw invalid("PASSWORD_HAS_EMAIL", "Don't use your email address in your password.");
            }
        }
    }

    private static ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message).with("fields", Map.of("newPassword", message));
    }
}
