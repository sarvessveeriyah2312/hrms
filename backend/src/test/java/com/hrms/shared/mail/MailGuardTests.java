package com.hrms.shared.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hrms.shared.web.ApiException;
import org.junit.jupiter.api.Test;

class MailGuardTests {

    private final MailGuard production = new MailGuard(false);
    private final MailGuard local = new MailGuard(true);

    @Test
    void acceptsNormalServerNamesAndMailPorts() {
        assertThat(production.host("  SMTP.Office365.com ")).isEqualTo("smtp.office365.com");
        assertThat(production.port(587)).isEqualTo(587);
        assertThat(production.port(465)).isEqualTo(465);
        assertThat(production.security("starttls")).isEqualTo("STARTTLS");
        assertThat(production.email("fromEmail", " hr@acme.com.my ", true)).isEqualTo("hr@acme.com.my");
    }

    @Test
    void rejectsUrlsOddPortsAndBadAddresses() {
        assertThatThrownBy(() -> production.host("https://smtp.gmail.com")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> production.host("smtp.gmail.com:587")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> production.port(22)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> production.port(1025)).isInstanceOf(ApiException.class);
        assertThat(local.port(1025)).isEqualTo(1025);
        assertThatThrownBy(() -> production.security("TLS1.0")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> production.email("fromEmail", "not an email", true)).isInstanceOf(ApiException.class);
        assertThat(production.email("replyTo", "", false)).isNull();
    }

    @Test
    void refusesPrivateAddressesUnlessAllowed() {
        assertThatThrownBy(() -> production.reachable("localhost")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> production.reachable("127.0.0.1")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> production.reachable("10.1.2.3")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> production.reachable("192.168.0.10")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> production.reachable("169.254.169.254")).isInstanceOf(ApiException.class);
        local.reachable("127.0.0.1"); // allowed for Mailpit in development
    }

    @Test
    void secretsRoundTripAndDontLeakWithAnotherKey() {
        SecretBox box = new SecretBox("bG9jYWwtb25seS1kby1ub3QtdXNlLWluLXByb2R1Y3Q=");
        String sealed = box.seal("app-password-123");
        assertThat(sealed).doesNotContain("app-password");
        assertThat(box.seal("app-password-123")).isNotEqualTo(sealed); // fresh nonce each time
        assertThat(box.open(sealed)).isEqualTo("app-password-123");
        SecretBox other = new SecretBox("YW5vdGhlci1rZXktMzItYnl0ZXMtbG9uZy0xMjM0NTY=");
        assertThat(other.open(sealed)).isNull();
        assertThatThrownBy(() -> new SecretBox("").seal("x")).isInstanceOf(ApiException.class);
    }
}
