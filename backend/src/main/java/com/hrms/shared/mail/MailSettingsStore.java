package com.hrms.shared.mail;

import com.hrms.shared.tenancy.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** A company's own SMTP settings (table tenant_mail_settings, one row per company, under RLS). */
@Component
public class MailSettingsStore {

    /** What the settings page shows. The password itself never leaves the server: only whether one is saved. */
    public record Settings(boolean enabled, String host, int port, String security, String username, boolean passwordSet,
                           String fromName, String fromEmail, String replyTo, Instant updatedAt, String updatedBy,
                           Instant lastTestAt, Boolean lastTestOk, String lastTestError) {
    }

    /** The fields an admin saves. {@code password}: null keeps the saved one, "" removes it. */
    public record Input(boolean enabled, String host, int port, String security, String username, String password,
                        String fromName, String fromEmail, String replyTo) {
    }

    private final JdbcClient jdbc;
    private final SecretBox secrets;

    MailSettingsStore(JdbcClient jdbc, SecretBox secrets) {
        this.jdbc = jdbc;
        this.secrets = secrets;
    }

    /** The current company's settings, if it has any. */
    public Optional<Settings> find() {
        return jdbc.sql("""
                select s.enabled, s.host, s.port, s.security, s.username, (s.password_enc is not null) as password_set,
                       s.from_name, s.from_email, s.reply_to, s.updated_at,
                       coalesce(e.full_name, u.email::text) as updated_by, s.last_test_at, s.last_test_ok, s.last_test_error
                from tenant_mail_settings s
                left join app_user u on u.id = s.updated_by
                left join employee e on e.user_id = s.updated_by
                where s.tenant_id = :tenant""")
            .param("tenant", TenantContext.require()).query(Settings.class).optional();
    }

    /** The saved password, decrypted (for sending, or for a test that leaves the password field empty). */
    public Optional<String> savedPassword() {
        return jdbc.sql("select password_enc from tenant_mail_settings where tenant_id = :tenant")
            .param("tenant", TenantContext.require()).query(String.class).optional().map(secrets::open);
    }

    /** The server to send the current company's mail through, when it has one switched on. */
    public Optional<MailServer> activeServer() {
        record Row(String host, int port, String security, String username, String passwordEnc, String fromName,
                   String fromEmail, String replyTo) {
        }
        return jdbc.sql("""
                select host, port, security, username, password_enc, from_name, from_email, reply_to
                from tenant_mail_settings where tenant_id = :tenant and enabled""")
            .param("tenant", TenantContext.require()).query(Row.class).optional()
            .map(r -> new MailServer(r.host(), r.port(), r.security(), r.username(), secrets.open(r.passwordEnc()),
                r.fromName(), r.fromEmail(), r.replyTo()));
    }

    public void save(UUID actor, Input in) {
        UUID tenant = TenantContext.require();
        String sealed = in.password() == null || in.password().isEmpty() ? null : secrets.seal(in.password());
        jdbc.sql("""
                insert into tenant_mail_settings (tenant_id, enabled, host, port, security, username, password_enc, from_name,
                                                  from_email, reply_to, updated_at, updated_by)
                values (:tenant, :enabled, :host, :port, :security, :username, :password, :fromName, :fromEmail, :replyTo, now(), :actor)
                on conflict (tenant_id) do update set
                    enabled = excluded.enabled, host = excluded.host, port = excluded.port, security = excluded.security,
                    username = excluded.username,
                    password_enc = case when :keep then tenant_mail_settings.password_enc else excluded.password_enc end,
                    from_name = excluded.from_name, from_email = excluded.from_email, reply_to = excluded.reply_to,
                    updated_at = now(), updated_by = excluded.updated_by""")
            .param("tenant", tenant).param("enabled", in.enabled()).param("host", in.host()).param("port", in.port())
            .param("security", in.security()).param("username", in.username()).param("password", sealed)
            .param("keep", in.password() == null).param("fromName", in.fromName()).param("fromEmail", in.fromEmail())
            .param("replyTo", in.replyTo()).param("actor", actor).update();
    }

    public void delete() {
        jdbc.sql("delete from tenant_mail_settings where tenant_id = :tenant").param("tenant", TenantContext.require()).update();
    }

    public void recordTest(boolean ok, String error) {
        jdbc.sql("""
                update tenant_mail_settings set last_test_at = now(), last_test_ok = :ok, last_test_error = :error
                where tenant_id = :tenant""")
            .param("ok", ok).param("error", error).param("tenant", TenantContext.require()).update();
    }
}
