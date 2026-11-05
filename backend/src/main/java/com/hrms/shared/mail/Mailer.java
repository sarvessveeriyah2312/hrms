package com.hrms.shared.mail;

import com.hrms.shared.tenancy.TenantContext;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Sends plain-text email for a company, through the first of:
 * <ol>
 *   <li>the company's own SMTP server (Company → Email), when it has one switched on;</li>
 *   <li>the platform's server ({@code spring.mail.host}, from MAIL_HOST);</li>
 *   <li>the log (local development), so links in the email can still be followed.</li>
 * </ol>
 * Inside a transaction, mail goes out only after it commits, so a request that fails sends nothing.
 * Sending failures are logged, not thrown: an email is never the reason a request fails.
 */
@Component
public class Mailer {

    private static final Logger log = LoggerFactory.getLogger(Mailer.class);
    private static final int TIMEOUT_MS = 10_000;

    /** Where a message went (or would go): the company's server, the platform's, or the log. */
    public enum Route { COMPANY, PLATFORM, LOG }

    private final ObjectProvider<JavaMailSender> platform;
    private final String platformFrom;
    private final String platformHost;
    private final MailSettingsStore settings;
    private final MailGuard guard;
    private final TransactionTemplate lookup;

    Mailer(ObjectProvider<JavaMailSender> platform, @Value("${hrms.mail.from}") String platformFrom,
           @Value("${spring.mail.host:}") String platformHost, MailSettingsStore settings, MailGuard guard,
           PlatformTransactionManager transactions) {
        this.platform = platform;
        this.platformFrom = platformFrom;
        this.platformHost = platformHost;
        this.settings = settings;
        this.guard = guard;
        // Reads the company's settings on its own connection, bound to that company, whatever the caller is doing.
        this.lookup = new TransactionTemplate(transactions);
        this.lookup.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.lookup.setReadOnly(true);
    }

    /** Sends (after commit, if in a transaction) for the given company; {@code tenantId} may be null. */
    public void send(UUID tenantId, String to, String subject, String body) {
        Optional<MailServer> server = companyServer(tenantId);
        Runnable deliver = () -> deliver(server, to, subject, body);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliver.run();
                }
            });
        } else {
            deliver.run();
        }
    }

    /** Which route the current company's mail takes right now (for the settings page). */
    public Route currentRoute() {
        if (settings.activeServer().isPresent()) return Route.COMPANY;
        return platformReady() ? Route.PLATFORM : Route.LOG;
    }

    /** Sends one message through the given server straight away, throwing on failure (for "Send test email"). */
    public void sendVia(MailServer server, String to, String subject, String body) {
        guard.reachable(server.host());
        JavaMailSenderImpl smtp = new JavaMailSenderImpl();
        smtp.setHost(server.host());
        smtp.setPort(server.port());
        smtp.setDefaultEncoding("UTF-8");
        Properties p = smtp.getJavaMailProperties();
        boolean auth = StringUtils.hasText(server.username());
        if (auth) {
            smtp.setUsername(server.username());
            smtp.setPassword(server.password());
        }
        p.put("mail.smtp.auth", String.valueOf(auth));
        p.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MS));
        p.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MS));
        p.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MS));
        switch (server.security()) {
            case "STARTTLS" -> {
                p.put("mail.smtp.starttls.enable", "true");
                p.put("mail.smtp.starttls.required", "true");
                p.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            case "SSL" -> {
                p.put("mail.smtp.ssl.enable", "true");
                p.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            default -> { }
        }
        smtp.send(mime -> fill(mime, server, to, subject, body));
    }

    // ---------- internals ----------

    private Optional<MailServer> companyServer(UUID tenantId) {
        if (tenantId == null) return Optional.empty();
        Optional<UUID> previous = TenantContext.current();
        TenantContext.set(tenantId);
        try {
            return lookup.execute(status -> settings.activeServer());
        } catch (RuntimeException e) {
            log.warn("Couldn't read the mail settings of company {}: {}", tenantId, e.getMessage());
            return Optional.empty();
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    private void deliver(Optional<MailServer> server, String to, String subject, String body) {
        if (server.isPresent()) {
            try {
                sendVia(server.get(), to, subject, body);
                return;
            } catch (RuntimeException e) {
                // Their server is set up wrong or down: say so, and don't silently send through ours instead.
                log.warn("Couldn't send \"{}\" to {} through the company's SMTP server {}: {}", subject, to, server.get().host(), e.getMessage());
                return;
            }
        }
        JavaMailSender smtp = platformReady() ? platform.getIfAvailable() : null;
        if (smtp == null) {
            log.info("""
                Email (not sent: no SMTP server configured)
                  To: {}
                  Subject: {}
                {}""", to, subject, body.indent(2));
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(platformFrom);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        try {
            smtp.send(message);
        } catch (MailException e) {
            log.warn("Couldn't send email \"{}\" to {}: {}", subject, to, e.getMessage());
        }
    }

    private boolean platformReady() {
        return StringUtils.hasText(platformHost) && platform.getIfAvailable() != null;
    }

    private static void fill(MimeMessage mime, MailServer server, String to, String subject, String body) throws MessagingException {
        MimeMessageHelper m = new MimeMessageHelper(mime, "UTF-8");
        try {
            m.setFrom(new InternetAddress(server.fromEmail(), StringUtils.hasText(server.fromName()) ? server.fromName() : null, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new MailPreparationException(e);
        }
        if (StringUtils.hasText(server.replyTo())) m.setReplyTo(server.replyTo());
        m.setTo(to);
        m.setSubject(subject);
        m.setText(body);
    }
}
