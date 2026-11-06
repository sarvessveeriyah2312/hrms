package com.hrms.attendance;

import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locked months (company-wide, for payroll): nothing that changes a locked month's attendance goes through — clock-ins
 * on its days, sending or approving corrections and overtime for its days. Reading, rejecting, sending back and
 * withdrawing still work. HR unlocks with a reason; every lock and unlock is kept.
 */
@Service
class PeriodLocks {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    static final int MAX_REASON = 500;

    record Event(String action, String reason, String actor, Instant at) {
    }

    /** waiting*: requests for the month still waiting for approval (they can't be approved while it's locked). */
    record Status(String month, boolean locked, String lockedBy, Instant lockedAt, int waitingCorrections,
                  int waitingOvertime, List<Event> history) {
    }

    private final JdbcClient jdbc;
    private final AttendanceFacts facts;
    private final AuditLog audit;
    private final Clock clock;

    @Autowired
    PeriodLocks(JdbcClient jdbc, AttendanceFacts facts, AuditLog audit) {
        this(jdbc, facts, audit, Clock.systemUTC());
    }

    PeriodLocks(JdbcClient jdbc, AttendanceFacts facts, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.facts = facts;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------- checking ----------

    boolean isLocked(LocalDate day) {
        return jdbc.sql("select exists (select 1 from attendance_lock where period = :p)")
            .param("p", YearMonth.from(day).atDay(1)).query(Boolean.class).single();
    }

    Set<YearMonth> lockedMonths(LocalDate from, LocalDate to) {
        Set<YearMonth> out = new HashSet<>();
        jdbc.sql("select period from attendance_lock where period between :from and :to")
            .param("from", YearMonth.from(from).atDay(1)).param("to", to)
            .query(LocalDate.class).list().forEach(d -> out.add(YearMonth.from(d)));
        return out;
    }

    /** Refuses a change to a locked month's attendance; {@code what} says what was being changed. */
    void requireOpen(LocalDate day, String what) {
        if (isLocked(day)) throw locked(YearMonth.from(day), what);
    }

    /** For clock-ins: today in the person's time zone. */
    void requireOpenToday(UUID employeeId) {
        LocalDate today = LocalDate.now(clock.withZone(facts.zoneOf(employeeId)));
        if (isLocked(today)) throw locked(YearMonth.from(today), "clock-ins");
    }

    static ApiException locked(YearMonth month, String what) {
        return new ApiException(HttpStatus.CONFLICT, "PERIOD_LOCKED", month.format(MONTH)
            + " is locked for payroll, so its " + what + " can't change. Ask HR to unlock it.").with("month", month.toString());
    }

    // ---------- status, locking, unlocking ----------

    @Transactional(readOnly = true)
    Status status(YearMonth month, boolean withHistory) {
        record Lock(String lockedBy, Instant lockedAt) {
        }
        Lock lock = jdbc.sql("""
                select coalesce(e.full_name, u.email::text) as locked_by, l.locked_at from attendance_lock l
                left join app_user u on u.id = l.locked_by left join employee e on e.user_id = l.locked_by
                where l.period = :p""").param("p", month.atDay(1)).query(Lock.class).optional().orElse(null);
        int corrections = jdbc.sql("select count(*) from attendance_correction where status = 'PENDING' and work_date between :f and :t")
            .param("f", month.atDay(1)).param("t", month.atEndOfMonth()).query(Integer.class).single();
        int overtime = jdbc.sql("select count(*) from overtime_request where status = 'PENDING' and work_date between :f and :t")
            .param("f", month.atDay(1)).param("t", month.atEndOfMonth()).query(Integer.class).single();
        List<Event> history = !withHistory ? List.of() : jdbc.sql("""
                select ev.action, ev.reason, coalesce(e.full_name, u.email::text) as actor, ev.at from attendance_lock_event ev
                left join app_user u on u.id = ev.actor left join employee e on e.user_id = ev.actor
                where ev.period = :p order by ev.at desc limit 50""").param("p", month.atDay(1)).query(Event.class).list();
        return new Status(month.toString(), lock != null, lock == null ? null : lock.lockedBy(), lock == null ? null : lock.lockedAt(),
            corrections, overtime, history);
    }

    /** Locked months of a year, for showing which are closed. */
    List<String> lockedIn(int year) {
        return lockedMonths(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)).stream().sorted().map(YearMonth::toString).toList();
    }

    @Transactional
    Status lock(UUID actor, YearMonth month) {
        if (month.atDay(1).isAfter(LocalDate.now(clock))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MONTH_IN_FUTURE", "Only this month or earlier months can be locked.");
        }
        try {
            jdbc.sql("insert into attendance_lock (tenant_id, period, locked_by) values (:tenant, :p, :actor)")
                .param("tenant", TenantContext.require()).param("p", month.atDay(1)).param("actor", actor).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_LOCKED", month.format(MONTH) + " is already locked.");
        }
        event(month, "LOCK", null, actor);
        audit.record(actor, "ATTENDANCE_PERIOD_LOCKED", "attendance_lock", null, Map.of("month", month.toString()));
        return status(month, true);
    }

    @Transactional
    Status unlock(UUID actor, YearMonth month, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "Say why the month is being unlocked.");
        }
        if (reason.trim().length() > MAX_REASON) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_TOO_LONG", "Keep the reason under " + MAX_REASON + " characters.");
        }
        if (jdbc.sql("delete from attendance_lock where period = :p").param("p", month.atDay(1)).update() == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_LOCKED", month.format(MONTH) + " isn't locked.");
        }
        event(month, "UNLOCK", reason.trim(), actor);
        audit.record(actor, "ATTENDANCE_PERIOD_UNLOCKED", "attendance_lock", null, Map.of("month", month.toString(), "reason", reason.trim()));
        return status(month, true);
    }

    private void event(YearMonth month, String action, String reason, UUID actor) {
        jdbc.sql("""
                insert into attendance_lock_event (tenant_id, period, action, reason, actor)
                values (:tenant, :p, :action, :reason, :actor)""")
            .param("tenant", TenantContext.require()).param("p", month.atDay(1)).param("action", action)
            .param("reason", reason).param("actor", actor).update();
    }
}
