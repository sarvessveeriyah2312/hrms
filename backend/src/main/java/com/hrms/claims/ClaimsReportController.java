package com.hrms.claims;

import com.hrms.shared.security.DataScope;
import com.hrms.shared.security.EmployeeScope;
import com.hrms.shared.security.PermissionChecker;
import com.hrms.shared.web.ApiException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
 * Claims report: what people claimed in a year (or one month of it), by person and claim type, within the scope of
 * "See claims reports". Amounts stay in each claimant's currency, so totals are kept per currency. Withdrawn and rejected
 * claims aren't counted. Dates are expense dates.
 */
@RestController
@RequestMapping("/api/v1/claims/reports")
class ClaimsReportController {

    /**
     * One person, claim type, currency and month: claims counted, and amounts approved, waiting, paid and
     * approved-but-unpaid. The app rolls these up by person, by type and by month, after its own filters.
     */
    record Row(UUID employeeId, String employeeNo, String employeeName, String department, UUID claimTypeId, String claimType,
               String currency, int month, int claims, BigDecimal approved, BigDecimal pending, BigDecimal paid, BigDecimal toPay) {
    }

    record Report(int year, Integer month, DataScope scope, List<Row> rows) {
    }

    private final JdbcClient jdbc;
    private final PermissionChecker perm;

    ClaimsReportController(JdbcClient jdbc, PermissionChecker perm) {
        this.jdbc = jdbc;
        this.perm = perm;
    }

    @GetMapping
    @PreAuthorize("@perm.has('reports:claims')")
    Report report(@RequestParam(required = false) Integer year, @RequestParam(required = false) Integer month,
                  @RequestParam(required = false) UUID claimTypeId, @AuthenticationPrincipal Jwt jwt) {
        return build(year, month, claimTypeId, jwt);
    }

    @GetMapping(value = "/csv", produces = "text/csv")
    @PreAuthorize("@perm.has('reports:claims')")
    ResponseEntity<byte[]> csv(@RequestParam(required = false) Integer year, @RequestParam(required = false) Integer month,
                               @RequestParam(required = false) UUID claimTypeId, @AuthenticationPrincipal Jwt jwt) {
        Report r = build(year, month, claimTypeId, jwt);
        Map<String, Row> byPerson = new LinkedHashMap<>();
        for (Row x : r.rows()) {
            byPerson.merge(x.employeeId() + "|" + x.claimTypeId() + "|" + x.currency(), x, (a, b) -> new Row(a.employeeId(), a.employeeNo(),
                a.employeeName(), a.department(), a.claimTypeId(), a.claimType(), a.currency(), 0, a.claims() + b.claims(),
                a.approved().add(b.approved()), a.pending().add(b.pending()), a.paid().add(b.paid()), a.toPay().add(b.toPay())));
        }
        StringBuilder out = new StringBuilder("\uFEFFEmployee no,Name,Department,Claim type,Currency,Claims,Approved,Waiting,Paid,To pay\n");
        for (Row l : byPerson.values()) {
            out.append(String.join(",", ClaimsController.cell(l.employeeNo()), ClaimsController.cell(l.employeeName()),
                ClaimsController.cell(l.department()), ClaimsController.cell(l.claimType()), l.currency(), String.valueOf(l.claims()),
                ClaimApprovals.amount(l.approved()), ClaimApprovals.amount(l.pending()), ClaimApprovals.amount(l.paid()),
                ClaimApprovals.amount(l.toPay()))).append('\n');
        }
        String name = "claims-" + r.year() + (r.month() == null ? "" : "-" + String.format("%02d", r.month())) + ".csv";
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
            .cacheControl(CacheControl.noStore())
            .body(out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Report build(Integer yearParam, Integer month, UUID claimTypeId, Jwt jwt) {
        List<String> modules = jwt.getClaimAsStringList("modules");
        if (modules == null || !modules.contains("claims")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MODULE_OFF", "Your company hasn't switched on Claims.");
        }
        int year = yearParam == null ? LocalDate.now().getYear() : yearParam;
        if (year < 2000 || year > 2100) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_YEAR", "Choose a year between 2000 and 2100.");
        if (month != null && (month < 1 || month > 12)) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_MONTH", "Month must be 1 to 12.");
        LocalDate from = month == null ? LocalDate.of(year, 1, 1) : LocalDate.of(year, month, 1);
        LocalDate to = month == null ? LocalDate.of(year, 12, 31) : from.withDayOfMonth(from.lengthOfMonth());

        DataScope scope = perm.scopeOf("reports:claims").orElse(DataScope.TEAM);
        String me = jwt.getClaimAsString("employee_id");

        // One row per person, type, currency and month; the report rolls them up three ways.
        List<Row> rows = jdbc.sql("""
                select e.id as employee_id, e.employee_no, e.full_name as employee_name, d.name as department,
                       t.id as claim_type_id, t.name as claim_type, c.currency, extract(month from c.expense_date)::int as month,
                       count(*)::int as claims,
                       coalesce(sum(c.amount) filter (where c.status = 'APPROVED'), 0) as approved,
                       coalesce(sum(c.amount) filter (where c.status = 'PENDING'), 0) as pending,
                       coalesce(sum(c.amount) filter (where c.status = 'APPROVED' and c.paid_at is not null), 0) as paid,
                       coalesce(sum(c.amount) filter (where c.status = 'APPROVED' and c.paid_at is null), 0) as to_pay
                from claim c
                join employee e on e.id = c.employee_id
                join claim_type t on t.id = c.claim_type_id
                left join department d on d.id = e.department_id
                where %s and c.status in ('APPROVED', 'PENDING') and c.expense_date between :from and :to
                  and (cast(:type as uuid) is null or c.claim_type_id = :type)
                group by e.id, e.employee_no, e.full_name, d.name, t.id, t.name, c.currency, extract(month from c.expense_date)
                order by e.full_name, t.name""".formatted(EmployeeScope.predicate(scope, "e")))
            .param("me", me == null ? null : UUID.fromString(me)).param("from", from).param("to", to).param("type", claimTypeId)
            .query(Row.class).list();

        // Currency is char(3); trim the padding.
        return new Report(year, month, scope, rows.stream().map(r -> new Row(r.employeeId(), r.employeeNo(), r.employeeName(),
            r.department(), r.claimTypeId(), r.claimType(), r.currency().trim(), r.month(), r.claims(), r.approved(), r.pending(),
            r.paid(), r.toPay())).toList());
    }
}
