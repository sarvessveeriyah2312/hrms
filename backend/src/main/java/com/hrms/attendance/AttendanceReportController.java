package com.hrms.attendance;

import com.hrms.shared.security.DataScope;
import com.hrms.shared.security.PermissionChecker;
import com.hrms.shared.web.ApiException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Attendance report ("See attendance reports", within its scope): a year, or one month of it, by person and month. */
@RestController
@RequestMapping("/api/v1/attendance/reports")
class AttendanceReportController {

    private final AttendanceReport report;
    private final PermissionChecker perm;

    AttendanceReportController(AttendanceReport report, PermissionChecker perm) {
        this.report = report;
        this.perm = perm;
    }

    @GetMapping
    @PreAuthorize("@perm.has('reports:attendance')")
    AttendanceReport.Report get(@RequestParam(required = false) Integer year, @RequestParam(required = false) Integer month,
                                @AuthenticationPrincipal Jwt jwt) {
        return build(year, month, jwt);
    }

    /** One line per person and month; paid overtime gets a column per rate used. */
    @GetMapping(value = "/csv", produces = "text/csv")
    @PreAuthorize("@perm.has('reports:attendance')")
    ResponseEntity<byte[]> csv(@RequestParam(required = false) Integer year, @RequestParam(required = false) Integer month,
                               @AuthenticationPrincipal Jwt jwt) {
        AttendanceReport.Report r = build(year, month, jwt);
        TreeSet<BigDecimal> rates = new TreeSet<>();
        r.rows().forEach(x -> x.paidByRate().forEach(p -> { if (p.rate() != null) rates.add(p.rate()); }));
        StringBuilder out = new StringBuilder("﻿Employee no,Name,Department,Site,Month,Locked,Working days,Days worked,Hours worked,"
            + "Late (times),Late (minutes),Left early (times),Left early (minutes),Missed clock-outs,Absent,Leave days,"
            + "Paid overtime hours");
        for (BigDecimal rate : rates) out.append(",Overtime at ").append(rate.toPlainString()).append("x (hours)");
        out.append(",Overtime pay hours (hours x rate),Overtime as time off (hours)\n");
        for (AttendanceReport.Row x : r.rows()) {
            var t = x.totals();
            out.append(String.join(",", cell(x.employeeNo()), cell(x.employeeName()), cell(x.department()), cell(x.site()), x.month(),
                x.locked() ? "Yes" : "No", String.valueOf(t.expectedDays()), String.valueOf(t.daysWorked()), hours(t.workedMin()),
                String.valueOf(t.lateCount()), String.valueOf(t.lateMin()), String.valueOf(t.earlyCount()), String.valueOf(t.earlyMin()),
                String.valueOf(t.missedOut()), String.valueOf(t.absent()), t.leaveDays().stripTrailingZeros().toPlainString(),
                hours(x.overtimePaidMin())));
            for (BigDecimal rate : rates) {
                int min = x.paidByRate().stream().filter(p -> rate.equals(p.rate())).mapToInt(AttendanceReport.RateMinutes::minutes).sum();
                out.append(',').append(hours(min));
            }
            out.append(',').append(hours(x.weightedMin())).append(',').append(hours(x.overtimeTimeOffMin())).append('\n');
        }
        String name = "attendance-" + r.year() + (r.month() == null ? "" : "-" + String.format("%02d", r.month())) + ".csv";
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
            .cacheControl(CacheControl.noStore())
            .body(out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private AttendanceReport.Report build(Integer yearParam, Integer month, Jwt jwt) {
        List<String> modules = jwt.getClaimAsStringList("modules");
        if (modules == null || !modules.contains("attendance")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MODULE_OFF", "Your company hasn't switched on Attendance.");
        }
        int year = yearParam == null ? LocalDate.now().getYear() : yearParam;
        if (year < 2000 || year > 2100) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_YEAR", "Choose a year between 2000 and 2100.");
        if (month != null && (month < 1 || month > 12)) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_MONTH", "Month must be 1 to 12.");
        DataScope scope = perm.scopeOf("reports:attendance").orElse(DataScope.TEAM);
        String me = jwt.getClaimAsString("employee_id");
        return report.build(me == null ? null : UUID.fromString(me), scope, year, month);
    }

    /** 7.5 for 450 minutes. */
    static String hours(int minutes) {
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** CSV cell: quoted when needed, and spreadsheet formulas neutralised. */
    static String cell(String value) {
        if (value == null) return "";
        String v = value;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }
}
