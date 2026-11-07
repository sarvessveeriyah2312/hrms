package com.hrms.notification;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Sends in-app notifications, and the same by email and push per each recipient's preferences
 * ({@link NotificationDelivery}). Call it inside the transaction of the action it describes, so a rolled-back action
 * leaves no notification behind (email and push go out after commit). Recipients are employees; people without a login
 * are skipped.
 */
@Service
public class Notifications {

    public enum Kind {
        APPROVAL_WAITING, REQUEST_APPROVED, REQUEST_REJECTED, REQUEST_RETURNED, REQUEST_CANCELLED, LEAVE_RECORDED, CLAIM_PAID, SCHEDULE_PUBLISHED, PAYSLIP_PUBLISHED, TAX_FORM_PUBLISHED,
        APPROVAL_OVERDUE, APPROVAL_BACKUP, ANNOUNCEMENT, POLICY_PUBLISHED, LETTER_ISSUED, GRIEVANCE_UPDATE
    }

    public record Message(Kind kind, String title, String body, String link, String tone) {
    }

    private final JdbcClient jdbc;
    private final NotificationDelivery delivery;

    Notifications(JdbcClient jdbc, NotificationDelivery delivery) {
        this.jdbc = jdbc;
        this.delivery = delivery;
    }

    public void toEmployee(UUID employeeId, Message m) {
        if (employeeId != null) toEmployees(Set.of(employeeId), m);
    }

    public void toEmployees(Collection<UUID> employeeIds, Message m) {
        Set<UUID> ids = new LinkedHashSet<>(employeeIds);
        ids.remove(null);
        if (ids.isEmpty()) return;
        jdbc.sql("""
                insert into notification (tenant_id, recipient_user_id, kind, title, body, link, tone)
                select e.tenant_id, e.user_id, :kind, :title, :body, :link, :tone
                from employee e where e.id in (:ids) and e.user_id is not null""")
            .param("kind", m.kind().name()).param("title", m.title()).param("body", m.body())
            .param("link", m.link()).param("tone", m.tone() == null ? "blue" : m.tone()).param("ids", ids)
            .update();
        delivery.deliver(ids, m);
    }

    /** Everyone whose roles give this permission at company-wide scope (e.g. HR approvers), minus some people. */
    public Set<UUID> employeesWithCompanyWide(String permission, Collection<UUID> except) {
        Set<UUID> out = new LinkedHashSet<>(jdbc.sql("""
                select distinct e.id from employee e
                join app_user u on u.id = e.user_id and u.status = 'ACTIVE'
                join user_role ur on ur.user_id = u.id
                join role_permission rp on rp.role_id = ur.role_id
                where rp.permission = :perm and rp.data_scope = 'TENANT' and e.status = 'ACTIVE'""")
            .param("perm", permission).query(UUID.class).list());
        out.removeAll(except);
        return out;
    }

    // ---------- reading (NotificationsController) ----------

    record Item(UUID id, String kind, String title, String body, String link, String tone,
                java.time.Instant createdAt, java.time.Instant readAt) {
    }

    java.util.List<Item> forUser(UUID userId, int limit) {
        return jdbc.sql("""
                select id, kind, title, body, link, tone, created_at, read_at from notification
                where recipient_user_id = :user order by created_at desc limit :limit""")
            .param("user", userId).param("limit", limit).query(Item.class).list();
    }

    int unread(UUID userId) {
        return jdbc.sql("select count(*) from notification where recipient_user_id = :user and read_at is null")
            .param("user", userId).query(Integer.class).single();
    }

    /** Marks one as read; only the recipient can. Returns false when it isn't theirs. */
    boolean markRead(UUID userId, UUID id) {
        return jdbc.sql("update notification set read_at = coalesce(read_at, now()) where id = :id and recipient_user_id = :user")
            .param("id", id).param("user", userId).update() > 0;
    }

    int markAllRead(UUID userId) {
        return jdbc.sql("update notification set read_at = now() where recipient_user_id = :user and read_at is null")
            .param("user", userId).update();
    }
}
