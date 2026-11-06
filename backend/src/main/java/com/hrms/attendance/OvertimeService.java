package com.hrms.attendance;

import com.hrms.attendance.AttendanceFacts.Emp;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import com.hrms.workflow.ApprovalFlows;
import com.hrms.workflow.WorkflowService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Overtime requests: someone asks to be paid for extra hours worked on a day, at a rate (1.0×, 1.5×, 2.0× or 3.0×),
 * with a reason. The request goes through approval workflows (subject OVERTIME, matched by department and hours; else
 * their manager). Approved hours show on timesheets. Depending on the person's {@link OvertimeRules overtime rule} it's
 * paid, given back as time off (leave, see {@link OvertimeTimeOff}), or they choose. Overtime is asked for after it's worked; approving it before
 * (pre-OT) comes with shifts.
 */
@Service
public class OvertimeService {

    /** How far back overtime can be asked for. */
    static final int MAX_AGE_DAYS = 60;
    static final int MAX_REASON = 500;
    /** A day's open and approved overtime can't add up to more than this. */
    static final BigDecimal MAX_HOURS_PER_DAY = new BigDecimal("24");

    /** compensation: PAY or TIME_OFF; needed only when the rule lets the employee choose. rate: needed when it's paid. */
    record Input(LocalDate date, BigDecimal hours, BigDecimal rate, String reason, String compensation) {
        Input(LocalDate date, BigDecimal hours, BigDecimal rate, String reason) {
            this(date, hours, rate, reason, null);
        }
    }

    /**
     * canCancel: waiting or sent back; canEdit: sent back (edit and resubmit). compensation: PAY or TIME_OFF; rate is
     * null for time off that ignores the rate. Once approved as time off: creditedHours (after any rate) and
     * creditedDays of leaveType (0 while the hours wait in the bank).
     */
    record Overtime(UUID id, UUID employeeId, String employeeName, String employeeNo, LocalDate date, BigDecimal hours,
                    BigDecimal rate, String reason, String status, String returnedComment, Instant createdAt,
                    Instant decidedAt, WorkflowService.PendingWith pendingWith, boolean canCancel, boolean canEdit,
                    String compensation, BigDecimal creditedHours, BigDecimal creditedDays, String leaveType,
                    String dayKind) {
    }

    /** The rule that applies to you, and your banked hours for its leave type. */
    record MyRule(OvertimeRules.Rule rule, BigDecimal bankHours) {
    }

    /** What the clock-ins showed that day (with any approved correction applied), and other overtime asked for it. */
    record Recorded(List<WorkDay.Session> sessions, int workedMin, BigDecimal otherHours) {
    }

    /** timeOff: for time off, what it earned (done) or would earn if approved now; null when paid. */
    record Detail(Overtime overtime, Recorded recorded, List<WorkflowService.TimelineStep> timeline,
                  OvertimeTimeOff.Preview timeOff) {
    }

    private record Row(UUID id, UUID employeeId, String employeeName, String employeeNo, LocalDate workDate,
                       BigDecimal hours, BigDecimal rate, String reason, String status, String returnedComment,
                       Instant createdAt, Instant decidedAt, UUID workflowId, String compensation, BigDecimal creditedHours,
                       BigDecimal creditedDays, UUID leaveTypeId, String leaveType, String dayKind) {
    }

    private static final String SELECT = """
            select o.id, o.employee_id, e.full_name as employee_name, e.employee_no, o.work_date, o.hours, o.rate,
                   o.reason, o.status, o.returned_comment, o.created_at, o.decided_at, o.workflow_id, o.compensation,
                   o.credited_hours, o.credited_days, o.leave_type_id, lt.name as leave_type, o.day_kind
            from overtime_request o join employee e on e.id = o.employee_id
            left join leave_type lt on lt.id = o.leave_type_id
            """;

    private final JdbcClient jdbc;
    private final AttendanceFacts facts;
    private final WorkflowService workflow;
    private final ApprovalFlows flows;
    private final OvertimeRules rules;
    private final OvertimeTimeOff timeOff;
    private final Clock clock;

    @Autowired
    OvertimeService(JdbcClient jdbc, AttendanceFacts facts, WorkflowService workflow, ApprovalFlows flows, OvertimeRules rules,
                    OvertimeTimeOff timeOff) {
        this(jdbc, facts, workflow, flows, rules, timeOff, Clock.systemUTC());
    }

    OvertimeService(JdbcClient jdbc, AttendanceFacts facts, WorkflowService workflow, ApprovalFlows flows, OvertimeRules rules,
                    OvertimeTimeOff timeOff, Clock clock) {
        this.jdbc = jdbc;
        this.facts = facts;
        this.workflow = workflow;
        this.flows = flows;
        this.rules = rules;
        this.timeOff = timeOff;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    MyRule myRule(UUID employeeId) {
        OvertimeRules.Rule r = rules.forEmployee(employeeId);
        return new MyRule(r, timeOff.bank(employeeId, r.leaveTypeId()));
    }

    // ---------- reading ----------

    /** Your own requests, newest day first (the last 200). */
    @Transactional(readOnly = true)
    List<Overtime> mine(UUID employeeId) {
        return jdbc.sql(SELECT + "where o.employee_id = :emp and o.status <> 'CANCELLED' order by o.work_date desc, o.created_at desc limit 200")
            .param("emp", employeeId).query(Row.class).list().stream().map(this::toOvertime).toList();
    }

    Optional<Overtime> find(UUID id) {
        return jdbc.sql(SELECT + "where o.id = :id").param("id", id).query(Row.class).optional().map(this::toOvertime);
    }

    @Transactional(readOnly = true)
    Detail detail(UUID id) {
        Row r = row(id);
        Emp e = facts.person(r.employeeId()).orElseThrow(OvertimeService::notFound);
        LocalDate d = r.workDate();
        List<UUID> ids = List.of(e.id());
        List<WorkDay.Clocking> clockings = AttendanceFacts.on(facts.punches(ids, d, d).get(e.id()), d, e.zone()).stream()
            .map(p -> new WorkDay.Clocking(AttendanceFacts.local(p.at(), e.zone()), p.type())).toList();
        var correction = AttendanceFacts.correctionsOn(facts.corrections(ids, d, d).get(e.id()), d).applied();
        if (correction != null) clockings = WorkDay.corrected(clockings, correction.clockIn(), correction.clockOut());
        var day = WorkDay.of(new WorkDay.Input(d, true, null, DayStatus.Hours.DEFAULT, clockings, d.plusDays(1).atStartOfDay()));
        BigDecimal others = hoursOn(r.employeeId(), d, r.id());
        OvertimeTimeOff.Preview off = null;
        if ("TIME_OFF".equals(r.compensation())) {
            off = r.creditedHours() != null
                ? new OvertimeTimeOff.Preview(r.leaveTypeId(), r.leaveType(), r.creditedHours(), r.creditedDays(), null, null, null, true)
                : "PENDING".equals(r.status()) || "RETURNED".equals(r.status()) ? timeOff.estimate(r.employeeId(), r.hours(), r.rate()) : null;
        }
        return new Detail(toOvertime(r), new Recorded(day.sessions(), day.workedMin(), others),
            r.workflowId() == null ? List.of() : workflow.timeline(r.workflowId()), off);
    }

    // ---------- submitting, editing, withdrawing ----------

    @Transactional
    Overtime submit(UUID employeeId, Input in) {
        Emp e = facts.person(employeeId).orElseThrow(OvertimeService::notFound);
        OvertimeRules.Rule rule = rules.forEmployee(employeeId);
        String compensation = compensation(rule, in);
        check(in, e.joinDate(), LocalDate.now(clock.withZone(e.zone())));
        OvertimeRules.DayKind kind = dayKind(e, in.date());
        BigDecimal rate = rateNeeded(rule, compensation) ? OvertimeRules.rateFor(rule, kind, in.rate()) : null;
        lockPerson(employeeId);
        checkDayTotal(hoursOn(employeeId, in.date(), null), in.hours());
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into overtime_request (id, tenant_id, employee_id, work_date, hours, rate, reason, status, compensation, day_kind)
                values (:id, :tenant, :emp, :date, :hours, :rate, :reason, 'PENDING', :comp, :kind)""")
            .param("id", id).param("tenant", TenantContext.require()).param("emp", employeeId).param("date", in.date())
            .param("hours", in.hours()).param("rate", rate).param("reason", in.reason().trim())
            .param("comp", compensation).param("kind", kind.name()).update();
        startApproval(id, employeeId, in.hours());
        return find(id).orElseThrow();
    }

    /** A request sent back for changes: the employee edits it and it starts a fresh approval chain. */
    @Transactional
    Overtime resubmit(UUID employeeId, UUID id, Input in) {
        Row r = row(id);
        if (!r.employeeId().equals(employeeId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_OVERTIME", "You can only change your own overtime requests.");
        }
        if (!"RETURNED".equals(r.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_RETURNED", "Only an overtime request sent back for changes can be edited.");
        }
        Emp e = facts.person(employeeId).orElseThrow(OvertimeService::notFound);
        OvertimeRules.Rule rule = rules.forEmployee(employeeId);
        String compensation = compensation(rule, in);
        check(in, e.joinDate(), LocalDate.now(clock.withZone(e.zone())));
        OvertimeRules.DayKind kind = dayKind(e, in.date());
        BigDecimal rate = rateNeeded(rule, compensation) ? OvertimeRules.rateFor(rule, kind, in.rate()) : null;
        lockPerson(employeeId);
        checkDayTotal(hoursOn(employeeId, in.date(), id), in.hours());
        jdbc.sql("""
                update overtime_request set work_date = :date, hours = :hours, rate = :rate, reason = :reason,
                       compensation = :comp, day_kind = :kind, status = 'PENDING', returned_comment = null,
                       decided_at = null, version = version + 1
                where id = :id""")
            .param("date", in.date()).param("hours", in.hours()).param("rate", rate)
            .param("reason", in.reason().trim()).param("comp", compensation).param("kind", kind.name()).param("id", id).update();
        startApproval(id, employeeId, in.hours());
        return find(id).orElseThrow();
    }

    /** The employee withdraws a request that is waiting or was sent back. */
    @Transactional
    void cancel(UUID employeeId, UUID id) {
        Row r = row(id);
        if (!r.employeeId().equals(employeeId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_OVERTIME", "You can only withdraw your own overtime requests.");
        }
        if ("PENDING".equals(r.status())) {
            workflow.cancel(r.workflowId(), employeeId); // marks it CANCELLED through OvertimeApprovals
        } else if ("RETURNED".equals(r.status())) {
            jdbc.sql("update overtime_request set status = 'CANCELLED', decided_at = now(), version = version + 1 where id = :id")
                .param("id", id).update();
        } else {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_CANCEL", "This overtime request has already been decided.");
        }
    }

    /** The first matching overtime workflow (by department and hours), else their manager (HR when they have none). */
    private void startApproval(UUID id, UUID employeeId, BigDecimal hours) {
        UUID department = jdbc.sql("select department_id from employee where id = :id").param("id", employeeId)
            .query(UUID.class).optional().orElse(null);
        WorkflowService.Plan plan = flows.match(OvertimeApprovals.SUBJECT, null, department, hours)
            .orElseGet(() -> new WorkflowService.ChainSpec(1, false).toPlan());
        UUID flow = workflow.start(OvertimeApprovals.SUBJECT, id, employeeId, plan);
        jdbc.sql("update overtime_request set workflow_id = :flow where id = :id").param("flow", flow).param("id", id).update();
    }

    /**
     * PAY or TIME_OFF, from the rule: paid-only and time-off-only rules decide it (asking for the other is refused);
     * CHOICE needs the employee to say.
     */
    static String compensation(OvertimeRules.Rule rule, Input in) {
        String asked = in == null || in.compensation() == null || in.compensation().isBlank() ? null : in.compensation().trim().toUpperCase();
        if (asked != null && !asked.equals("PAY") && !asked.equals("TIME_OFF")) {
            throw bad("BAD_COMPENSATION", "Choose to be paid or to take time off.");
        }
        String allowed = rule.allowsTimeOff() ? rule.compensation() : "PAY";
        return switch (allowed) {
            case "CHOICE" -> {
                if (asked == null) throw bad("COMPENSATION_REQUIRED", "Choose to be paid or to take time off.");
                yield asked;
            }
            case "TIME_OFF" -> {
                if ("PAY".equals(asked)) throw bad("TIME_OFF_ONLY", "Overtime here is given back as time off, not paid.");
                yield "TIME_OFF";
            }
            default -> {
                if ("TIME_OFF".equals(asked)) throw bad("PAY_ONLY", "Overtime here is paid, not given back as time off.");
                yield "PAY";
            }
        };
    }

    /** Paid overtime needs a rate; time off only when the rule multiplies the hours by it. */
    static boolean rateNeeded(OvertimeRules.Rule rule, String compensation) {
        return "PAY".equals(compensation) || rule.applyRate();
    }

    /** Two requests sent at once can't both slip under the day's limit. */
    private void lockPerson(UUID employeeId) {
        jdbc.sql("select id from employee where id = :id for update").param("id", employeeId).query(UUID.class).optional();
    }

    /** Open and approved hours on a day, leaving out one request (the one being changed). */
    private BigDecimal hoursOn(UUID employeeId, LocalDate date, UUID except) {
        return jdbc.sql("""
                select coalesce(sum(hours), 0) from overtime_request
                where employee_id = :emp and work_date = :date and status in ('PENDING', 'RETURNED', 'APPROVED')
                  and (cast(:except as uuid) is null or id <> :except)""")
            .param("emp", employeeId).param("date", date).param("except", except).query(BigDecimal.class).single();
    }

    static void checkDayTotal(BigDecimal already, BigDecimal asked) {
        if (already.add(asked).compareTo(MAX_HOURS_PER_DAY) > 0) {
            throw bad("TOO_MANY_HOURS", "That day already has " + already.stripTrailingZeros().toPlainString()
                + " hours of overtime asked for or approved; a day can't have more than 24.");
        }
    }

    /** A public holiday, else a rest day (outside their entity's working week), else a working day. */
    private OvertimeRules.DayKind dayKind(Emp e, LocalDate date) {
        return kindOf(!facts.holidays(date, date).isEmpty(), e.workDays(), date);
    }

    static OvertimeRules.DayKind kindOf(boolean holiday, Set<java.time.DayOfWeek> workDays, LocalDate date) {
        if (holiday) return OvertimeRules.DayKind.HOLIDAY;
        return workDays.contains(date.getDayOfWeek()) ? OvertimeRules.DayKind.WORKING : OvertimeRules.DayKind.REST;
    }

    /** Checks a request against the person's join date and their local "today". The rate is checked against the rule. */
    static void check(Input in, LocalDate joined, LocalDate today) {
        if (in == null || in.date() == null) throw bad("DATE_REQUIRED", "Choose the day you worked overtime.");
        if (in.date().isAfter(today)) throw bad("DATE_IN_FUTURE", "Ask for overtime on the day you work it, or after.");
        if (in.date().isBefore(today.minusDays(MAX_AGE_DAYS))) {
            throw bad("OVERTIME_TOO_OLD", "Overtime more than " + MAX_AGE_DAYS + " days ago can't be asked for.");
        }
        if (joined != null && in.date().isBefore(joined)) throw bad("BEFORE_JOINING", "That's before you joined.");
        if (in.hours() == null || in.hours().signum() <= 0) throw bad("HOURS_REQUIRED", "Give the number of overtime hours.");
        if (in.hours().compareTo(MAX_HOURS_PER_DAY) > 0) throw bad("BAD_HOURS", "A day can't have more than 24 hours of overtime.");
        if (in.hours().multiply(BigDecimal.valueOf(4)).stripTrailingZeros().scale() > 0) {
            throw bad("BAD_HOURS", "Use quarter hours, like 1.25 or 2.5.");
        }
        if (in.reason() == null || in.reason().isBlank()) throw bad("REASON_REQUIRED", "Say what the overtime was for.");
        if (in.reason().trim().length() > MAX_REASON) throw bad("REASON_TOO_LONG", "Keep the reason under " + MAX_REASON + " characters.");
    }

    // ---------- helpers ----------

    private Row row(UUID id) {
        return jdbc.sql(SELECT + "where o.id = :id").param("id", id).query(Row.class).optional().orElseThrow(OvertimeService::notFound);
    }

    private Overtime toOvertime(Row r) {
        var pending = "PENDING".equals(r.status()) && r.workflowId() != null ? workflow.pendingWith(r.workflowId()).orElse(null) : null;
        boolean open = "PENDING".equals(r.status()) || "RETURNED".equals(r.status());
        return new Overtime(r.id(), r.employeeId(), r.employeeName(), r.employeeNo(), r.workDate(), r.hours(), r.rate(),
            r.reason(), r.status(), r.returnedComment(), r.createdAt(), r.decidedAt(), pending, open, "RETURNED".equals(r.status()),
            r.compensation(), r.creditedHours(), r.creditedDays(), r.leaveType(), r.dayKind());
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "OVERTIME_NOT_FOUND", "That overtime request doesn't exist.");
    }

    static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
