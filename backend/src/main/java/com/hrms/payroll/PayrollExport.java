package com.hrms.payroll;

import com.hrms.attendance.PayrollFeed;
import com.hrms.claims.ClaimsPayrollFeed;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One pay period for payroll: per person, the month's attendance (timesheet rules), approved overtime by rate, and
 * approved claims by currency. Shown as a preview and downloaded as CSV. Warns when the month isn't locked yet or
 * requests for it are still waiting, since those can still change the figures.
 */
@Service
class PayrollExport {

    /** overtimeByRate: rate ("1.5") → paid hours. claims / claimsUnpaid: currency → amount. */
    record Row(UUID employeeId, String employeeNo, String employeeName, String department, int workingDays, int daysWorked,
               BigDecimal hoursWorked, int lateCount, int lateMinutes, int earlyCount, int missedClockOuts, int absentDays,
               BigDecimal leaveDays, BigDecimal paidOvertimeHours, BigDecimal weightedOvertimeHours, BigDecimal timeOffOvertimeHours,
               Map<String, BigDecimal> overtimeByRate, Map<String, BigDecimal> claims, Map<String, BigDecimal> claimsUnpaid) {
    }

    /** Requests for the month still waiting for approval: they'd change the export once decided. */
    record Waiting(int corrections, int overtime, int claims) {
    }

    record Summary(String month, boolean locked, boolean truncated, Waiting waiting, List<String> rates, List<String> currencies,
                   List<Row> rows) {
    }

    private final PayrollFeed attendance;
    private final ClaimsPayrollFeed claims;
    private final JdbcClient jdbc;

    PayrollExport(PayrollFeed attendance, ClaimsPayrollFeed claims, JdbcClient jdbc) {
        this.attendance = attendance;
        this.claims = claims;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    Summary build(YearMonth month) {
        PayrollFeed.Result feed = attendance.month(month);
        List<ClaimsPayrollFeed.ApprovedClaim> approved = claims.approvedIn(month);

        TreeSet<String> rates = new TreeSet<>();
        TreeSet<String> currencies = new TreeSet<>();
        Map<UUID, Row> rows = new LinkedHashMap<>();
        for (PayrollFeed.Month m : feed.people()) {
            Map<String, BigDecimal> byRate = new TreeMap<>();
            for (PayrollFeed.OvertimeAtRate o : m.overtimeByRate()) {
                String rate = o.rate() == null ? "unrated" : o.rate().setScale(1, RoundingMode.HALF_UP).toPlainString();
                rates.add(rate);
                byRate.merge(rate, o.hours(), BigDecimal::add);
            }
            rows.put(m.employeeId(), new Row(m.employeeId(), m.employeeNo(), m.employeeName(), m.department(), m.workingDays(),
                m.daysWorked(), m.hoursWorked(), m.lateCount(), m.lateMinutes(), m.earlyCount(), m.missedClockOuts(), m.absentDays(),
                m.leaveDays(), m.paidOvertimeHours(), m.weightedOvertimeHours(), m.timeOffOvertimeHours(), byRate,
                new TreeMap<>(), new TreeMap<>()));
        }
        for (ClaimsPayrollFeed.ApprovedClaim c : approved) {
            currencies.add(c.currency());
            Row r = rows.computeIfAbsent(c.employeeId(), id -> new Row(id, c.employeeNo(), c.employeeName(), null, 0, 0,
                BigDecimal.ZERO, 0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new TreeMap<>(), new TreeMap<>(), new TreeMap<>()));
            r.claims().merge(c.currency(), c.amount(), BigDecimal::add);
            if (!c.paid()) r.claimsUnpaid().merge(c.currency(), c.amount(), BigDecimal::add);
        }
        List<Row> sorted = new ArrayList<>(rows.values());
        sorted.sort((a, b) -> a.employeeName().compareToIgnoreCase(b.employeeName()));
        return new Summary(month.toString(), feed.locked(), feed.truncated(), waiting(month), List.copyOf(rates),
            List.copyOf(currencies), sorted);
    }

    private Waiting waiting(YearMonth month) {
        record Counts(int corrections, int overtime, int claims) {
        }
        Counts c = jdbc.sql("""
                select (select count(*) from attendance_correction where status = 'PENDING' and work_date between :from and :to) as corrections,
                       (select count(*) from overtime_request where status = 'PENDING' and work_date between :from and :to) as overtime,
                       (select count(*) from claim where status = 'PENDING' and expense_date between :from and :to) as claims""")
            .param("from", month.atDay(1)).param("to", month.atEndOfMonth()).query(Counts.class).single();
        return new Waiting(c.corrections(), c.overtime(), c.claims());
    }

    static String csv(Summary s) {
        StringBuilder out = new StringBuilder("﻿");
        List<String> head = new ArrayList<>(List.of("Employee no", "Name", "Department", "Working days", "Days worked", "Hours worked",
            "Late (times)", "Late (minutes)", "Left early (times)", "Missed clock-outs", "Absent days", "Leave days",
            "Overtime paid hours", "Overtime hours x rate", "Overtime as time off (hours)"));
        for (String r : s.rates()) head.add("Overtime hours at " + ("unrated".equals(r) ? "no rate" : r + "x"));
        for (String c : s.currencies()) {
            head.add("Claims approved " + c);
            head.add("Claims not yet paid " + c);
        }
        out.append(String.join(",", head)).append('\n');
        for (Row r : s.rows()) {
            List<String> cells = new ArrayList<>(List.of(cell(r.employeeNo()), cell(r.employeeName()), cell(r.department()),
                String.valueOf(r.workingDays()), String.valueOf(r.daysWorked()), num(r.hoursWorked()), String.valueOf(r.lateCount()),
                String.valueOf(r.lateMinutes()), String.valueOf(r.earlyCount()), String.valueOf(r.missedClockOuts()),
                String.valueOf(r.absentDays()), num(r.leaveDays()), num(r.paidOvertimeHours()), num(r.weightedOvertimeHours()),
                num(r.timeOffOvertimeHours())));
            for (String rate : s.rates()) cells.add(num(r.overtimeByRate().getOrDefault(rate, BigDecimal.ZERO)));
            for (String c : s.currencies()) {
                cells.add(money(r.claims().getOrDefault(c, BigDecimal.ZERO)));
                cells.add(money(r.claimsUnpaid().getOrDefault(c, BigDecimal.ZERO)));
            }
            out.append(String.join(",", cells)).append('\n');
        }
        return out.toString();
    }

    private static String num(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Quotes when needed, and stops spreadsheet formula injection (cells starting with = + - @). */
    static String cell(String value) {
        if (value == null) return "";
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) v = "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }
}
