package com.hrms.attendance;

import com.hrms.shared.security.DataScope;
import com.hrms.shared.security.EmployeeScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attendance report: per person and month, the timesheet totals (worked out exactly as timesheets do) and overtime
 * split into paid hours by rate and hours given as time off. A whole year, or one month of it.
 */
@Service
class AttendanceReport {

    /** Paid overtime minutes at one rate. */
    record RateMinutes(BigDecimal rate, int minutes) {
    }

    /**
     * One person and month. overtimePaidMin: approved paid overtime, split by rate in paidByRate (a null rate is
     * overtime sent before rates were required); weightedMin: paid minutes × rate, what payroll pays for.
     * overtimeTimeOffMin: approved overtime given back as leave. locked: the month is locked for payroll.
     */
    record Row(UUID employeeId, String employeeNo, String employeeName, String department, String site, String month,
               TimesheetService.Totals totals, int overtimePaidMin, int overtimeTimeOffMin, int weightedMin,
               List<RateMinutes> paidByRate, boolean locked) {
    }

    record Report(int year, Integer month, DataScope scope, List<Row> rows, boolean truncated) {
    }

    private final TimesheetService timesheets;
    private final JdbcClient jdbc;
    private final PeriodLocks locks;

    AttendanceReport(TimesheetService timesheets, JdbcClient jdbc, PeriodLocks locks) {
        this.timesheets = timesheets;
        this.jdbc = jdbc;
        this.locks = locks;
    }

    @Transactional(readOnly = true)
    Report build(UUID me, DataScope scope, int year, Integer month) {
        List<YearMonth> months = new ArrayList<>();
        if (month != null) months.add(YearMonth.of(year, month));
        else for (int m = 1; m <= 12; m++) months.add(YearMonth.of(year, m));
        LocalDate today = LocalDate.now();
        months.removeIf(m -> m.atDay(1).isAfter(today));

        LocalDate from = months.isEmpty() ? LocalDate.of(year, 1, 1) : months.get(0).atDay(1);
        LocalDate to = months.isEmpty() ? from : months.get(months.size() - 1).atEndOfMonth();
        Map<String, Overtime> overtime = overtime(me, scope, from, to);
        var locked = locks.lockedMonths(from, to);

        List<Row> rows = new ArrayList<>();
        boolean truncated = false;
        for (YearMonth m : months) {
            TimesheetService.Team team = timesheets.team(me, scope, m.atDay(1), m.atEndOfMonth());
            truncated |= team.truncated();
            for (TimesheetService.Line l : team.people()) {
                Overtime o = overtime.getOrDefault(l.employeeId() + "|" + m, Overtime.NONE);
                rows.add(new Row(l.employeeId(), l.employeeNo(), l.fullName(), l.department(), l.site(), m.toString(), l.totals(),
                    o.paidMin(), o.timeOffMin(), o.weightedMin(), o.byRate(), locked.contains(m)));
            }
        }
        return new Report(year, month, scope, rows, truncated);
    }

    private record Overtime(int paidMin, int timeOffMin, int weightedMin, List<RateMinutes> byRate) {
        static final Overtime NONE = new Overtime(0, 0, 0, List.of());
    }

    /** Approved overtime by person and month ("id|2026-11"), within the scope. */
    private Map<String, Overtime> overtime(UUID me, DataScope scope, LocalDate from, LocalDate to) {
        record Agg(UUID employeeId, int year, int month, String compensation, BigDecimal rate, BigDecimal hours) {
        }
        List<Agg> rows = jdbc.sql("""
                select o.employee_id, extract(year from o.work_date)::int as year, extract(month from o.work_date)::int as month,
                       o.compensation, o.rate, sum(o.hours) as hours
                from overtime_request o join employee e on e.id = o.employee_id
                where %s and o.status = 'APPROVED' and o.work_date between :from and :to
                group by o.employee_id, 2, 3, o.compensation, o.rate""".formatted(EmployeeScope.predicate(scope, "e")))
            .param("me", me).param("from", from).param("to", to).query(Agg.class).list();

        Map<String, List<Agg>> by = new HashMap<>();
        for (Agg a : rows) by.computeIfAbsent(a.employeeId() + "|" + YearMonth.of(a.year(), a.month()), k -> new ArrayList<>()).add(a);
        Map<String, Overtime> out = new HashMap<>();
        by.forEach((key, list) -> {
            int paid = 0, timeOff = 0;
            BigDecimal weighted = BigDecimal.ZERO;
            Map<BigDecimal, Integer> byRate = new TreeMap<>(java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()));
            for (Agg a : list) {
                int min = minutes(a.hours());
                if ("TIME_OFF".equals(a.compensation())) {
                    timeOff += min;
                    continue;
                }
                paid += min;
                BigDecimal rate = a.rate() == null ? null : a.rate().stripTrailingZeros();
                byRate.merge(rate, min, Integer::sum);
                weighted = weighted.add(a.hours().multiply(a.rate() == null ? BigDecimal.ONE : a.rate()));
            }
            List<RateMinutes> rates = byRate.entrySet().stream().map(e -> new RateMinutes(e.getKey(), e.getValue())).toList();
            out.put(key, new Overtime(paid, timeOff, minutes(weighted), rates));
        });
        return out;
    }

    static int minutes(BigDecimal hours) {
        return hours.multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.HALF_UP).intValue();
    }
}
