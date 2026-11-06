package com.hrms.attendance;

import com.hrms.attendance.AttendanceFacts.Emp;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import com.hrms.workflow.ApprovalFlows;
import com.hrms.workflow.WorkflowService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attendance corrections: someone asks for a day's first clock-in and/or last clock-out to be set to a time, with a
 * reason. The request goes through approval workflows (subject CORRECTION; the first matching workflow, else their
 * manager). Once approved, Today and timesheets use the corrected times ({@link WorkDay#corrected}); the clock-ins
 * themselves are never changed.
 */
@Service
public class CorrectionService {

    /** How far back a day can be corrected. */
    static final int MAX_AGE_DAYS = 60;
    static final int MAX_REASON = 500;

    record Input(LocalDate date, LocalTime clockIn, LocalTime clockOut, String reason) {
    }

    /** canCancel: waiting or sent back; canEdit: sent back (edit and resubmit). */
    record Correction(UUID id, UUID employeeId, String employeeName, String employeeNo, LocalDate date, LocalTime clockIn,
                      LocalTime clockOut, String reason, String status, String returnedComment, Instant createdAt,
                      Instant decidedAt, WorkflowService.PendingWith pendingWith, boolean canCancel, boolean canEdit) {
    }

    /** What the clock-ins showed that day, before any correction. */
    record Recorded(LocalTime firstIn, LocalTime lastOut, List<WorkDay.Session> sessions) {
    }

    record Detail(Correction correction, Recorded recorded, List<WorkflowService.TimelineStep> timeline) {
    }

    private record Row(UUID id, UUID employeeId, String employeeName, String employeeNo, LocalDate workDate,
                       LocalTime clockIn, LocalTime clockOut, String reason, String status, String returnedComment,
                       Instant createdAt, Instant decidedAt, UUID workflowId) {
    }

    private static final String SELECT = """
            select c.id, c.employee_id, e.full_name as employee_name, e.employee_no, c.work_date, c.clock_in, c.clock_out,
                   c.reason, c.status, c.returned_comment, c.created_at, c.decided_at, c.workflow_id
            from attendance_correction c join employee e on e.id = c.employee_id
            """;

    private final JdbcClient jdbc;
    private final AttendanceFacts facts;
    private final WorkflowService workflow;
    private final ApprovalFlows flows;
    private final PeriodLocks locks;
    private final Clock clock;

    @Autowired
    CorrectionService(JdbcClient jdbc, AttendanceFacts facts, WorkflowService workflow, ApprovalFlows flows, PeriodLocks locks) {
        this(jdbc, facts, workflow, flows, locks, Clock.systemUTC());
    }

    CorrectionService(JdbcClient jdbc, AttendanceFacts facts, WorkflowService workflow, ApprovalFlows flows, PeriodLocks locks,
                      Clock clock) {
        this.jdbc = jdbc;
        this.facts = facts;
        this.workflow = workflow;
        this.flows = flows;
        this.locks = locks;
        this.clock = clock;
    }

    // ---------- reading ----------

    /** Your own requests, newest day first (the last 100). */
    @Transactional(readOnly = true)
    List<Correction> mine(UUID employeeId) {
        return jdbc.sql(SELECT + "where c.employee_id = :emp and c.status <> 'CANCELLED' order by c.work_date desc, c.created_at desc limit 100")
            .param("emp", employeeId).query(Row.class).list().stream().map(this::toCorrection).toList();
    }

    Optional<Correction> find(UUID id) {
        return jdbc.sql(SELECT + "where c.id = :id").param("id", id).query(Row.class).optional().map(this::toCorrection);
    }

    @Transactional(readOnly = true)
    Detail detail(UUID id) {
        Row r = row(id);
        Emp e = facts.person(r.employeeId()).orElseThrow(CorrectionService::notFound);
        List<WorkDay.Clocking> clockings = AttendanceFacts.on(
                facts.punches(List.of(e.id()), r.workDate(), r.workDate()).get(e.id()), r.workDate(), e.zone()).stream()
            .map(p -> new WorkDay.Clocking(AttendanceFacts.local(p.at(), e.zone()), p.type())).toList();
        var day = WorkDay.of(new WorkDay.Input(r.workDate(), true, null, DayStatus.Hours.DEFAULT, clockings,
            r.workDate().plusDays(1).atStartOfDay()));
        return new Detail(toCorrection(r), new Recorded(day.firstIn(), day.lastOut(), day.sessions()),
            r.workflowId() == null ? List.of() : workflow.timeline(r.workflowId()));
    }

    // ---------- submitting, editing, withdrawing ----------

    @Transactional
    Correction submit(UUID employeeId, Input in) {
        Emp e = facts.person(employeeId).orElseThrow(CorrectionService::notFound);
        check(in, e.joinDate(), LocalDateTime.now(clock.withZone(e.zone())));
        locks.requireOpen(in.date(), "attendance");
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into attendance_correction (id, tenant_id, employee_id, work_date, clock_in, clock_out, reason, status)
                    values (:id, :tenant, :emp, :date, :in, :out, :reason, 'PENDING')""")
                .param("id", id).param("tenant", TenantContext.require()).param("emp", employeeId).param("date", in.date())
                .param("in", in.clockIn()).param("out", in.clockOut()).param("reason", in.reason().trim()).update();
        } catch (DuplicateKeyException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "CORRECTION_OPEN",
                "You already have a correction waiting for that day. Withdraw or change it instead.");
        }
        startApproval(id, employeeId);
        return find(id).orElseThrow();
    }

    /** A request sent back for changes: the employee edits it and it starts a fresh approval chain. */
    @Transactional
    Correction resubmit(UUID employeeId, UUID id, Input in) {
        Row r = row(id);
        if (!r.employeeId().equals(employeeId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_CORRECTION", "You can only change your own corrections.");
        }
        if (!"RETURNED".equals(r.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_RETURNED", "Only a correction sent back for changes can be edited.");
        }
        Emp e = facts.person(employeeId).orElseThrow(CorrectionService::notFound);
        check(in, e.joinDate(), LocalDateTime.now(clock.withZone(e.zone())));
        locks.requireOpen(in.date(), "attendance");
        try {
            jdbc.sql("""
                    update attendance_correction set work_date = :date, clock_in = :in, clock_out = :out, reason = :reason,
                           status = 'PENDING', returned_comment = null, decided_at = null, version = version + 1
                    where id = :id""")
                .param("date", in.date()).param("in", in.clockIn()).param("out", in.clockOut())
                .param("reason", in.reason().trim()).param("id", id).update();
        } catch (DuplicateKeyException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "CORRECTION_OPEN", "You already have a correction waiting for that day.");
        }
        startApproval(id, employeeId);
        return find(id).orElseThrow();
    }

    /** The employee withdraws a request that is waiting or was sent back. */
    @Transactional
    void cancel(UUID employeeId, UUID id) {
        Row r = row(id);
        if (!r.employeeId().equals(employeeId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_CORRECTION", "You can only withdraw your own corrections.");
        }
        if ("PENDING".equals(r.status())) {
            workflow.cancel(r.workflowId(), employeeId); // marks it CANCELLED through CorrectionApprovals
        } else if ("RETURNED".equals(r.status())) {
            jdbc.sql("update attendance_correction set status = 'CANCELLED', decided_at = now(), version = version + 1 where id = :id")
                .param("id", id).update();
        } else {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_CANCEL", "This correction has already been decided.");
        }
    }

    /** The first matching correction workflow (by department), else their manager (HR when they have none). */
    private void startApproval(UUID id, UUID employeeId) {
        UUID department = jdbc.sql("select department_id from employee where id = :id").param("id", employeeId)
            .query(UUID.class).optional().orElse(null);
        WorkflowService.Plan plan = flows.match(CorrectionApprovals.SUBJECT, null, department, null)
            .orElseGet(() -> new WorkflowService.ChainSpec(1, false).toPlan());
        UUID flow = workflow.start(CorrectionApprovals.SUBJECT, id, employeeId, plan);
        jdbc.sql("update attendance_correction set workflow_id = :flow where id = :id").param("flow", flow).param("id", id).update();
    }

    /** Checks a request against the person's join date and their local "now". */
    static void check(Input in, LocalDate joined, LocalDateTime now) {
        if (in == null || in.date() == null) throw bad("DATE_REQUIRED", "Choose the day to correct.");
        LocalDate today = now.toLocalDate();
        if (in.date().isAfter(today)) throw bad("DATE_IN_FUTURE", "You can only correct today or an earlier day.");
        if (in.date().isBefore(today.minusDays(MAX_AGE_DAYS))) {
            throw bad("CORRECTION_TOO_OLD", "Days more than " + MAX_AGE_DAYS + " days ago can't be corrected.");
        }
        if (joined != null && in.date().isBefore(joined)) throw bad("BEFORE_JOINING", "That's before you joined.");
        if (in.clockIn() == null && in.clockOut() == null) throw bad("TIMES_REQUIRED", "Give the clock-in time, the clock-out time, or both.");
        if (in.clockIn() != null && in.clockOut() != null && !in.clockOut().isAfter(in.clockIn())) {
            throw bad("BAD_TIMES", "The clock-out time must be after the clock-in time.");
        }
        if (in.date().equals(today)) {
            LocalTime t = now.toLocalTime();
            if ((in.clockIn() != null && in.clockIn().isAfter(t)) || (in.clockOut() != null && in.clockOut().isAfter(t))) {
                throw bad("TIME_IN_FUTURE", "That time hasn't happened yet.");
            }
        }
        if (in.reason() == null || in.reason().isBlank()) throw bad("REASON_REQUIRED", "Say why the times need correcting.");
        if (in.reason().trim().length() > MAX_REASON) throw bad("REASON_TOO_LONG", "Keep the reason under " + MAX_REASON + " characters.");
    }

    // ---------- helpers ----------

    private Row row(UUID id) {
        return jdbc.sql(SELECT + "where c.id = :id").param("id", id).query(Row.class).optional().orElseThrow(CorrectionService::notFound);
    }

    private Correction toCorrection(Row r) {
        var pending = "PENDING".equals(r.status()) && r.workflowId() != null ? workflow.pendingWith(r.workflowId()).orElse(null) : null;
        boolean open = "PENDING".equals(r.status()) || "RETURNED".equals(r.status());
        return new Correction(r.id(), r.employeeId(), r.employeeName(), r.employeeNo(), r.workDate(), r.clockIn(), r.clockOut(),
            r.reason(), r.status(), r.returnedComment(), r.createdAt(), r.decidedAt(), pending, open, "RETURNED".equals(r.status()));
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "CORRECTION_NOT_FOUND", "That correction doesn't exist.");
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
