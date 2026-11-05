package com.hrms.claims;

import com.hrms.files.FileStore;
import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.security.DataScope;
import com.hrms.shared.security.PermissionChecker;
import com.hrms.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Claims for the signed-in employee (submit, own claims, monthly limits), for the people their claims:view scope
 * covers, and for HR (mark paid, payroll export).
 */
@RestController
@RequestMapping("/api/v1/claims")
class ClaimsController {

    record Team(DataScope scope, List<ClaimsService.Claim> claims) {
    }

    /** Monthly limits for the signed-in person, in their currency. */
    record UsageReport(String currency, String month, List<ClaimsService.Usage> types) {
    }

    record PayInput(List<UUID> claimIds, String reference) {
    }

    record PayResult(int paid) {
    }

    private final ClaimsService claims;
    private final PermissionChecker perm;
    private final FileStore files;
    private final AuditLog audit;

    ClaimsController(ClaimsService claims, PermissionChecker perm, FileStore files, AuditLog audit) {
        this.claims = claims;
        this.perm = perm;
        this.files = files;
        this.audit = audit;
    }

    private static UUID me(Jwt jwt) {
        String id = jwt.getClaimAsString("employee_id");
        if (id == null) throw new ApiException(HttpStatus.FORBIDDEN, "NO_EMPLOYEE_RECORD", "Your account has no employee record, so it has no claims.");
        return UUID.fromString(id);
    }

    private static UUID meOrNull(Jwt jwt) {
        String id = jwt.getClaimAsString("employee_id");
        return id == null ? null : UUID.fromString(id);
    }

    private DataScope viewScope() {
        return perm.scopeOf("claims:view").orElse(DataScope.SELF);
    }

    @GetMapping("/types")
    @PreAuthorize("@perm.has('claims:submit') or @perm.has('claims:view')")
    List<ClaimsService.ClaimType> types() {
        return claims.types(false);
    }

    @GetMapping("/mine")
    @PreAuthorize("@perm.has('claims:submit') or @perm.has('claims:view')")
    List<ClaimsService.Claim> mine(@RequestParam(required = false) Integer year, @AuthenticationPrincipal Jwt jwt) {
        return claims.mine(me(jwt), year == null ? LocalDate.now().getYear() : year);
    }

    /** Spending this month (or {@code month=2026-11}) against each type's monthly limit. */
    @GetMapping("/usage")
    @PreAuthorize("@perm.has('claims:submit')")
    UsageReport usage(@RequestParam(required = false) String month, @AuthenticationPrincipal Jwt jwt) {
        YearMonth m = month(month);
        UUID me = me(jwt);
        return new UsageReport(claims.currencyOf(me), m.toString(), claims.usage(me, m));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('claims:submit')")
    ClaimsService.Claim submit(@RequestBody ClaimsService.ClaimInput in, @AuthenticationPrincipal Jwt jwt) {
        return claims.submit(me(jwt), in);
    }

    /** Edit a claim that was sent back for changes; it goes through approval again. */
    @PutMapping("/{id}")
    @PreAuthorize("@perm.has('claims:submit')")
    ClaimsService.Claim resubmit(@PathVariable UUID id, @RequestBody ClaimsService.ClaimInput in, @AuthenticationPrincipal Jwt jwt) {
        return claims.resubmit(me(jwt), id, in);
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('claims:submit')")
    void cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        claims.cancel(me(jwt), id);
    }

    /** One claim with its approval timeline: yours, someone your claims:view scope covers, or one you may approve. */
    @GetMapping("/{id}")
    @PreAuthorize("@perm.has('claims:submit') or @perm.has('claims:view') or @perm.has('claims:approve')")
    ClaimsService.ClaimDetail detail(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        ClaimsService.ClaimDetail detail = claims.detail(id);
        UUID me = meOrNull(jwt);
        UUID owner = detail.claim().employeeId();
        boolean allowed = claims.canSee(me, viewScope(), owner)
            || (perm.has("claims:approve") && claims.canSee(me, perm.scopeOf("claims:approve").orElse(DataScope.SELF), owner))
            || perm.has("claims:manage");
        if (!allowed) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You can't see this person's claims.");
        return detail;
    }

    /** The receipt, for anyone who may see the claim. */
    @GetMapping("/{id}/receipt")
    @PreAuthorize("@perm.has('claims:submit') or @perm.has('claims:view') or @perm.has('claims:approve')")
    ResponseEntity<byte[]> receipt(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        var r = detail(id, jwt).claim().receipt();
        if (r == null) throw new ApiException(HttpStatus.NOT_FOUND, "NO_RECEIPT", "This claim has no receipt.");
        var file = files.read(r.id()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_RECEIPT", "The receipt is missing."));
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.meta().contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(file.meta().name()).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .cacheControl(CacheControl.noStore())
            .body(file.data());
    }

    /** Other people's claims within your claims:view scope. status: PENDING, APPROVED, UNPAID, PAID, REJECTED or all. */
    @GetMapping("/team")
    @PreAuthorize("@perm.has('claims:view')")
    Team team(@RequestParam(required = false) String status,
              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
              @AuthenticationPrincipal Jwt jwt) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(90) : from;
        if (end.isBefore(start) || end.isAfter(start.plusDays(400))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_RANGE", "Choose a range of up to a year.");
        }
        DataScope scope = viewScope();
        return new Team(scope, claims.others(meOrNull(jwt), scope, status, start, end));
    }

    /** HR marks approved claims as paid. */
    @PostMapping("/pay")
    @PreAuthorize("@perm.has('claims:manage')")
    PayResult pay(@RequestBody PayInput in, @AuthenticationPrincipal Jwt jwt) {
        UUID actor = UUID.fromString(jwt.getSubject());
        int paid = claims.markPaid(actor, in == null ? null : in.claimIds(), in == null ? null : in.reference());
        audit.record(actor, "CLAIMS_PAID", "claim", null,
            Map.of("count", paid, "reference", in.reference() == null ? "" : in.reference().trim()));
        return new PayResult(paid);
    }

    /** Approved claims for a month (by expense date), for payroll. */
    @GetMapping(value = "/export", produces = "text/csv")
    @PreAuthorize("@perm.has('claims:manage')")
    ResponseEntity<byte[]> export(@RequestParam(required = false) String month) {
        YearMonth m = month(month);
        StringBuilder out = new StringBuilder("﻿Employee no,Name,Claim type,Expense date,Description,Merchant,Distance km,Amount,Currency,Approved,Paid,Payment ref\n");
        for (ClaimsService.Claim c : claims.approvedIn(m)) {
            out.append(String.join(",", cell(c.employeeNo()), cell(c.employeeName()), cell(c.typeName()), c.expenseDate().toString(),
                cell(c.description()), cell(c.merchant()), c.distanceKm() == null ? "" : c.distanceKm().toPlainString(),
                ClaimApprovals.amount(c.amount()), c.currency(), c.decidedAt() == null ? "" : c.decidedAt().toString().substring(0, 10),
                c.paidAt() == null ? "" : c.paidAt().toString().substring(0, 10), cell(c.paymentRef()))).append('\n');
        }
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("claims-" + m + ".csv").build().toString())
            .cacheControl(CacheControl.noStore())
            .body(out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static YearMonth month(String value) {
        if (value == null || value.isBlank()) return YearMonth.now();
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_MONTH", "Give the month as YYYY-MM.");
        }
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
