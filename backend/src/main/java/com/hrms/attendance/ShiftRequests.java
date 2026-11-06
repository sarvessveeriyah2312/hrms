package com.hrms.attendance;

import com.hrms.notification.Notifications;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import com.hrms.workflow.ApprovalFlows;
import com.hrms.workflow.WorkflowService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changing who works a published shift. GIVE: hand my shift to a colleague. SWAP: my shift for one of theirs. CLAIM:
 * take an open shift at one of my sites. Give and swap need the colleague to accept first; then every kind goes through
 * approval workflows (subject SHIFT_CHANGE). Approving moves the shifts (working and published copies alike) and tells
 * everyone involved. Nothing in a locked month, or in the past, can change.
 */
@Service
class ShiftRequests {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    static final int MAX_REASON = 300;
    /** How far ahead open shifts are shown. */
    static final int OPEN_DAYS = 28;

    /** A published shift, with who's on it (null: open). */
    record ShiftView(UUID id, UUID siteId, String site, UUID employeeId, String employeeName, LocalDate date, LocalTime startTime,
                     LocalTime endTime, String code, String tone) {
    }

    record Input(String kind, UUID shiftId, UUID colleagueId, UUID colleagueShiftId, String reason) {
    }

    /**
     * status: COLLEAGUE (waiting for the colleague), PENDING (in approval), APPROVED, REJECTED, RETURNED, DECLINED,
     * CANCELLED. mine: you asked; forMe: you're the colleague and it's waiting for you.
     */
    record Request(UUID id, String kind, String status, UUID requesterId, String requesterName, ShiftView shift, UUID colleagueId,
                   String colleagueName, ShiftView colleagueShift, String reason, String returnedComment, Instant createdAt,
                   WorkflowService.PendingWith pendingWith, boolean mine, boolean forMe) {
    }

    record Detail(Request request, List<WorkflowService.TimelineStep> timeline) {
    }

    /** For asking: colleagues at the shift's site, and their shifts there that week (to swap for). */
    record Options(ShiftView shift, List<Colleague> colleagues) {
    }

    record Colleague(UUID id, String fullName, List<ShiftView> shifts) {
    }

    /** An open shift at one of your sites; requested: you've already asked for it. */
    record OpenShift(ShiftView shift, boolean requested) {
    }

    private record Row(UUID id, String kind, String status, UUID requesterId, UUID shiftId, UUID colleagueId, UUID colleagueShiftId,
                       String reason, String returnedComment, Instant createdAt, UUID workflowId) {
    }

    private static final String SHIFT_SELECT = """
            select p.id, p.site_id, s.name as site, p.employee_id, e.full_name as employee_name, p.work_date as date,
                   p.start_time, p.end_time, p.code, p.tone
            from published_shift p join site s on s.id = p.site_id left join employee e on e.id = p.employee_id
            """;

    private final JdbcClient jdbc;
    private final WorkflowService workflow;
    private final ApprovalFlows flows;
    private final PeriodLocks locks;
    private final Notifications notifications;
    private final AttendanceFacts facts;
    private final Clock clock;

    @Autowired
    ShiftRequests(JdbcClient jdbc, WorkflowService workflow, ApprovalFlows flows, PeriodLocks locks, Notifications notifications,
                  AttendanceFacts facts) {
        this(jdbc, workflow, flows, locks, notifications, facts, Clock.systemUTC());
    }

    ShiftRequests(JdbcClient jdbc, WorkflowService workflow, ApprovalFlows flows, PeriodLocks locks, Notifications notifications,
                  AttendanceFacts facts, Clock clock) {
        this.jdbc = jdbc;
        this.workflow = workflow;
        this.flows = flows;
        this.locks = locks;
        this.notifications = notifications;
        this.facts = facts;
        this.clock = clock;
    }

    // ---------- reading ----------

    Optional<ShiftView> shift(UUID id) {
        return id == null ? Optional.empty() : jdbc.sql(SHIFT_SELECT + "where p.id = :id").param("id", id).query(ShiftView.class).optional();
    }

    /** Requests you made and ones waiting for you as the colleague (newest first, the last 100). */
    @Transactional(readOnly = true)
    List<Request> mine(UUID me) {
        return jdbc.sql("""
                select id, kind, status, requester_id, shift_id, colleague_id, colleague_shift_id, reason, returned_comment, created_at, workflow_id
                from shift_request where (requester_id = :me or (colleague_id = :me and status <> 'CANCELLED'))
                order by created_at desc limit 100""")
            .param("me", me).query(Row.class).list().stream().map(r -> view(r, me)).toList();
    }

    Optional<Request> find(UUID id) {
        return row(id).map(r -> view(r, null));
    }

    @Transactional(readOnly = true)
    Detail detail(UUID id, UUID me) {
        Row r = row(id).orElseThrow(ShiftRequests::notFound);
        return new Detail(view(r, me), r.workflowId() == null ? List.of() : workflow.timeline(r.workflowId()));
    }

    @Transactional(readOnly = true)
    Options options(UUID me, UUID shiftId) {
        ShiftView s = shift(shiftId).orElseThrow(ShiftRequests::shiftGone);
        if (!me.equals(s.employeeId())) throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_SHIFT", "You can only offer your own shifts.");
        LocalDate monday = RosterService.monday(s.date());
        List<ShiftView> theirs = jdbc.sql(SHIFT_SELECT + """
                where p.site_id = :site and p.work_date between :from and :to and p.employee_id is not null and p.employee_id <> :me
                  and p.work_date >= :today order by p.work_date, p.start_time""")
            .param("site", s.siteId()).param("from", monday).param("to", monday.plusDays(6)).param("me", me).param("today", today(me))
            .query(ShiftView.class).list();
        List<Colleague> colleagues = jdbc.sql("""
                select e.id, e.full_name from employee_site es join employee e on e.id = es.employee_id
                where es.site_id = :site and e.id <> :me and e.status = 'ACTIVE' order by e.full_name""")
            .param("site", s.siteId()).param("me", me)
            .query((rs, i) -> {
                UUID id = rs.getObject("id", UUID.class);
                return new Colleague(id, rs.getString("full_name"), theirs.stream().filter(x -> id.equals(x.employeeId())).toList());
            }).list();
        return new Options(s, colleagues);
    }

    /** Open shifts at your sites from today, for the next four weeks. */
    @Transactional(readOnly = true)
    List<OpenShift> openShifts(UUID me) {
        LocalDate today = today(me);
        List<UUID> asked = jdbc.sql("select shift_id from shift_request where requester_id = :me and kind = 'CLAIM' and status in ('PENDING', 'RETURNED')")
            .param("me", me).query(UUID.class).list();
        return jdbc.sql(SHIFT_SELECT + """
                where p.employee_id is null and p.work_date between :from and :to
                  and p.site_id in (select site_id from employee_site where employee_id = :me)
                order by p.work_date, p.start_time""")
            .param("from", today).param("to", today.plusDays(OPEN_DAYS)).param("me", me).query(ShiftView.class).list()
            .stream().map(s -> new OpenShift(s, asked.contains(s.id()))).toList();
    }

    // ---------- asking, answering, withdrawing ----------

    @Transactional
    Request ask(UUID me, Input in) {
        if (in == null || in.kind() == null) throw ShiftTemplates.bad("KIND_REQUIRED", "Choose to give, swap or claim a shift.");
        String kind = in.kind().toUpperCase();
        ShiftView s = shift(in.shiftId()).orElseThrow(ShiftRequests::shiftGone);
        requireFuture(me, s);
        String reason = in.reason() == null || in.reason().isBlank() ? null : in.reason().trim();
        if (reason != null && reason.length() > MAX_REASON) throw ShiftTemplates.bad("REASON_TOO_LONG", "Keep the reason under " + MAX_REASON + " characters.");
        UUID colleague = null;
        UUID colleagueShift = null;
        switch (kind) {
            case "CLAIM" -> {
                if (s.employeeId() != null) throw new ApiException(HttpStatus.CONFLICT, "NOT_OPEN", "Someone already has that shift.");
                boolean member = jdbc.sql("select exists (select 1 from employee_site where employee_id = :me and site_id = :site)")
                    .param("me", me).param("site", s.siteId()).query(Boolean.class).single();
                if (!member) throw new ApiException(HttpStatus.FORBIDDEN, "NOT_AT_SITE", "You can only claim open shifts at your own sites.");
                requireFree(me, s.date(), s.startTime(), s.endTime(), null);
            }
            case "GIVE", "SWAP" -> {
                if (!me.equals(s.employeeId())) throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_SHIFT", "You can only offer your own shifts.");
                if (in.colleagueId() == null || in.colleagueId().equals(me)) throw ShiftTemplates.bad("COLLEAGUE_REQUIRED", "Choose a colleague.");
                colleague = in.colleagueId();
                boolean atSite = jdbc.sql("select exists (select 1 from employee_site where employee_id = :c and site_id = :site)")
                    .param("c", colleague).param("site", s.siteId()).query(Boolean.class).single();
                if (!atSite) throw ShiftTemplates.bad("NOT_AT_SITE", "Choose a colleague who works at " + s.site() + ".");
                if ("SWAP".equals(kind)) {
                    ShiftView theirs = shift(in.colleagueShiftId()).orElseThrow(ShiftRequests::shiftGone);
                    if (!colleague.equals(theirs.employeeId())) throw ShiftTemplates.bad("NOT_THEIR_SHIFT", "Choose one of your colleague's shifts.");
                    requireFuture(me, theirs);
                    colleagueShift = theirs.id();
                }
            }
            default -> throw ShiftTemplates.bad("BAD_KIND", "Choose to give, swap or claim a shift.");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into shift_request (id, tenant_id, kind, requester_id, shift_id, colleague_id, colleague_shift_id, reason, status)
                    values (:id, :tenant, :kind, :me, :shift, :colleague, :cshift, :reason, :status)""")
                .param("id", id).param("tenant", TenantContext.require()).param("kind", kind).param("me", me).param("shift", s.id())
                .param("colleague", colleague).param("cshift", colleagueShift).param("reason", reason)
                .param("status", "CLAIM".equals(kind) ? "PENDING" : "COLLEAGUE").update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_OPEN", "You've already asked about that shift.");
        }
        if ("CLAIM".equals(kind)) {
            startApproval(id, me);
        } else {
            String name = facts.person(me).map(AttendanceFacts.Emp::fullName).orElse("A colleague");
            notifications.toEmployee(colleague, new Notifications.Message(Notifications.Kind.APPROVAL_WAITING,
                "GIVE".equals(kind) ? name + " offers you a shift" : name + " asks to swap shifts",
                s.date().format(DAY) + " " + s.startTime() + "–" + s.endTime() + " at " + s.site() + ". Accept or decline on My schedule.",
                "/app/attendance?tab=schedule&request=" + id, "blue"));
        }
        return find(id).orElseThrow();
    }

    /** The colleague accepts: it goes for approval. */
    @Transactional
    Request accept(UUID me, UUID id) {
        Row r = row(id).orElseThrow(ShiftRequests::notFound);
        if (!me.equals(r.colleagueId())) throw notFound();
        if (!"COLLEAGUE".equals(r.status())) throw new ApiException(HttpStatus.CONFLICT, "NOT_WAITING", "This request isn't waiting for you any more.");
        ShiftView s = shift(r.shiftId()).orElseThrow(ShiftRequests::shiftGone);
        requireFuture(me, s);
        ShiftView theirs = shift(r.colleagueShiftId()).orElse(null);
        requireFree(me, s.date(), s.startTime(), s.endTime(), theirs == null ? null : theirs.id());
        jdbc.sql("update shift_request set status = 'PENDING' where id = :id").param("id", id).update();
        startApproval(id, r.requesterId());
        return find(id).orElseThrow();
    }

    @Transactional
    void decline(UUID me, UUID id) {
        Row r = row(id).orElseThrow(ShiftRequests::notFound);
        if (!me.equals(r.colleagueId())) throw notFound();
        if (!"COLLEAGUE".equals(r.status())) throw new ApiException(HttpStatus.CONFLICT, "NOT_WAITING", "This request isn't waiting for you any more.");
        jdbc.sql("update shift_request set status = 'DECLINED', decided_at = now() where id = :id").param("id", id).update();
        String name = facts.person(me).map(AttendanceFacts.Emp::fullName).orElse("Your colleague");
        notifications.toEmployee(r.requesterId(), new Notifications.Message(Notifications.Kind.REQUEST_REJECTED,
            name + " declined your shift request", null, "/app/attendance?tab=schedule&request=" + id, "red"));
    }

    @Transactional
    void cancel(UUID me, UUID id) {
        Row r = row(id).orElseThrow(ShiftRequests::notFound);
        if (!me.equals(r.requesterId())) throw notFound();
        switch (r.status()) {
            case "PENDING" -> workflow.cancel(r.workflowId(), me);   // marks it CANCELLED through ShiftChangeApprovals
            case "COLLEAGUE", "RETURNED" -> jdbc.sql("update shift_request set status = 'CANCELLED', decided_at = now() where id = :id")
                .param("id", id).update();
            default -> throw new ApiException(HttpStatus.CONFLICT, "CANNOT_CANCEL", "This request has already been decided.");
        }
    }

    private void startApproval(UUID id, UUID requester) {
        UUID department = jdbc.sql("select department_id from employee where id = :id").param("id", requester)
            .query(UUID.class).optional().orElse(null);
        WorkflowService.Plan plan = flows.match(ShiftChangeApprovals.SUBJECT, null, department, null)
            .orElseGet(() -> new WorkflowService.ChainSpec(1, false).toPlan());
        UUID flow = workflow.start(ShiftChangeApprovals.SUBJECT, id, requester, plan);
        jdbc.sql("update shift_request set workflow_id = :flow where id = :id").param("flow", flow).param("id", id).update();
    }

    // ---------- applying (on approval) ----------

    /**
     * Moves the shifts, checking nothing changed since asking: the shifts are still published with the same people (or
     * still open), in an open month, and nobody ends up double-booked. Other claims for the same open shift are withdrawn.
     */
    void apply(UUID id) {
        Row r = row(id).orElseThrow(ShiftRequests::notFound);
        ShiftView s = shift(r.shiftId()).orElseThrow(ShiftRequests::shiftGone);
        locks.requireOpen(s.date(), "roster");
        switch (r.kind()) {
            case "CLAIM" -> {
                if (s.employeeId() != null) throw new ApiException(HttpStatus.CONFLICT, "NOT_OPEN", "Someone else already has that shift.");
                requireFree(r.requesterId(), s.date(), s.startTime(), s.endTime(), null);
                move(s.id(), r.requesterId());
                // Other people's claims for it can't happen now.
                jdbc.sql("select workflow_id, requester_id from shift_request where shift_id = :shift and kind = 'CLAIM' and status = 'PENDING' and id <> :id")
                    .param("shift", s.id()).param("id", id)
                    .query((rs, i) -> {
                        workflow.cancel(rs.getObject("workflow_id", UUID.class), rs.getObject("requester_id", UUID.class));
                        return null;
                    }).list();
            }
            case "GIVE" -> {
                if (!r.requesterId().equals(s.employeeId())) throw changed();
                requireFree(r.colleagueId(), s.date(), s.startTime(), s.endTime(), null);
                move(s.id(), r.colleagueId());
            }
            case "SWAP" -> {
                ShiftView theirs = shift(r.colleagueShiftId()).orElseThrow(ShiftRequests::shiftGone);
                if (!r.requesterId().equals(s.employeeId()) || !r.colleagueId().equals(theirs.employeeId())) throw changed();
                locks.requireOpen(theirs.date(), "roster");
                requireFree(r.colleagueId(), s.date(), s.startTime(), s.endTime(), theirs.id());
                requireFree(r.requesterId(), theirs.date(), theirs.startTime(), theirs.endTime(), s.id());
                move(s.id(), r.colleagueId());
                move(theirs.id(), r.requesterId());
            }
            default -> throw changed();
        }
        if (r.colleagueId() != null) {
            notifications.toEmployee(r.colleagueId(), new Notifications.Message(Notifications.Kind.SCHEDULE_PUBLISHED,
                "Your schedule changed", "A shift change you accepted was approved.", "/app/attendance?tab=schedule&week=" + RosterService.monday(s.date()), "green"));
        }
    }

    /** Puts a shift on someone, in both the plan and what's published. */
    private void move(UUID shiftId, UUID to) {
        jdbc.sql("update published_shift set employee_id = :to where id = :id").param("to", to).param("id", shiftId).update();
        jdbc.sql("update roster_shift set employee_id = :to, updated_at = now() where id = :id").param("to", to).param("id", shiftId).update();
    }

    // ---------- helpers ----------

    /** Shifts that already started can't change hands. */
    private void requireFuture(UUID me, ShiftView s) {
        LocalDate today = today(me);
        if (s.date().isBefore(today)) throw new ApiException(HttpStatus.CONFLICT, "SHIFT_PAST", "That shift is in the past.");
        locks.requireOpen(s.date(), "roster");
    }

    /** No overlap and at most 3 shifts that day for the person taking it on (leaving out one shift they'd give up). */
    private void requireFree(UUID who, LocalDate date, LocalTime start, LocalTime end, UUID except) {
        record Other(UUID id, LocalTime startTime, LocalTime endTime) {
        }
        List<Other> sameDay = jdbc.sql("select id, start_time, end_time from published_shift where employee_id = :who and work_date = :date")
            .param("who", who).param("date", date).query(Other.class).list()
            .stream().filter(o -> !o.id().equals(except)).toList();
        String name = facts.person(who).map(AttendanceFacts.Emp::fullName).orElse("They");
        if (sameDay.size() >= RosterChecks.MAX_PER_DAY) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_SHIFTS", name + " already has " + RosterChecks.MAX_PER_DAY + " shifts that day.");
        }
        for (Other o : sameDay) {
            if (start.isBefore(o.endTime()) && o.startTime().isBefore(end)) {
                throw new ApiException(HttpStatus.CONFLICT, "SHIFT_OVERLAPS", name + " already works " + o.startTime() + "–" + o.endTime() + " that day.");
            }
        }
    }

    private LocalDate today(UUID me) {
        return LocalDate.now(clock.withZone(facts.zoneOf(me)));
    }

    private Optional<Row> row(UUID id) {
        return jdbc.sql("""
                select id, kind, status, requester_id, shift_id, colleague_id, colleague_shift_id, reason, returned_comment, created_at, workflow_id
                from shift_request where id = :id""").param("id", id).query(Row.class).optional();
    }

    private Request view(Row r, UUID me) {
        var pending = "PENDING".equals(r.status()) && r.workflowId() != null ? workflow.pendingWith(r.workflowId()).orElse(null) : null;
        return new Request(r.id(), r.kind(), r.status(), r.requesterId(), name(r.requesterId()), shift(r.shiftId()).orElse(null),
            r.colleagueId(), name(r.colleagueId()), shift(r.colleagueShiftId()).orElse(null), r.reason(), r.returnedComment(),
            r.createdAt(), pending, r.requesterId().equals(me), "COLLEAGUE".equals(r.status()) && r.colleagueId() != null && r.colleagueId().equals(me));
    }

    private String name(UUID employeeId) {
        return employeeId == null ? null : facts.person(employeeId).map(AttendanceFacts.Emp::fullName).orElse(null);
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "SHIFT_REQUEST_NOT_FOUND", "That shift request doesn't exist.");
    }

    private static ApiException shiftGone() {
        return new ApiException(HttpStatus.CONFLICT, "SHIFT_GONE", "That shift isn't on the published roster any more.");
    }

    private static ApiException changed() {
        return new ApiException(HttpStatus.CONFLICT, "SHIFT_CHANGED", "The roster changed since this was asked: the shift isn't theirs any more.");
    }
}
