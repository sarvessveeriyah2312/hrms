package com.hrms.attendance;

import com.hrms.shared.security.DataScope;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What payroll needs from attendance for one month, everyone in the company: the timesheet totals (the same rules as
 * timesheets and the attendance report) and approved overtime by rate. Used by the payroll export.
 */
@Component
public class PayrollFeed {

    /** Paid overtime hours at one rate (rate null: sent before rates were required). */
    public record OvertimeAtRate(BigDecimal rate, BigDecimal hours) {
    }

    /**
     * One person's month. weightedOvertimeHours: paid overtime hours × rate (what payroll pays for).
     * locked: the month is locked, so these figures can't change any more.
     */
    public record Month(UUID employeeId, String employeeNo, String employeeName, String department, int workingDays,
                        int daysWorked, BigDecimal hoursWorked, int lateCount, int lateMinutes, int earlyCount, int missedClockOuts,
                        int absentDays, BigDecimal leaveDays, BigDecimal paidOvertimeHours, BigDecimal weightedOvertimeHours,
                        BigDecimal timeOffOvertimeHours, List<OvertimeAtRate> overtimeByRate, boolean locked) {
    }

    /** truncated: the company is larger than one report can hold (see the attendance report). */
    public record Result(List<Month> people, boolean locked, boolean truncated) {
    }

    private final AttendanceReport report;
    private final PeriodLocks locks;

    PayrollFeed(AttendanceReport report, PeriodLocks locks) {
        this.report = report;
        this.locks = locks;
    }

    @Transactional(readOnly = true)
    public Result month(YearMonth month) {
        AttendanceReport.Report r = report.build(null, DataScope.TENANT, month.getYear(), month.getMonthValue());
        boolean locked = locks.lockedMonths(month.atDay(1), month.atEndOfMonth()).contains(month);
        List<Month> people = r.rows().stream().map(row -> {
            TimesheetService.Totals t = row.totals();
            return new Month(row.employeeId(), row.employeeNo(), row.employeeName(), row.department(), t.expectedDays(), t.daysWorked(),
                hours(t.workedMin()), t.lateCount(), t.lateMin(), t.earlyCount(), t.missedOut(), t.absent(), t.leaveDays(),
                hours(row.overtimePaidMin()), hours(row.weightedMin()), hours(row.overtimeTimeOffMin()),
                row.paidByRate().stream().map(x -> new OvertimeAtRate(x.rate(), hours(x.minutes()))).toList(), row.locked());
        }).toList();
        return new Result(people, locked, r.truncated());
    }

    private static BigDecimal hours(int minutes) {
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP);
    }
}
