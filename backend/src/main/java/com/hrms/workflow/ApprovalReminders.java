package com.hrms.workflow;

import com.hrms.notification.Notifications;
import com.hrms.shared.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every few minutes, for every company: reminds approvers of steps that are past due (once when they become overdue,
 * then once a day, up to {@value #MAX_REMINDERS} times), and tells the requester's backup approver once when they can
 * step in on a manager or person step, because the approver is away or the step is overdue.
 */
@Component
class ApprovalReminders {

    static final int MAX_REMINDERS = 3;
    private static final Duration REPEAT = Duration.ofHours(24);
    private static final Logger log = LoggerFactory.getLogger(ApprovalReminders.class);

    record Due(UUID stepId, UUID instanceId, int level, UUID assigneeId, String pool, UUID poolRoleId, String kind, Instant dueAt,
               Instant remindedAt, int reminderCount, Instant backupAlertedAt, String subjectType, UUID subjectId, String module,
               UUID requesterId, Instant createdAt) {
    }

    private final JdbcClient jdbc;
    private final WorkflowService workflow;
    private final Notifications notifications;
    private final ObjectProvider<ApproverAvailability> availability;
    private final TransactionTemplate tx;

    ApprovalReminders(JdbcClient jdbc, WorkflowService workflow, Notifications notifications,
                      ObjectProvider<ApproverAvailability> availability, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.workflow = workflow;
        this.notifications = notifications;
        this.availability = availability;
        this.tx = new TransactionTemplate(transactions);
    }

    @Scheduled(initialDelayString = "${hrms.workflow.reminders.initial-delay:PT1M}", fixedDelayString = "${hrms.workflow.reminders.every:PT15M}")
    void run() {
        List<UUID> tenants = jdbc.sql("select id from tenant where status = 'ACTIVE'").query(UUID.class).list(); // tenant has no RLS
        int sent = 0;
        for (UUID tenant : tenants) {
            TenantContext.set(tenant);
            try {
                Integer n = tx.execute(s -> remind(Instant.now()));
                sent += n == null ? 0 : n;
            } catch (RuntimeException e) {
                log.warn("Approval reminders failed for a company: {}", e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
        if (sent > 0) log.info("Sent {} approval reminder(s)", sent);
    }

    /** One company's reminders (the tenant is already bound). Returns how many notifications went out. */
    int remind(Instant now) {
        List<Due> steps = jdbc.sql("""
                select s.id as step_id, s.instance_id, s.level, s.assignee_id, s.pool, s.pool_role_id, s.kind, s.due_at,
                       s.reminded_at, s.reminder_count, s.backup_alerted_at, i.subject_type, i.subject_id, i.module,
                       i.requester_id, i.created_at
                from workflow_step s join workflow_instance i on i.id = s.instance_id
                where s.state = 'ACTIVE' and i.state = 'PENDING'
                  and ((s.due_at < :now and s.reminder_count < :max)
                       or (s.backup_alerted_at is null and s.kind in ('MANAGER', 'PERSON') and s.assignee_id is not null))
                for update of s skip locked""")
            .param("now", java.sql.Timestamp.from(now)).param("max", MAX_REMINDERS).query(Due.class).list();
        int sent = 0;
        LocalDate today = LocalDate.now();
        for (Due d : steps) {
            boolean overdue = d.dueAt() != null && d.dueAt().isBefore(now);
            if (overdue && shouldRemind(d, now)) {
                sent += remindApprovers(d);
                jdbc.sql("update workflow_step set reminded_at = :now, reminder_count = reminder_count + 1 where id = :id")
                    .param("now", java.sql.Timestamp.from(now)).param("id", d.stepId()).update();
            }
            if (d.backupAlertedAt() == null && d.assigneeId() != null && ("MANAGER".equals(d.kind()) || "PERSON".equals(d.kind()))) {
                ApproverAvailability a = availability.getIfAvailable();
                boolean away = a != null && a.isAway(d.assigneeId(), today);
                if (overdue || away) {
                    sent += alertBackup(d, away);
                    jdbc.sql("update workflow_step set backup_alerted_at = :now where id = :id")
                        .param("now", java.sql.Timestamp.from(now)).param("id", d.stepId()).update();
                }
            }
        }
        return sent;
    }

    static boolean shouldRemind(Due d, Instant now) {
        if (d.reminderCount() >= MAX_REMINDERS) return false;
        return d.remindedAt() == null || d.remindedAt().plus(REPEAT).isBefore(now);
    }

    private int remindApprovers(Due d) {
        WorkflowService.Instance in = new WorkflowService.Instance(d.instanceId(), d.subjectType(), d.subjectId(), "PENDING", d.module(),
            d.requesterId(), d.createdAt());
        WorkflowService.Step step = new WorkflowService.Step(d.stepId(), d.instanceId(), d.level(), d.assigneeId(), d.pool(), d.poolRoleId(),
            d.kind(), "ACTIVE", d.dueAt());
        Set<UUID> to = workflow.waitingRecipients(in, step);
        if (to.isEmpty()) return 0;
        ApprovalSubject.Summary summary = workflow.subject(d.subjectType()).summarize(d.subjectId());
        notifications.toEmployees(to, new Notifications.Message(Notifications.Kind.APPROVAL_OVERDUE,
            "Overdue: " + requester(d) + " is waiting for " + lowerFirst(summary.title()),
            summary.detail(), "/app/approvals?open=" + d.instanceId(), "orange"));
        return to.size();
    }

    private int alertBackup(Due d, boolean away) {
        UUID backup = jdbc.sql("select backup_approver_id from employee where id = :id").param("id", d.requesterId())
            .query(UUID.class).optional().orElse(null);
        if (backup == null || backup.equals(d.requesterId()) || backup.equals(d.assigneeId())) return 0;
        ApprovalSubject.Summary summary = workflow.subject(d.subjectType()).summarize(d.subjectId());
        String approver = jdbc.sql("select full_name from employee where id = :id").param("id", d.assigneeId())
            .query(String.class).optional().orElse("The approver");
        notifications.toEmployee(backup, new Notifications.Message(Notifications.Kind.APPROVAL_BACKUP,
            "You can approve " + requester(d) + "’s " + lowerFirst(summary.title()),
            approver + (away ? " is away today" : " hasn’t acted and it’s overdue") + ", and you’re the backup approver.",
            "/app/approvals?open=" + d.instanceId(), "orange"));
        return 1;
    }

    private String requester(Due d) {
        return jdbc.sql("select full_name from employee where id = :id").param("id", d.requesterId()).query(String.class)
            .optional().orElse("Someone");
    }

    private static String lowerFirst(String s) {
        return s == null || s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
