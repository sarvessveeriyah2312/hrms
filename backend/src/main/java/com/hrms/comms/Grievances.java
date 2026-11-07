package com.hrms.comms;

import com.hrms.files.FileStore;
import com.hrms.notification.Notifications;
import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grievances and feedback. Anyone raises one (optionally anonymous to the people handling it), with an attachment; it
 * goes to everyone who handles grievances (comms:grievances, company-wide). Handlers reply, take or assign it and set
 * its status; the person replies, closes it, and can reopen it within {@value #REOPEN_DAYS} days. Each side is notified
 * when the other acts. Nobody handles their own grievance.
 */
@Service
class Grievances {

    static final Set<String> CATEGORIES = Set.of("WORKPLACE", "HARASSMENT", "PAY", "MANAGEMENT", "FACILITIES", "SUGGESTION", "OTHER");
    static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED");
    static final int REOPEN_DAYS = 30;
    static final String PERMISSION = "comms:grievances";

    record Input(String category, String subject, String body, UUID fileId, Boolean anonymous) {
    }

    record ReplyInput(String body, UUID fileId) {
    }

    /** raisedBy is "Anonymous" to handlers when the person chose that. role: how the viewer sees it (SUBMITTER or HANDLER). */
    record Case(UUID id, String category, String subject, boolean anonymous, String status, String raisedBy, String raisedByNo,
                UUID assignedTo, String assignedName, Instant createdAt, Instant updatedAt, Instant closedAt, String role,
                boolean canReopen, int messages) {
    }

    record Message(UUID id, String author, String authorRole, boolean mine, String body, FileStore.FileMeta file, Instant createdAt) {
    }

    record Detail(Case grievance, List<Message> messages) {
    }

    record Handler(UUID employeeId, String name) {
    }

    private record Row(UUID id, UUID employeeId, String category, String subject, boolean anonymous, String status, String raisedBy,
                       String raisedByNo, UUID assignedTo, String assignedName, Instant createdAt, Instant updatedAt, Instant closedAt,
                       int messages) {
    }

    private static final String SELECT = """
            select g.id, g.employee_id, g.category, g.subject, g.anonymous, g.status, e.full_name as raised_by, e.employee_no as raised_by_no,
                   g.assigned_to, a.full_name as assigned_name, g.created_at, g.updated_at, g.closed_at,
                   (select count(*) from grievance_message m where m.grievance_id = g.id and m.author_role <> 'SYSTEM')::int as messages
            from grievance g
            join employee e on e.id = g.employee_id
            left join employee a on a.id = g.assigned_to
            """;

    private final JdbcClient jdbc;
    private final FileStore files;
    private final Notifications notifications;
    private final AuditLog audit;

    Grievances(JdbcClient jdbc, FileStore files, Notifications notifications, AuditLog audit) {
        this.jdbc = jdbc;
        this.files = files;
        this.notifications = notifications;
        this.audit = audit;
    }

    // ---------- the person raising it ----------

    @Transactional
    Detail submit(UUID me, UUID user, Input in) {
        if (in == null) throw bad("GRIEVANCE_REQUIRED", "Fill in the form.");
        String category = in.category() == null ? "OTHER" : in.category();
        if (!CATEGORIES.contains(category)) throw bad("BAD_CATEGORY", "Choose what it's about.");
        String subject = text(in.subject(), 150, "SUBJECT", "the subject");
        String body = text(in.body(), 5000, "BODY", "what happened");
        checkFile(in.fileId(), user);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into grievance (id, tenant_id, employee_id, category, subject, anonymous)
                values (:id, :tenant, :me, :category, :subject, :anonymous)""")
            .param("id", id).param("tenant", TenantContext.require()).param("me", me).param("category", category)
            .param("subject", subject).param("anonymous", Boolean.TRUE.equals(in.anonymous())).update();
        message(id, me, "SUBMITTER", body, in.fileId());
        notifications.toEmployees(notifications.employeesWithCompanyWide(PERMISSION, Set.of(me)), new Notifications.Message(
            Notifications.Kind.GRIEVANCE_UPDATE, "New " + ("SUGGESTION".equals(category) ? "feedback" : "grievance") + ": " + subject,
            Boolean.TRUE.equals(in.anonymous()) ? "Raised anonymously" : "Raised by " + name(me), "/app/feedback?case=" + id, "orange"));
        audit.record(user, "GRIEVANCE_RAISED", "grievance", id, Map.of("category", category));
        return detail(me, id, false);
    }

    @Transactional(readOnly = true)
    List<Case> mine(UUID me) {
        return jdbc.sql(SELECT + "where g.employee_id = :me order by g.updated_at desc limit 200").param("me", me)
            .query(Row.class).list().stream().map(r -> toCase(r, me, false)).toList();
    }

    // ---------- handlers ----------

    @Transactional(readOnly = true)
    List<Case> all(UUID me, String status) {
        String s = status == null || status.isBlank() || "ALL".equals(status) ? null : status;
        if (s != null && !"ACTIVE".equals(s) && !STATUSES.contains(s)) throw bad("BAD_STATUS", "Unknown status.");
        String filter = s == null ? "true" : "ACTIVE".equals(s) ? "g.status in ('OPEN', 'IN_PROGRESS')" : "g.status = :status";
        return jdbc.sql(SELECT + "where " + filter + " order by (g.status = 'OPEN') desc, g.updated_at desc limit 500")
            .param("status", s == null ? "" : s).query(Row.class).list().stream().map(r -> toCase(r, me, true)).toList();
    }

    @Transactional(readOnly = true)
    List<Handler> handlers() {
        Set<UUID> ids = notifications.employeesWithCompanyWide(PERMISSION, Set.of());
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("select id as employee_id, full_name as name from employee where id in (:ids) order by full_name")
            .param("ids", ids).query(Handler.class).list();
    }

    // ---------- either side ----------

    /** The case and its thread, for the person who raised it or a handler (who never sees an anonymous person's name). */
    @Transactional(readOnly = true)
    Detail detail(UUID me, UUID id, boolean handler) {
        Row r = row(id).orElseThrow(Grievances::notFound);
        boolean owner = r.employeeId().equals(me);
        if (!owner && !handler) throw notFound();
        boolean asHandler = !owner;
        record M(UUID id, UUID authorId, String authorName, String authorRole, String body, UUID fileId, Instant createdAt) {
        }
        List<Message> messages = jdbc.sql("""
                select m.id, m.author_id, e.full_name as author_name, m.author_role, m.body, m.file_id, m.created_at
                from grievance_message m left join employee e on e.id = m.author_id
                where m.grievance_id = :id order by m.created_at""").param("id", id).query(M.class).list().stream()
            .map(m -> {
                boolean mine = me != null && me.equals(m.authorId());
                String author = switch (m.authorRole()) {
                    case "SYSTEM" -> "Update";
                    case "SUBMITTER" -> asHandler && r.anonymous() ? "Anonymous" : m.authorName();
                    default -> m.authorName() == null ? "HR" : m.authorName();
                };
                return new Message(m.id(), author, m.authorRole(), mine, m.body(), m.fileId() == null ? null : files.meta(m.fileId()).orElse(null),
                    m.createdAt());
            }).toList();
        return new Detail(toCase(r, me, asHandler), messages);
    }

    @Transactional
    Detail reply(UUID me, UUID user, UUID id, ReplyInput in, boolean handler) {
        Row r = row(id).orElseThrow(Grievances::notFound);
        boolean owner = r.employeeId().equals(me);
        if (!owner && !handler) throw notFound();
        if ("CLOSED".equals(r.status())) throw new ApiException(HttpStatus.CONFLICT, "CLOSED", "This is closed. Reopen it to reply.");
        String body = text(in == null ? null : in.body(), 5000, "REPLY", "your reply");
        UUID fileId = in == null ? null : in.fileId();
        checkFile(fileId, user);
        message(id, me, owner ? "SUBMITTER" : "HANDLER", body, fileId);
        if (owner) {
            jdbc.sql("update grievance set updated_at = now(), status = case when status = 'RESOLVED' then 'IN_PROGRESS' else status end, version = version + 1 where id = :id")
                .param("id", id).update();
            Set<UUID> to = r.assignedTo() != null ? Set.of(r.assignedTo()) : notifications.employeesWithCompanyWide(PERMISSION, Set.of(me));
            notifications.toEmployees(to, new Notifications.Message(Notifications.Kind.GRIEVANCE_UPDATE, "New reply: " + r.subject(),
                excerpt(body), "/app/feedback?case=" + id, "orange"));
        } else {
            jdbc.sql("""
                    update grievance set updated_at = now(), status = case when status = 'OPEN' then 'IN_PROGRESS' else status end,
                           assigned_to = coalesce(assigned_to, :me), version = version + 1
                    where id = :id""").param("me", me).param("id", id).update();
            notifications.toEmployee(r.employeeId(), new Notifications.Message(Notifications.Kind.GRIEVANCE_UPDATE,
                "HR replied: " + r.subject(), excerpt(body), "/app/feedback?case=" + id, "orange"));
        }
        return detail(me, id, handler);
    }

    /** Handlers: move it on (in progress, resolved, closed) with an optional note to the person. */
    @Transactional
    Detail setStatus(UUID me, UUID user, UUID id, String status, String note) {
        Row r = row(id).orElseThrow(Grievances::notFound);
        if (r.employeeId().equals(me)) throw new ApiException(HttpStatus.FORBIDDEN, "OWN_GRIEVANCE", "Someone else has to handle your own grievance.");
        if (!Set.of("IN_PROGRESS", "RESOLVED", "CLOSED").contains(status)) throw bad("BAD_STATUS", "Choose in progress, resolved or closed.");
        String n = note == null || note.isBlank() ? null : text(note, 2000, "NOTE", "the note");
        changeStatus(id, status, me);
        message(id, me, "SYSTEM", label(status) + (n == null ? "" : ": " + n), null);
        notifications.toEmployee(r.employeeId(), new Notifications.Message(Notifications.Kind.GRIEVANCE_UPDATE,
            r.subject() + " is now " + label(status).toLowerCase(), n == null ? "" : excerpt(n), "/app/feedback?case=" + id,
            "RESOLVED".equals(status) || "CLOSED".equals(status) ? "green" : "orange"));
        audit.record(user, "GRIEVANCE_STATUS", "grievance", id, Map.of("status", status));
        return detail(me, id, true);
    }

    /** Handlers: give it to a handler (or take it: assignee = yourself). */
    @Transactional
    Detail assign(UUID me, UUID user, UUID id, UUID assignee) {
        Row r = row(id).orElseThrow(Grievances::notFound);
        if (r.employeeId().equals(me)) throw new ApiException(HttpStatus.FORBIDDEN, "OWN_GRIEVANCE", "Someone else has to handle your own grievance.");
        if (assignee != null) {
            if (assignee.equals(r.employeeId())) throw bad("CANT_ASSIGN", "It can't go to the person who raised it.");
            if (!notifications.employeesWithCompanyWide(PERMISSION, Set.of()).contains(assignee)) {
                throw bad("NOT_A_HANDLER", "Choose someone who handles grievances.");
            }
        }
        jdbc.sql("update grievance set assigned_to = :a, updated_at = now(), version = version + 1 where id = :id")
            .param("a", assignee).param("id", id).update();
        if (assignee != null && !assignee.equals(me)) {
            notifications.toEmployee(assignee, new Notifications.Message(Notifications.Kind.GRIEVANCE_UPDATE,
                "Assigned to you: " + r.subject(), "By " + name(me), "/app/feedback?case=" + id, "orange"));
        }
        audit.record(user, "GRIEVANCE_ASSIGNED", "grievance", id, Map.of("to", assignee == null ? "nobody" : assignee.toString()));
        return detail(me, id, true);
    }

    /** The person closes their own (resolved to their satisfaction, or no longer needed). */
    @Transactional
    Detail close(UUID me, UUID id) {
        Row r = row(id).orElseThrow(Grievances::notFound);
        if (!r.employeeId().equals(me)) throw notFound();
        if ("CLOSED".equals(r.status())) throw new ApiException(HttpStatus.CONFLICT, "CLOSED", "It's already closed.");
        changeStatus(id, "CLOSED", me);
        message(id, me, "SYSTEM", "Closed by the person who raised it", null);
        if (r.assignedTo() != null) {
            notifications.toEmployee(r.assignedTo(), new Notifications.Message(Notifications.Kind.GRIEVANCE_UPDATE, "Closed: " + r.subject(),
                "Closed by the person who raised it", "/app/feedback?case=" + id, "green"));
        }
        return detail(me, id, false);
    }

    @Transactional
    Detail reopen(UUID me, UUID id, String reason) {
        Row r = row(id).orElseThrow(Grievances::notFound);
        if (!r.employeeId().equals(me)) throw notFound();
        if (!canReopen(r)) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_REOPEN", "Only something resolved or closed in the last " + REOPEN_DAYS + " days can be reopened. Raise a new one instead.");
        }
        String why = text(reason, 2000, "REASON", "why you're reopening it");
        jdbc.sql("update grievance set status = 'OPEN', closed_at = null, updated_at = now(), version = version + 1 where id = :id")
            .param("id", id).update();
        message(id, me, "SUBMITTER", why, null);
        message(id, me, "SYSTEM", "Reopened", null);
        Set<UUID> to = r.assignedTo() != null ? Set.of(r.assignedTo()) : notifications.employeesWithCompanyWide(PERMISSION, Set.of(me));
        notifications.toEmployees(to, new Notifications.Message(Notifications.Kind.GRIEVANCE_UPDATE, "Reopened: " + r.subject(),
            excerpt(why), "/app/feedback?case=" + id, "orange"));
        return detail(me, id, false);
    }

    @Transactional(readOnly = true)
    FileStore.StoredFile file(UUID me, UUID id, UUID fileId, boolean handler) {
        Row r = row(id).orElseThrow(Grievances::notFound);
        if (!r.employeeId().equals(me) && !handler) throw notFound();
        boolean inThread = jdbc.sql("select exists (select 1 from grievance_message where grievance_id = :id and file_id = :file)")
            .param("id", id).param("file", fileId).query(Boolean.class).single();
        if (!inThread) throw notFound();
        return files.read(fileId).orElseThrow(Grievances::notFound);
    }

    // ---------- helpers ----------

    private void changeStatus(UUID id, String status, UUID me) {
        jdbc.sql("""
                update grievance set status = :status, updated_at = now(), version = version + 1,
                       closed_at = case when :status in ('RESOLVED', 'CLOSED') then coalesce(closed_at, now()) else null end
                where id = :id""").param("status", status).param("id", id).update();
    }

    private void message(UUID grievance, UUID author, String role, String body, UUID fileId) {
        jdbc.sql("""
                insert into grievance_message (id, tenant_id, grievance_id, author_id, author_role, body, file_id)
                values (:id, :tenant, :g, :author, :role, :body, :file)""")
            .param("id", UUID.randomUUID()).param("tenant", TenantContext.require()).param("g", grievance).param("author", author)
            .param("role", role).param("body", body).param("file", fileId).update();
    }

    private void checkFile(UUID fileId, UUID user) {
        if (fileId == null) return;
        files.meta(fileId).filter(m -> m.ownerUserId().equals(user)).orElseThrow(() -> bad("FILE_NOT_FOUND", "Upload the attachment first."));
    }

    private Optional<Row> row(UUID id) {
        return jdbc.sql(SELECT + "where g.id = :id").param("id", id).query(Row.class).optional();
    }

    private Case toCase(Row r, UUID me, boolean asHandler) {
        boolean hide = asHandler && r.anonymous() && !r.employeeId().equals(me);
        return new Case(r.id(), r.category(), r.subject(), r.anonymous(), r.status(), hide ? "Anonymous" : r.raisedBy(),
            hide ? null : r.raisedByNo(), r.assignedTo(), r.assignedName(), r.createdAt(), r.updatedAt(), r.closedAt(),
            asHandler ? "HANDLER" : "SUBMITTER", !asHandler && canReopen(r), r.messages());
    }

    static boolean canReopen(Row r) {
        return ("CLOSED".equals(r.status()) || "RESOLVED".equals(r.status()))
            && (r.closedAt() == null || r.closedAt().isAfter(Instant.now().minus(Duration.ofDays(REOPEN_DAYS))));
    }

    private String name(UUID employee) {
        return jdbc.sql("select full_name from employee where id = :id").param("id", employee).query(String.class).optional().orElse("Someone");
    }

    private static String label(String status) {
        return switch (status) {
            case "IN_PROGRESS" -> "In progress";
            case "RESOLVED" -> "Resolved";
            case "CLOSED" -> "Closed";
            default -> "Open";
        };
    }

    private static String excerpt(String s) {
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() > 140 ? one.substring(0, 137) + "..." : one;
    }

    private static String text(String s, int max, String code, String what) {
        if (s == null || s.isBlank()) throw bad(code + "_REQUIRED", "Fill in " + what + ".");
        String t = s.strip();
        if (t.length() > max) throw bad(code + "_TOO_LONG", "Keep " + what + " under " + max + " characters.");
        return t;
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "GRIEVANCE_NOT_FOUND", "That doesn't exist.");
    }

    static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
