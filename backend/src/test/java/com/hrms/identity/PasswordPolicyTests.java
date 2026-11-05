package com.hrms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hrms.shared.web.ApiException;
import org.junit.jupiter.api.Test;

class PasswordPolicyTests {

    private static String code(String password, String email) {
        try {
            PasswordPolicy.check(password, email);
            return "OK";
        } catch (ApiException e) {
            return e.code();
        }
    }

    @Test
    void acceptsLongEnoughUncommonPasswords() {
        assertThatCode(() -> PasswordPolicy.check("blue kettle on tuesday", "weiling@demo.hrms.local")).doesNotThrowAnyException();
        assertThat(code("Tr0pical-Mango", null)).isEqualTo("OK");
    }

    @Test
    void rejectsShortCommonRepeatedOrEmailBasedPasswords() {
        assertThat(code("short1", null)).isEqualTo("PASSWORD_TOO_SHORT");
        assertThat(code("x".repeat(201), null)).isEqualTo("PASSWORD_TOO_LONG");
        assertThat(code("Password123", null)).isEqualTo("PASSWORD_TOO_COMMON");
        assertThat(code("ababababab", null)).isEqualTo("PASSWORD_TOO_COMMON");
        assertThat(code("weiling-2026!", "weiling@demo.hrms.local")).isEqualTo("PASSWORD_HAS_EMAIL");
        assertThatThrownBy(() -> PasswordPolicy.check(null, null)).isInstanceOf(ApiException.class);
    }

    @Test
    void masksEmailsForTheResetPage() {
        assertThat(PasswordService.mask("weiling@demo.hrms.local")).isEqualTo("we•••••@demo.hrms.local");
        assertThat(PasswordService.mask("al@x.com")).isEqualTo("•••@x.com");
        assertThat(PasswordService.mask(null)).isNull();
    }
}
