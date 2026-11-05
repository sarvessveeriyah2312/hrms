package com.hrms.identity;

import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.mail.MailGuard;
import com.hrms.shared.mail.MailServer;
import com.hrms.shared.mail.MailSettingsStore;
import com.hrms.shared.mail.Mailer;
import com.hrms.shared.mail.SecretBox;
import com.hrms.shared.web.ApiException;
import jakarta.mail.AuthenticationFailedException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLException;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Company → Email: the company's own SMTP server for the emails the app sends. Needs "Manage company settings".
 * The password is write-only: saved encrypted, never returned (only whether one is saved).
 */
@RestController
@RequestMapping("/api/v1/admin/company/mail")
class MailSettingsController {

    /** route: where the company's email goes now (COMPANY, PLATFORM or LOG). */
    record View(MailSettingsStore.Settings settings, Mailer.Route route, boolean canStorePasswords, boolean privateHostsAllowed) {
    }

    record Input(Boolean enabled, String host, Integer port, String security, String username, String password,
                 String fromName, String fromEmail, String replyTo) {
    }

    /** Sends a test through the settings in the form (saved password used when the field is left empty). */
    record TestInput(Input settings, String to) {
    }

    record TestResult(boolean ok, String message, String detail) {
    }

    private final MailSettingsStore store;
    private final Mailer mailer;
    private final MailGuard guard;
    private final SecretBox secrets;
    private final AuditLog audit;

    MailSettingsController(MailSettingsStore store, Mailer mailer, MailGuard guard, SecretBox secrets, AuditLog audit) {
        this.store = store;
        this.mailer = mailer;
        this.guard = guard;
        this.secrets = secrets;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("@perm.has('admin:company')")
    View get() {
        return view();
    }

    @PutMapping
    @PreAuthorize("@perm.has('admin:company')")
    @Transactional
    View save(@RequestBody Input in, @AuthenticationPrincipal Jwt jwt) {
        MailSettingsStore.Input valid = validate(in);
        UUID actor = UUID.fromString(jwt.getSubject());
        store.save(actor, valid);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("enabled", valid.enabled());
        after.put("host", valid.host());
        after.put("port", valid.port());
        after.put("security", valid.security());
        after.put("username", String.valueOf(valid.username()));
        after.put("fromEmail", valid.fromEmail());
        after.put("password", in.password() == null ? "kept" : in.password().isEmpty() ? "removed" : "changed");
        audit.record(actor, "MAIL_SETTINGS_SAVED", "tenant_mail_settings", null, after);
        return view();
    }

    /** Back to the default (the platform's server, or the log). */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('admin:company')")
    @Transactional
    void remove(@AuthenticationPrincipal Jwt jwt) {
        store.delete();
        audit.record(UUID.fromString(jwt.getSubject()), "MAIL_SETTINGS_REMOVED", "tenant_mail_settings", null, Map.of());
    }

    @PostMapping("/test")
    @PreAuthorize("@perm.has('admin:company')")
    TestResult test(@RequestBody TestInput in, @AuthenticationPrincipal Jwt jwt) {
        if (in == null || in.settings() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "FIELDS_REQUIRED", "Fill in the server details first.");
        MailSettingsStore.Input s = validate(in.settings());
        String to = guard.email("to", in.to() == null || in.to().isBlank() ? jwt.getClaimAsString("email") : in.to(), true);
        String password = in.settings().password() != null && !in.settings().password().isEmpty()
            ? in.settings().password() : store.savedPassword().orElse(null);
        MailServer server = new MailServer(s.host(), s.port(), s.security(), s.username(), password, s.fromName(), s.fromEmail(), s.replyTo());
        UUID actor = UUID.fromString(jwt.getSubject());
        TestResult result;
        try {
            mailer.sendVia(server, to, "Test email from HRMS", """
                Hello,

                This is a test from Company → Email in HRMS. If you're reading it, emails from HRMS can be sent through
                %s:%d (%s).
                """.formatted(s.host(), s.port(), s.security()));
            result = new TestResult(true, "Sent to " + to + ". Check that inbox (and its spam folder).", null);
        } catch (ApiException e) {
            throw e; // a host the guard refused: show it on the field
        } catch (RuntimeException e) {
            result = explain(e);
        }
        // Remember the outcome only when the test used the saved server.
        TestResult outcome = result;
        store.find().filter(saved -> saved.host().equals(s.host()) && saved.port() == s.port())
            .ifPresent(saved -> store.recordTest(outcome.ok(), outcome.ok() ? null : outcome.message()));
        audit.record(actor, "MAIL_SETTINGS_TESTED", "tenant_mail_settings", null, Map.of("host", s.host(), "ok", outcome.ok()));
        return outcome;
    }

    // ---------- helpers ----------

    private View view() {
        return new View(store.find().orElse(null), mailer.currentRoute(), secrets.ready(), guard.allowsPrivateHosts());
    }

    private MailSettingsStore.Input validate(Input in) {
        if (in == null) throw new ApiException(HttpStatus.BAD_REQUEST, "FIELDS_REQUIRED", "Fill in the server details.");
        String username = in.username() == null || in.username().isBlank() ? null : in.username().trim();
        if (username != null && username.length() > 254) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MAIL_SETTING", "The username is too long.");
        if (in.password() != null && in.password().length() > 500) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MAIL_SETTING", "The password is too long.");
        String fromName = in.fromName() == null || in.fromName().isBlank() ? null : in.fromName().trim();
        if (fromName != null && (fromName.length() > 100 || fromName.matches(".*[\\r\\n<>\"].*"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MAIL_SETTING", "Use a plain sender name of up to 100 characters.")
                .with("fields", Map.of("fromName", "Use a plain sender name of up to 100 characters."));
        }
        return new MailSettingsStore.Input(in.enabled() == null || in.enabled(), guard.host(in.host()), guard.port(in.port()),
            guard.security(in.security()), username, in.password(), fromName,
            guard.email("fromEmail", in.fromEmail(), true), guard.email("replyTo", in.replyTo(), false));
    }

    /** Turns a JavaMail failure into something an admin can act on, keeping the technical reason as detail. */
    private static TestResult explain(RuntimeException e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String detail = root.getClass().getSimpleName() + ": " + String.valueOf(root.getMessage());
        String message;
        if (e instanceof MailAuthenticationException || root instanceof AuthenticationFailedException) {
            message = "The server refused the username or password. Check them; some providers need an app password.";
        } else if (root instanceof UnknownHostException) {
            message = "Couldn't find that server. Check the server name.";
        } else if (root instanceof SocketTimeoutException) {
            message = "The server didn't answer in time. Check the port, and that the server accepts connections from us.";
        } else if (root instanceof ConnectException) {
            message = "Couldn't connect to that server and port. Check the port and security setting.";
        } else if (root instanceof SSLException) {
            message = "The secure connection failed. Try the other security option (STARTTLS on 587, SSL/TLS on 465).";
        } else if (e instanceof MailException) {
            message = "The server didn't accept the email. Check the sender address is allowed on this server.";
        } else {
            message = "Sending failed.";
        }
        return new TestResult(false, message, detail.length() > 500 ? detail.substring(0, 500) : detail);
    }
}
