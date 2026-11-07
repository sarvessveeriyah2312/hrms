package com.hrms.notification;

import com.hrms.shared.mail.Mailer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sends each in-app notification on by email and push too, per the recipient's preferences (email and push are on
 * unless they switched them off for that kind). Both go out only after the action's transaction commits; a failed push
 * is logged, never fails the action. Push needs a device the person registered (the mobile app does it after sign-in).
 */
@Component
class NotificationDelivery {

    private static final Logger log = LoggerFactory.getLogger(NotificationDelivery.class);

    private final JdbcClient jdbc;
    private final Mailer mailer;
    private final PushSender push;
    private final String webBase;

    NotificationDelivery(JdbcClient jdbc, Mailer mailer, PushSender push, @Value("${hrms.web.base-url:http://localhost:5173}") String webBase) {
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.push = push;
        this.webBase = webBase.replaceAll("/+$", "");
    }

    private record Recipient(UUID userId, UUID tenantId, String email, Boolean emailOn, Boolean pushOn) {
    }

    void deliver(Collection<UUID> employeeIds, Notifications.Message m) {
        List<Recipient> recipients = jdbc.sql("""
                select u.id as user_id, e.tenant_id, u.email::text as email, p.email as email_on, p.push as push_on
                from employee e
                join app_user u on u.id = e.user_id and u.status = 'ACTIVE'
                left join notification_preference p on p.user_id = u.id and p.kind = :kind
                where e.id in (:ids)""")
            .param("kind", m.kind().name()).param("ids", employeeIds).query(Recipient.class).list();
        for (Recipient r : recipients) {
            if (!Boolean.FALSE.equals(r.emailOn()) && r.email() != null && !r.email().isBlank()) {
                mailer.send(r.tenantId(), r.email(), m.title(), emailBody(m));
            }
            if (!Boolean.FALSE.equals(r.pushOn())) {
                List<String> tokens = jdbc.sql("select push_token from device where user_id = :user and push_token is not null")
                    .param("user", r.userId()).query(String.class).list();
                for (String token : tokens) {
                    afterCommit(() -> {
                        try {
                            push.send(new PushSender.PushMessage(token, m.title(), m.body() == null ? "" : m.body(), m.kind().name(),
                                m.link(), Map.of("tone", m.tone() == null ? "blue" : m.tone())));
                        } catch (RuntimeException e) {
                            log.warn("Push to a device failed: {}", e.getMessage());
                        }
                    });
                }
            }
        }
    }

    String emailBody(Notifications.Message m) {
        StringBuilder b = new StringBuilder(m.title()).append("\n\n");
        if (m.body() != null && !m.body().isBlank()) b.append(m.body()).append("\n\n");
        if (m.link() != null) b.append("Open it: ").append(webBase).append(m.link()).append("\n\n");
        b.append("—\nYou get this email because of your notification settings. Change them: ")
            .append(webBase).append("/app/account/notifications\n");
        return b.toString();
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }
}
