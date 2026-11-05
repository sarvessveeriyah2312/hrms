package com.hrms.claims;

import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
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
 * Claim types for HR: limits, mileage rate, receipts and the approval chain. Changes apply to new claims;
 * claims already in approval keep their chain. Every field is sent on save (a missing limit means no limit).
 */
@RestController
@RequestMapping("/api/v1/claims/settings")
class ClaimSettingsController {

    record TypeInput(String name, String tone, String kind, BigDecimal maxPerClaim, BigDecimal maxPerMonth,
                     BigDecimal ratePerKm, Boolean receiptRequired, Integer approvalLevels, Boolean hrApproval, Boolean active) {
    }

    private static final Set<String> TONES = Set.of("blue", "green", "orange", "teal", "purple", "pink");
    private static final BigDecimal MAX_LIMIT = new BigDecimal("1000000");
    private static final BigDecimal MAX_RATE = new BigDecimal("100");

    private final JdbcClient jdbc;
    private final ClaimsService claims;
    private final AuditLog audit;

    ClaimSettingsController(JdbcClient jdbc, ClaimsService claims, AuditLog audit) {
        this.jdbc = jdbc;
        this.claims = claims;
        this.audit = audit;
    }

    @GetMapping("/types")
    @PreAuthorize("@perm.has('claims:manage')")
    List<ClaimsService.ClaimType> types() {
        return claims.types(true);
    }

    @PostMapping("/types")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('claims:manage')")
    @Transactional
    ClaimsService.ClaimType create(@RequestBody TypeInput in, @AuthenticationPrincipal Jwt jwt) {
        Valid v = validate(in);
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into claim_type (id, tenant_id, code, name, tone, kind, max_per_claim, max_per_month, rate_per_km,
                                            receipt_required, approval_levels, hr_approval, active)
                    values (:id, :tenant, :code, :name, :tone, :kind, :perClaim, :perMonth, :rate, :receipt, :levels, :hr, true)""")
                .param("id", id).param("tenant", TenantContext.require()).param("code", codeFor(v.name(), id)).param("name", v.name())
                .param("tone", v.tone()).param("kind", v.kind()).param("perClaim", v.maxPerClaim()).param("perMonth", v.maxPerMonth())
                .param("rate", v.ratePerKm()).param("receipt", v.receiptRequired()).param("levels", v.levels()).param("hr", v.hr())
                .update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "CLAIM_TYPE_EXISTS", "There's already a claim type called " + v.name() + ".");
        }
        audit.record(actor(jwt), "CLAIM_TYPE_CREATED", "claim_type", id, Map.of("name", v.name()));
        return claims.type(id);
    }

    @PutMapping("/types/{id}")
    @PreAuthorize("@perm.has('claims:manage')")
    @Transactional
    ClaimsService.ClaimType update(@PathVariable UUID id, @RequestBody TypeInput in, @AuthenticationPrincipal Jwt jwt) {
        ClaimsService.ClaimType existing = claims.type(id);
        Valid v = validate(in);
        boolean used = jdbc.sql("select exists (select 1 from claim where claim_type_id = :id)").param("id", id).query(Boolean.class).single();
        if (used && !v.kind().equals(existing.kind())) {
            throw new ApiException(HttpStatus.CONFLICT, "KIND_LOCKED",
                "Claims have been made with this type, so it can't switch between receipt and mileage. Create a new type instead.");
        }
        jdbc.sql("""
                update claim_type set name = :name, tone = :tone, kind = :kind, max_per_claim = :perClaim, max_per_month = :perMonth,
                       rate_per_km = :rate, receipt_required = :receipt, approval_levels = :levels, hr_approval = :hr, active = :active
                where id = :id""")
            .param("name", v.name()).param("tone", v.tone()).param("kind", v.kind()).param("perClaim", v.maxPerClaim())
            .param("perMonth", v.maxPerMonth()).param("rate", v.ratePerKm()).param("receipt", v.receiptRequired())
            .param("levels", v.levels()).param("hr", v.hr()).param("active", in.active() == null ? existing.active() : in.active())
            .param("id", id).update();
        audit.record(actor(jwt), "CLAIM_TYPE_UPDATED", "claim_type", id, Map.of("name", v.name()));
        return claims.type(id);
    }

    /** Who would approve a claim of this type and amount from this person (for the workflow screen's "Try it"). */
    @GetMapping("/approval-preview")
    @PreAuthorize("@perm.has('workflow:manage') or @perm.has('claims:manage')")
    ClaimsService.ApprovalPreview approvalPreview(@RequestParam UUID employeeId, @RequestParam UUID claimTypeId,
                                                  @RequestParam(defaultValue = "0") BigDecimal amount) {
        boolean exists = jdbc.sql("select exists (select 1 from employee where id = :id)").param("id", employeeId)
            .query(Boolean.class).single();
        if (!exists) throw new ApiException(HttpStatus.NOT_FOUND, "UNKNOWN_EMPLOYEE", "That employee doesn't exist.");
        return claims.previewApproval(employeeId, claimTypeId, amount);
    }

    private record Valid(String name, String tone, String kind, BigDecimal maxPerClaim, BigDecimal maxPerMonth,
                         BigDecimal ratePerKm, boolean receiptRequired, int levels, boolean hr) {
    }

    private static Valid validate(TypeInput in) {
        if (in == null || in.name() == null || in.name().isBlank() || in.name().trim().length() > 60) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NAME_REQUIRED", "Give the claim type a name of up to 60 characters.");
        }
        String kind = "MILEAGE".equals(in.kind()) ? "MILEAGE" : "RECEIPT";
        BigDecimal rate = null;
        if ("MILEAGE".equals(kind)) {
            if (in.ratePerKm() == null || in.ratePerKm().signum() <= 0 || in.ratePerKm().compareTo(MAX_RATE) > 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "RATE_REQUIRED", "Set the rate paid per km (more than 0).");
            }
            rate = in.ratePerKm();
        }
        BigDecimal perClaim = limit(in.maxPerClaim(), "per claim");
        BigDecimal perMonth = limit(in.maxPerMonth(), "per month");
        if (perClaim != null && perMonth != null && perClaim.compareTo(perMonth) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LIMITS_REVERSED", "The limit per claim can't be more than the limit per month.");
        }
        int levels = in.approvalLevels() == null ? 1 : in.approvalLevels();
        if (levels < 0 || levels > 2) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_LEVELS", "Manager levels must be 0, 1 or 2.");
        return new Valid(in.name().trim(), in.tone() != null && TONES.contains(in.tone()) ? in.tone() : "orange", kind, perClaim,
            perMonth, rate, "RECEIPT".equals(kind) && (in.receiptRequired() == null || in.receiptRequired()), levels,
            in.hrApproval() != null && in.hrApproval());
    }

    /** Null or zero means no limit. */
    private static BigDecimal limit(BigDecimal value, String what) {
        if (value == null || value.signum() == 0) return null;
        if (value.signum() < 0 || value.compareTo(MAX_LIMIT) >= 0 || value.scale() > 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_LIMIT", "The limit " + what + " must be a positive amount with up to two decimals.");
        }
        return value;
    }

    private static String codeFor(String name, UUID id) {
        String code = name.toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        // Names in other scripts (e.g. Chinese) give no Latin code; use part of the id instead.
        return code.isEmpty() ? "TYPE_" + id.toString().substring(0, 8).toUpperCase() : code;
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
