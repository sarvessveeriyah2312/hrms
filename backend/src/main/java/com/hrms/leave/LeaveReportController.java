package com.hrms.leave;

import com.hrms.shared.security.DataScope;
import com.hrms.shared.security.EmployeeScope;
import com.hrms.shared.security.PermissionChecker;
import com.hrms.shared.web.ApiException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Leave report: for each person and leave type, entitled, carried, taken, waiting and left, plus totals.
 * Who appears follows the scope of reports:leave (team, department, entity or company); the reports module
 * must be switched on for the permission to be in the token at all.
 */
@RestController
@RequestMapping("/api/v1/leave/reports")
class LeaveReportController {

    record Line(UUID employeeId, String employeeNo, String employeeName, String department, UUID leaveTypeId,
                String leaveType, BigDecimal entitled, BigDecimal carried, BigDecimal used, BigDecimal pending,
                BigDecimal available, String basis) {
    }

    record Total(UUID leaveTypeId, String leaveType, BigDecimal entitled, BigDecimal used, BigDecimal pending, int people) {
    }

    record Report(int year, DataScope scope, List<Line> lines, List<Total> totals) {
    }

    private final JdbcClient jdbc;
    private final LeaveService leave;
    private final PermissionChecker perm;

    LeaveReportController(JdbcClient jdbc, LeaveService leave, PermissionChecker perm) {
        this.jdbc = jdbc;
        this.leave = leave;
        this.perm = perm;
    }

    @GetMapping
    @PreAuthorize("@perm.has('reports:leave')")
    Report report(@RequestParam(required = false) Integer year, @RequestParam(required = false) UUID departmentId,
                  @RequestParam(required = false) UUID leaveTypeId, @AuthenticationPrincipal Jwt jwt) {
        return build(year, departmentId, leaveTypeId, jwt);
    }

    @GetMapping(value = "/csv", produces = "text/csv")
    @PreAuthorize("@perm.has('reports:leave')")
    ResponseEntity<byte[]> csv(@RequestParam(required = false) Integer year, @RequestParam(required = false) UUID departmentId,
                               @RequestParam(required = false) UUID leaveTypeId, @AuthenticationPrincipal Jwt jwt) {
        Report r = build(year, departmentId, leaveTypeId, jwt);
        StringBuilder out = new StringBuilder("﻿Employee no,Name,Department,Leave type,Entitled,Carried,Taken,Waiting,Left,Basis\n");
        for (Line l : r.lines()) {
            out.append(String.join(",", csvCell(l.employeeNo()), csvCell(l.employeeName()), csvCell(l.department()),
                csvCell(l.leaveType()), num(l.entitled()), num(l.carried()), num(l.used()), num(l.pending()),
                num(l.available()), csvCell(l.basis()))).append('\n');
        }
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("leave-" + r.year() + ".csv").build().toString())
            .cacheControl(CacheControl.noStore())
            .body(out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Report build(Integer yearParam, UUID departmentId, UUID leaveTypeId, Jwt jwt) {
        List<String> modules = jwt.getClaimAsStringList("modules");
        if (modules == null || !modules.contains("leave")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MODULE_OFF", "Your company hasn't switched on Leave.");
        }
        int year = yearParam == null ? LocalDate.now().getYear() : yearParam;
        DataScope scope = perm.scopeOf("reports:leave").orElse(DataScope.TEAM);
        String me = jwt.getClaimAsString("employee_id");
        record Person(UUID id, String employeeNo, String fullName, String department) {
        }
        List<Person> people = jdbc.sql("""
                select e.id, e.employee_no, e.full_name, d.name as department
                from employee e left join department d on d.id = e.department_id
                where e.status = 'ACTIVE' and %s and (cast(:dept as uuid) is null or e.department_id = :dept)
                order by e.full_name""".formatted(EmployeeScope.predicate(scope, "e")))
            .param("me", me == null ? null : UUID.fromString(me)).param("dept", departmentId)
            .query(Person.class).list();

        List<LeaveService.LeaveType> types = leave.types(false).stream()
            .filter(t -> leaveTypeId == null || t.id().equals(leaveTypeId)).toList();
        List<Line> lines = new ArrayList<>();
        Map<UUID, Total> totals = new LinkedHashMap<>();
        for (Person p : people) {
            for (LeaveService.Balance b : leave.balances(p.id(), year, types)) {
                lines.add(new Line(p.id(), p.employeeNo(), p.fullName(), p.department(), b.leaveTypeId(), b.name(),
                    b.trackBalance() ? b.entitled() : null, b.trackBalance() ? b.carried() : null, b.used(), b.pending(),
                    b.available(), b.basis()));
                totals.merge(b.leaveTypeId(),
                    new Total(b.leaveTypeId(), b.name(), b.trackBalance() ? b.entitled().add(b.carried()) : BigDecimal.ZERO,
                        b.used(), b.pending(), b.used().signum() > 0 ? 1 : 0),
                    (x, y) -> new Total(x.leaveTypeId(), x.leaveType(), x.entitled().add(y.entitled()), x.used().add(y.used()),
                        x.pending().add(y.pending()), x.people() + y.people()));
            }
        }
        return new Report(year, scope, lines, List.copyOf(totals.values()));
    }

    /** Quotes when needed, and stops spreadsheet formula injection (cells starting with = + - @). */
    static String csvCell(String value) {
        if (value == null) return "";
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) v = "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }

    private static String num(BigDecimal n) {
        return n == null ? "" : n.stripTrailingZeros().toPlainString();
    }
}
