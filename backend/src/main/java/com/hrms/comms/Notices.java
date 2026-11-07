package com.hrms.comms;

import com.hrms.files.FileStore;
import com.hrms.notification.Notifications;
import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notices: announcements and HR memos (comms:post) and company policies (comms:policies). Each goes to an audience
 * (everyone, some departments or some legal entities), may carry attachments, and is a draft until published, when its
 * audience is notified. Opening one is a read receipt; policies that need it are acknowledged ("read and understood").
 * Policies have versions: publishing a change makes a new version, and everyone acknowledges it again.
 */
@Service
class Notices {

    static final Set<String> KINDS = Set.of("ANNOUNCEMENT", "MEMO", "POLICY");
    static final Set<String> AUDIENCES = Set.of("ALL", "DEPARTMENTS", "ENTITIES");
    static final int MAX_ATTACHMENTS = 5;

    record Input(String kind, String title, String summary, String body, String audience, List<UUID> audienceIds,
                 List<UUID> attachmentIds, Boolean pinned, Boolean requiresAck) {
    }

    /** readAt / acknowledgedAt: the viewer's own receipt for the current version. Counts are for managers. */
    record Notice(UUID id, String kind, String title, String summary, String body, String audience, List<UUID> audienceIds,
                  List<String> audienceNames, List<FileStore.FileMeta> attachments, boolean pinned, boolean requiresAck, int version,
                  String status, Instant publishedAt, String publishedBy, Instant createdAt, Instant updatedAt, Instant readAt,
                  Instant acknowledgedAt, Integer audienceSize, Integer readCount, Integer acknowledgedCount) {
    }

    record Receipt(UUID employeeId, String employeeNo, String name, String department, Instant readAt, Instant acknowledgedAt) {
    }

    /** Everyone in the audience, read or not. */
    record Receipts(UUID noticeId, int version, boolean requiresAck, List<Receipt> people) {
    }

    record Counts(int unread, int toAcknowledge) {
    }

    private record Row(UUID id, String kind, String title, String summary, String body, String audience, String audienceIds,
                       String attachmentIds, boolean pinned, boolean requiresAck, int version, String status, Instant publishedAt,
                       String publishedBy, Instant createdAt, Instant updatedAt, Instant readAt, Instant acknowledgedAt) {
    }

    /** True when employee alias {@code e} is in notice alias {@code n}'s audience. */
    static final String IN_AUDIENCE = """
            (n.audience = 'ALL' or (n.audience = 'DEPARTMENTS' and e.department_id = any(n.audience_ids))
             or (n.audience = 'ENTITIES' and e.entity_id = any(n.audience_ids)))""";

    private static final String SELECT = """
            select n.id, n.kind, n.title, n.summary, n.body, n.audience, n.audience_ids::text as audience_ids,
                   n.attachment_ids::text as attachment_ids, n.pinned, n.requires_ack,
                   n.version, n.status, n.published_at, coalesce(pe.full_name, pu.email::text) as published_by, n.created_at,
                   n.updated_at, r.read_at, r.acknowledged_at
            from notice n
            left join app_user pu on pu.id = n.published_by
            left join employee pe on pe.user_id = n.published_by
            left join notice_receipt r on r.notice_id = n.id and r.version = n.version and r.employee_id = :me
            """;

    private final JdbcClient jdbc;
    private final FileStore files;
    private final Notifications notifications;
    private final AuditLog audit;

    Notices(JdbcClient jdbc, FileStore files, Notifications notifications, AuditLog audit) {
        this.jdbc = jdbc;
        this.files = files;
        this.notifications = notifications;
        this.audit = audit;
    }

    // ---------- reading ----------

    /** Published notices in your audience, pinned first, newest first. */
    @Transactional(readOnly = true)
    List<Notice> mine(UUID me, String kind) {
        if (me == null) return List.of();
        return jdbc.sql(SELECT + """
                join employee e on e.id = :me
                where n.status = 'PUBLISHED' and (cast(:kind as text) is null or n.kind = cast(:kind as text)) and %s
                order by n.pinned desc, n.published_at desc limit 200""".formatted(IN_AUDIENCE))
            .param("me", me).param("kind", kind).query(Row.class).list().stream().map(r -> toNotice(r, false)).toList();
    }

    /** Everything of the kinds you manage, drafts and archived too, with read counts. */
    @Transactional(readOnly = true)
    List<Notice> managed(UUID me, Set<String> kinds) {
        if (kinds.isEmpty()) return List.of();
        return jdbc.sql(SELECT + "where n.kind in (:kinds) order by (n.status = 'DRAFT') desc, coalesce(n.published_at, n.created_at) desc limit 300")
            .param("me", me).param("kinds", kinds).query(Row.class).list().stream().map(r -> toNotice(r, true)).toList();
    }

    /** One notice. A reader in the audience gets a read receipt; managers of its kind see any. */
    @Transactional
    Notice open(UUID me, UUID id, Set<String> managedKinds) {
        Row r = row(me, id).orElseThrow(Notices::notFound);
        boolean manager = managedKinds.contains(r.kind());
        boolean reader = "PUBLISHED".equals(r.status()) && inAudience(me, id);
        if (!manager && !reader) throw notFound();
        if (reader) {
            jdbc.sql("""
                    insert into notice_receipt (tenant_id, notice_id, version, employee_id) values (:tenant, :id, :version, :me)
                    on conflict do nothing""")
                .param("tenant", TenantContext.require()).param("id", id).param("version", r.version()).param("me", me).update();
            r = row(me, id).orElseThrow();
        }
        return toNotice(r, manager);
    }

    @Transactional
    Notice acknowledge(UUID me, UUID id) {
        Row r = row(me, id).orElseThrow(Notices::notFound);
        if (!"PUBLISHED".equals(r.status()) || !inAudience(me, id)) throw notFound();
        if (!r.requiresAck()) throw new ApiException(HttpStatus.CONFLICT, "NO_ACK_NEEDED", "This notice doesn't need acknowledging.");
        jdbc.sql("""
                insert into notice_receipt (tenant_id, notice_id, version, employee_id, acknowledged_at)
                values (:tenant, :id, :version, :me, now())
                on conflict (notice_id, version, employee_id) do update set acknowledged_at = coalesce(notice_receipt.acknowledged_at, now())""")
            .param("tenant", TenantContext.require()).param("id", id).param("version", r.version()).param("me", me).update();
        return toNotice(row(me, id).orElseThrow(), false);
    }

    @Transactional(readOnly = true)
    Counts counts(UUID me) {
        if (me == null) return new Counts(0, 0);
        record C(int unread, int toAcknowledge) {
        }
        C c = jdbc.sql("""
                select count(*) filter (where n.kind <> 'POLICY' and r.read_at is null)::int as unread,
                       count(*) filter (where n.requires_ack and r.acknowledged_at is null)::int as to_acknowledge
                from notice n join employee e on e.id = :me
                left join notice_receipt r on r.notice_id = n.id and r.version = n.version and r.employee_id = :me
                where n.status = 'PUBLISHED' and %s""".formatted(IN_AUDIENCE))
            .param("me", me).query(C.class).single();
        return new Counts(c.unread(), c.toAcknowledge());
    }

    @Transactional(readOnly = true)
    Receipts receipts(UUID id) {
        Row r = row(null, id).orElseThrow(Notices::notFound);
        List<Receipt> people = jdbc.sql("""
                select e.id as employee_id, e.employee_no, e.full_name as name, d.name as department, x.read_at, x.acknowledged_at
                from notice n
                join employee e on e.status = 'ACTIVE' and %s
                left join department d on d.id = e.department_id
                left join notice_receipt x on x.notice_id = n.id and x.version = n.version and x.employee_id = e.id
                where n.id = :id
                order by (x.acknowledged_at is null and n.requires_ack) desc, (x.read_at is null) desc, e.full_name
                limit 2000""".formatted(IN_AUDIENCE))
            .param("id", id).query(Receipt.class).list();
        return new Receipts(id, r.version(), r.requiresAck(), people);
    }

    @Transactional(readOnly = true)
    FileStore.StoredFile attachment(UUID me, UUID id, UUID fileId, Set<String> managedKinds) {
        Row r = row(me, id).orElseThrow(Notices::notFound);
        boolean allowed = managedKinds.contains(r.kind()) || ("PUBLISHED".equals(r.status()) && inAudience(me, id));
        if (!allowed || !uuids(r.attachmentIds()).contains(fileId)) throw notFound();
        return files.read(fileId).orElseThrow(Notices::notFound);
    }

    // ---------- writing ----------

    @Transactional
    Notice create(UUID me, UUID user, Input in) {
        Input c = check(in, user, List.of());
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into notice (id, tenant_id, kind, title, summary, body, audience, audience_ids, attachment_ids, pinned, requires_ack,
                                    created_by)
                values (:id, :tenant, :kind, :title, :summary, :body, :audience, cast(:audienceIds as uuid[]), cast(:attachments as uuid[]),
                        :pinned, :ack, :user)""")
            .param("id", id).param("tenant", TenantContext.require()).param("kind", c.kind()).param("title", c.title())
            .param("summary", c.summary()).param("body", c.body()).param("audience", c.audience())
            .param("audienceIds", pgArray(c.audienceIds())).param("attachments", pgArray(c.attachmentIds()))
            .param("pinned", c.pinned()).param("ack", c.requiresAck()).param("user", user).update();
        audit.record(user, "NOTICE_CREATED", "notice", id, Map.of("kind", c.kind(), "title", c.title()));
        return toNotice(row(me, id).orElseThrow(), true);
    }

    @Transactional
    Notice update(UUID me, UUID user, UUID id, Input in) {
        Row existing = row(me, id).orElseThrow(Notices::notFound);
        if ("ARCHIVED".equals(existing.status())) throw new ApiException(HttpStatus.CONFLICT, "ARCHIVED", "Archived notices can't be changed.");
        Input c = check(new Input(existing.kind(), in.title(), in.summary(), in.body(), in.audience(), in.audienceIds(), in.attachmentIds(),
            in.pinned(), in.requiresAck()), user, uuids(existing.attachmentIds()));
        jdbc.sql("""
                update notice set title = :title, summary = :summary, body = :body, audience = :audience, audience_ids = cast(:audienceIds as uuid[]),
                       attachment_ids = cast(:attachments as uuid[]), pinned = :pinned, requires_ack = :ack, updated_at = now(), row_version = row_version + 1
                where id = :id""")
            .param("title", c.title()).param("summary", c.summary()).param("body", c.body()).param("audience", c.audience())
            .param("audienceIds", pgArray(c.audienceIds())).param("attachments", pgArray(c.attachmentIds()))
            .param("pinned", c.pinned()).param("ack", c.requiresAck()).param("id", id).update();
        audit.record(user, "NOTICE_UPDATED", "notice", id, Map.of("title", c.title()));
        return toNotice(row(me, id).orElseThrow(), true);
    }

    /**
     * Publishes a draft (version 1) and notifies its audience. A published policy published again becomes a new version:
     * everyone reads (and acknowledges) it again.
     */
    @Transactional
    Notice publish(UUID me, UUID user, UUID id) {
        Row r = row(me, id).orElseThrow(Notices::notFound);
        boolean newVersion = "PUBLISHED".equals(r.status());
        if ("ARCHIVED".equals(r.status())) throw new ApiException(HttpStatus.CONFLICT, "ARCHIVED", "Archived notices can't be published.");
        if (newVersion && !"POLICY".equals(r.kind())) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_PUBLISHED", "This is already published. Edit it to change what people see.");
        }
        if (r.body() == null || r.body().isBlank()) {
            if (uuids(r.attachmentIds()).isEmpty()) throw bad("BODY_REQUIRED", "Write the notice, or attach a document, before publishing.");
        }
        jdbc.sql("""
                update notice set status = 'PUBLISHED', version = version + 1, published_at = now(), published_by = :user,
                       updated_at = now(), row_version = row_version + 1
                where id = :id""")
            .param("user", user).param("id", id).update();
        Set<UUID> audience = new LinkedHashSet<>(jdbc.sql("""
                select e.id from notice n join employee e on e.status = 'ACTIVE' and %s where n.id = :id""".formatted(IN_AUDIENCE))
            .param("id", id).query(UUID.class).list());
        if (me != null) audience.remove(me);
        String what = switch (r.kind()) {
            case "MEMO" -> "HR memo";
            case "POLICY" -> newVersion ? "Updated policy" : "New policy";
            default -> "Announcement";
        };
        String body = r.requiresAck() ? "Please read and acknowledge it." : r.summary() == null ? "" : r.summary();
        notifications.toEmployees(audience, new Notifications.Message(
            "POLICY".equals(r.kind()) ? Notifications.Kind.POLICY_PUBLISHED : Notifications.Kind.ANNOUNCEMENT,
            what + ": " + r.title(), body, "/app/news?notice=" + id, "POLICY".equals(r.kind()) ? "purple" : "blue"));
        audit.record(user, newVersion ? "NOTICE_NEW_VERSION" : "NOTICE_PUBLISHED", "notice", id,
            Map.of("kind", r.kind(), "version", r.version() + 1, "audience", audience.size()));
        return toNotice(row(me, id).orElseThrow(), true);
    }

    @Transactional
    void archive(UUID user, UUID id) {
        Row r = row(null, id).orElseThrow(Notices::notFound);
        if (!"PUBLISHED".equals(r.status())) throw new ApiException(HttpStatus.CONFLICT, "NOT_PUBLISHED", "Only published notices can be archived.");
        jdbc.sql("update notice set status = 'ARCHIVED', pinned = false, updated_at = now(), row_version = row_version + 1 where id = :id")
            .param("id", id).update();
        audit.record(user, "NOTICE_ARCHIVED", "notice", id, Map.of());
    }

    @Transactional
    void delete(UUID user, UUID id) {
        int n = jdbc.sql("delete from notice where id = :id and status = 'DRAFT'").param("id", id).update();
        if (n == 0) {
            if (row(null, id).isEmpty()) throw notFound();
            throw new ApiException(HttpStatus.CONFLICT, "PUBLISHED", "Published notices are archived, not deleted.");
        }
        audit.record(user, "NOTICE_DELETED", "notice", id, Map.of());
    }

    String kindOf(UUID id) {
        return jdbc.sql("select kind from notice where id = :id").param("id", id).query(String.class).optional().orElseThrow(Notices::notFound);
    }

    // ---------- helpers ----------

    private Input check(Input in, UUID user, List<UUID> alreadyAttached) {
        if (in == null) throw bad("NOTICE_REQUIRED", "Fill in the notice.");
        String kind = in.kind() == null ? "ANNOUNCEMENT" : in.kind();
        if (!KINDS.contains(kind)) throw bad("BAD_KIND", "Choose announcement, memo or policy.");
        String title = trim(in.title());
        if (title == null) throw bad("TITLE_REQUIRED", "Give it a title.");
        if (title.length() > 150) throw bad("TITLE_TOO_LONG", "Keep the title under 150 characters.");
        String summary = trim(in.summary());
        if (summary != null && summary.length() > 300) throw bad("SUMMARY_TOO_LONG", "Keep the summary under 300 characters.");
        String body = in.body() == null ? "" : in.body().strip();
        if (body.length() > 50_000) throw bad("BODY_TOO_LONG", "Keep the text under 50,000 characters.");
        String audience = in.audience() == null ? "ALL" : in.audience();
        if (!AUDIENCES.contains(audience)) throw bad("BAD_AUDIENCE", "Choose who it's for.");
        List<UUID> ids = in.audienceIds() == null ? List.of() : in.audienceIds().stream().filter(Objects::nonNull).distinct().toList();
        if ("ALL".equals(audience)) ids = List.of();
        else if (ids.isEmpty()) throw bad("AUDIENCE_REQUIRED", "Choose at least one " + ("DEPARTMENTS".equals(audience) ? "department." : "company."));
        else {
            String table = "DEPARTMENTS".equals(audience) ? "department" : "legal_entity";
            int found = jdbc.sql("select count(*) from " + table + " where id in (:ids)").param("ids", ids).query(Integer.class).single();
            if (found != ids.size()) throw bad("BAD_AUDIENCE", "Some of the chosen groups don't exist.");
        }
        List<UUID> attachments = in.attachmentIds() == null ? List.of() : in.attachmentIds().stream().filter(Objects::nonNull).distinct().toList();
        if (attachments.size() > MAX_ATTACHMENTS) throw bad("TOO_MANY_ATTACHMENTS", "Attach up to " + MAX_ATTACHMENTS + " documents.");
        for (UUID f : attachments) {
            if (alreadyAttached.contains(f)) continue;
            files.meta(f).filter(m -> m.ownerUserId().equals(user))
                .orElseThrow(() -> bad("FILE_NOT_FOUND", "Upload the attachments first."));
        }
        boolean ack = "POLICY".equals(kind) ? !Boolean.FALSE.equals(in.requiresAck()) : Boolean.TRUE.equals(in.requiresAck());
        return new Input(kind, title, summary, body, audience, ids, attachments, Boolean.TRUE.equals(in.pinned()), ack);
    }

    private boolean inAudience(UUID me, UUID id) {
        if (me == null) return false;
        return jdbc.sql("select exists (select 1 from notice n join employee e on e.id = :me where n.id = :id and %s)".formatted(IN_AUDIENCE))
            .param("me", me).param("id", id).query(Boolean.class).single();
    }

    private Optional<Row> row(UUID me, UUID id) {
        return jdbc.sql(SELECT + "where n.id = :id").param("me", me).param("id", id).query(Row.class).optional();
    }

    private Notice toNotice(Row r, boolean manager) {
        List<UUID> audienceIds = uuids(r.audienceIds());
        List<String> names = audienceIds.isEmpty() ? List.of() : jdbc.sql(
                "select name from " + ("DEPARTMENTS".equals(r.audience()) ? "department" : "legal_entity") + " where id in (:ids) order by name")
            .param("ids", audienceIds).query(String.class).list();
        List<FileStore.FileMeta> attachments = new ArrayList<>();
        for (UUID f : uuids(r.attachmentIds())) files.meta(f).ifPresent(attachments::add);
        Integer size = null;
        Integer read = null;
        Integer acked = null;
        if (manager && !"DRAFT".equals(r.status())) {
            record C(int size, int read, int acked) {
            }
            C c = jdbc.sql("""
                    select count(*)::int as size, count(x.read_at)::int as read, count(x.acknowledged_at)::int as acked
                    from notice n join employee e on e.status = 'ACTIVE' and %s
                    left join notice_receipt x on x.notice_id = n.id and x.version = n.version and x.employee_id = e.id
                    where n.id = :id""".formatted(IN_AUDIENCE)).param("id", r.id()).query(C.class).single();
            size = c.size();
            read = c.read();
            acked = c.acked();
        }
        return new Notice(r.id(), r.kind(), r.title(), r.summary(), r.body(), r.audience(), audienceIds, names, attachments, r.pinned(),
            r.requiresAck(), r.version(), r.status(), r.publishedAt(), r.publishedBy(), r.createdAt(), r.updatedAt(), r.readAt(),
            r.acknowledgedAt(), size, read, acked);
    }

    /** "{a,b}" (a uuid[] as text) → the ids. */
    static List<UUID> uuids(String array) {
        if (array == null || array.length() < 3) return List.of();
        return Arrays.stream(array.substring(1, array.length() - 1).split(",")).map(String::trim).filter(x -> !x.isEmpty())
            .map(UUID::fromString).toList();
    }

    static String pgArray(List<UUID> ids) {
        return "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOTICE_NOT_FOUND", "That notice doesn't exist.");
    }

    static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
