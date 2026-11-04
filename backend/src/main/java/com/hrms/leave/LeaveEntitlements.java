package com.hrms.leave;

import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * HR's controls over individual balances: a manual entitlement or carried figure for one person and year,
 * and the year-end run that carries unused days forward (up to each type's cap).
 */
@Service
class LeaveEntitlements {

    record Override(BigDecimal entitled, BigDecimal carried, String note) {
    }

    record YearEndResult(int year, int people, BigDecimal days, int keptManual, List<String> types) {
    }

    record YearEndRun(int year, Instant runAt, int people, BigDecimal days) {
    }

    private final JdbcClient jdbc;
    private final LeaveService leave;
    private final AuditLog audit;

    LeaveEntitlements(JdbcClient jdbc, LeaveService leave, AuditLog audit) {
        this.jdbc = jdbc;
        this.leave = leave;
        this.audit = audit;
    }

    private void requireEmployee(UUID employeeId) {
        boolean ok = jdbc.sql("select exists (select 1 from employee where id = :id)").param("id", employeeId).query(Boolean.class).single();
        if (!ok) throw new ApiException(HttpStatus.NOT_FOUND, "UNKNOWN_EMPLOYEE", "That employee doesn't exist.");
    }

    /**
     * Sets this person's figures. {@code entitled} null means "use the policy"; {@code carried} null leaves the
     * carried days as they are. Typing a carried figure makes it manual, so a later year-end run won't change it.
     */
    @Transactional
    List<LeaveService.Balance> set(UUID actor, UUID employeeId, UUID typeId, int year, Override in) {
        requireEmployee(employeeId);
        LeaveService.LeaveType type = leave.type(typeId);
        if (!type.trackBalance()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_BALANCE", type.name() + " has no yearly allowance to set.");
        }
        check(in.entitled(), "entitled");
        check(in.carried(), "carried");
        jdbc.sql("""
                insert into leave_entitlement (tenant_id, employee_id, leave_type_id, year, entitled, carried, carried_source, note, updated_by)
                values (:tenant, :emp, :type, :year, cast(:entitled as numeric), coalesce(cast(:carried as numeric), 0), case when cast(:carried as numeric) is null then null else 'MANUAL' end, :note, :actor)
                on conflict (employee_id, leave_type_id, year) do update set
                    entitled = excluded.entitled,
                    carried = case when cast(:carried as numeric) is null then leave_entitlement.carried else excluded.carried end,
                    carried_source = case when cast(:carried as numeric) is null then leave_entitlement.carried_source else 'MANUAL' end,
                    note = excluded.note, updated_by = excluded.updated_by, updated_at = now()""")
            .param("tenant", TenantContext.require()).param("emp", employeeId).param("type", typeId).param("year", year)
            .param("entitled", in.entitled()).param("carried", in.carried())
            .param("note", in.note() == null || in.note().isBlank() ? null : in.note().trim()).param("actor", actor).update();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("type", type.name());
        after.put("year", year);
        after.put("entitled", in.entitled() == null ? "policy" : in.entitled().toPlainString());
        after.put("carried", in.carried() == null ? "unchanged" : in.carried().toPlainString());
        audit.record(actor, "LEAVE_ENTITLEMENT_SET", "employee", employeeId, after);
        return leave.balances(employeeId, year);
    }

    /** Back to the policy: removes HR's entitled figure and any manual carried days (year-end carry stays). */
    @Transactional
    List<LeaveService.Balance> reset(UUID actor, UUID employeeId, UUID typeId, int year) {
        requireEmployee(employeeId);
        jdbc.sql("""
                update leave_entitlement set entitled = null, note = null,
                       carried = case when carried_source = 'MANUAL' then 0 else carried end,
                       carried_source = case when carried_source = 'MANUAL' then null else carried_source end,
                       updated_by = :actor, updated_at = now()
                where employee_id = :emp and leave_type_id = :type and year = :year""")
            .param("emp", employeeId).param("type", typeId).param("year", year).param("actor", actor).update();
        jdbc.sql("delete from leave_entitlement where employee_id = :emp and leave_type_id = :type and year = :year and entitled is null and carried = 0")
            .param("emp", employeeId).param("type", typeId).param("year", year).update();
        audit.record(actor, "LEAVE_ENTITLEMENT_RESET", "employee", employeeId, Map.of("typeId", typeId.toString(), "year", year));
        return leave.balances(employeeId, year);
    }

    /**
     * Carries unused days from {@code year} into the next, for every active employee and every type with a
     * carry-over cap. Unused = entitled + carried − used − pending (pending counts as used, to be safe).
     * Safe to run again: it replaces the carried days it wrote before, and never changes manual figures.
     */
    @Transactional
    YearEndResult runYearEnd(UUID actor, int year) {
        int current = LocalDate.now().getYear();
        if (year >= current + 1 || year < current - 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_YEAR", "Carry over from last year or this year only.");
        }
        List<LeaveService.LeaveType> carryTypes = leave.types(false).stream()
            .filter(t -> t.trackBalance() && t.carryMax().signum() > 0).toList();
        if (carryTypes.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_CARRY_TYPES",
                "No leave type allows carry-over. Set a carry-over limit on a leave type first.");
        }
        List<UUID> employees = jdbc.sql("select id from employee where status = 'ACTIVE'").query(UUID.class).list();
        int people = 0, keptManual = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (UUID emp : employees) {
            boolean any = false;
            for (LeaveService.Balance b : leave.balances(emp, year, carryTypes)) {
                LeaveService.LeaveType t = carryTypes.stream().filter(x -> x.id().equals(b.leaveTypeId())).findFirst().orElseThrow();
                BigDecimal carry = b.available().max(BigDecimal.ZERO).min(t.carryMax());
                int changed = jdbc.sql("""
                        insert into leave_entitlement (tenant_id, employee_id, leave_type_id, year, entitled, carried, carried_source, updated_by)
                        values (:tenant, :emp, :type, :next, null, :carry, 'YEAR_END', :actor)
                        on conflict (employee_id, leave_type_id, year) do update set
                            carried = excluded.carried, carried_source = 'YEAR_END', updated_by = excluded.updated_by, updated_at = now()
                        where leave_entitlement.carried_source is null or leave_entitlement.carried_source = 'YEAR_END'""")
                    .param("tenant", TenantContext.require()).param("emp", emp).param("type", t.id()).param("next", year + 1)
                    .param("carry", carry).param("actor", actor).update();
                if (changed == 0) {
                    keptManual++;
                    continue;
                }
                if (carry.signum() > 0) {
                    any = true;
                    total = total.add(carry);
                }
            }
            if (any) people++;
        }
        jdbc.sql("""
                insert into leave_year_end (tenant_id, year, run_by, people, days) values (:tenant, :year, :actor, :people, :days)
                on conflict (tenant_id, year) do update set run_at = now(), run_by = excluded.run_by, people = excluded.people, days = excluded.days""")
            .param("tenant", TenantContext.require()).param("year", year).param("actor", actor).param("people", people).param("days", total).update();
        audit.record(actor, "LEAVE_YEAR_END", "tenant", TenantContext.require(),
            Map.of("year", year, "people", people, "days", total.toPlainString()));
        return new YearEndResult(year, people, total, keptManual, carryTypes.stream().map(LeaveService.LeaveType::name).toList());
    }

    List<YearEndRun> runs() {
        return jdbc.sql("select year, run_at, people, days from leave_year_end order by year desc").query(YearEndRun.class).list();
    }

    private static void check(BigDecimal value, String field) {
        if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(365)) > 0
            || value.multiply(BigDecimal.TWO).stripTrailingZeros().scale() > 0)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_DAYS",
                "Use whole or half days between 0 and 365 for " + field + ".");
        }
    }
}
