package com.hrms.attendance;

import com.hrms.leave.LeaveCredits;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Turns approved overtime taken as time off into leave, using the person's overtime rule at the time of approval:
 * banked hours add up until they make a whole half or full day; per-request hours convert on their own.
 */
@Component
class OvertimeTimeOff {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** What a request earns (or earned): the hours that count, the leave, and the bank after it. */
    record Preview(UUID leaveTypeId, String leaveType, BigDecimal countedHours, BigDecimal days, BigDecimal bankHours,
                   BigDecimal lostHours, String conversion, boolean done) {
    }

    private final JdbcClient jdbc;
    private final OvertimeRules rules;
    private final LeaveCredits credits;
    private final AttendanceFacts facts;
    private final Clock clock;

    @Autowired
    OvertimeTimeOff(JdbcClient jdbc, OvertimeRules rules, LeaveCredits credits, AttendanceFacts facts) {
        this(jdbc, rules, credits, facts, Clock.systemUTC());
    }

    OvertimeTimeOff(JdbcClient jdbc, OvertimeRules rules, LeaveCredits credits, AttendanceFacts facts, Clock clock) {
        this.jdbc = jdbc;
        this.rules = rules;
        this.credits = credits;
        this.facts = facts;
        this.clock = clock;
    }

    /** Banked hours not yet turned into leave. */
    BigDecimal bank(UUID employeeId, UUID leaveTypeId) {
        if (leaveTypeId == null) return BigDecimal.ZERO;
        return jdbc.sql("select hours from overtime_bank where employee_id = :emp and leave_type_id = :type")
            .param("emp", employeeId).param("type", leaveTypeId).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }

    /** What approving it now would earn, under the person's current rule. */
    Preview estimate(UUID employeeId, BigDecimal hours, BigDecimal rate) {
        OvertimeRules.Rule r = rules.forEmployee(employeeId);
        if (!r.allowsTimeOff()) return null;
        var c = OvertimeRules.convert(r, hours, rate, bank(employeeId, r.leaveTypeId()));
        return new Preview(r.leaveTypeId(), r.leaveTypeName(), c.countedHours(), c.days(), c.bankAfter(), c.lostHours(), r.conversion(), false);
    }

    /**
     * Called in the approval's transaction. Refuses (rolling the approval back) when the person's rule no longer gives
     * time off, so the approver can send it back to be paid instead.
     */
    void credit(UUID requestId) {
        record Req(UUID employeeId, LocalDate workDate, BigDecimal hours, BigDecimal rate) {
        }
        Req q = jdbc.sql("select employee_id, work_date, hours, rate from overtime_request where id = :id")
            .param("id", requestId).query(Req.class).single();
        OvertimeRules.Rule r = rules.forEmployee(q.employeeId());
        if (!r.allowsTimeOff()) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_TIME_OFF",
                "Overtime for this person is paid, not given as time off, under the current overtime rules. "
                    + "Send it back so they can ask for it to be paid.");
        }
        jdbc.sql("""
                insert into overtime_bank (tenant_id, employee_id, leave_type_id, hours) values (:tenant, :emp, :type, 0)
                on conflict (employee_id, leave_type_id) do nothing""")
            .param("tenant", TenantContext.require()).param("emp", q.employeeId()).param("type", r.leaveTypeId()).update();
        BigDecimal bank = jdbc.sql("select hours from overtime_bank where employee_id = :emp and leave_type_id = :type for update")
            .param("emp", q.employeeId()).param("type", r.leaveTypeId()).query(BigDecimal.class).single();

        var c = OvertimeRules.convert(r, q.hours(), q.rate(), bank);
        jdbc.sql("update overtime_bank set hours = :hours, updated_at = now() where employee_id = :emp and leave_type_id = :type")
            .param("hours", c.bankAfter()).param("emp", q.employeeId()).param("type", r.leaveTypeId()).update();
        if (c.days().signum() > 0) {
            LocalDate today = LocalDate.now(clock.withZone(facts.zoneOf(q.employeeId())));
            credits.credit(q.employeeId(), r.leaveTypeId(), c.days(), today, OvertimeRules.expiresOn(r, today),
                "OVERTIME", requestId, "Overtime on " + q.workDate().format(DAY));
        }
        jdbc.sql("update overtime_request set credited_hours = :hours, credited_days = :days, leave_type_id = :type where id = :id")
            .param("hours", c.countedHours()).param("days", c.days()).param("type", r.leaveTypeId()).param("id", requestId).update();
    }
}
