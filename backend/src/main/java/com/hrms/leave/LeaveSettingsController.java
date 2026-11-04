package com.hrms.leave;

import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Leave types (entitlement and approval chain) and public holidays. Needs leave:manage. */
@RestController
@RequestMapping("/api/v1/leave/settings")
class LeaveSettingsController {

    record TypeInput(String name, String tone, Boolean paid, Boolean trackBalance, BigDecimal defaultDays,
                     Boolean halfDayAllowed, Integer approvalLevels, Boolean hrApproval, Boolean active,
                     Boolean proRate, BigDecimal carryMax, List<EntitlementPolicy.Tier> serviceTiers,
                     Integer carryExpiryMonth, Boolean requiresAttachment) {
    }

    record WorkWeek(UUID entityId, String name, List<Integer> workDays) {
    }

    record WorkWeekInput(List<Integer> workDays) {
    }

    record HolidayInput(LocalDate date, String name) {
    }

    private static final Set<String> TONES = Set.of("blue", "green", "orange", "teal", "purple", "pink");

    private final JdbcClient jdbc;
    private final LeaveService leave;
    private final LeaveEntitlements entitlements;
    private final AuditLog audit;

    LeaveSettingsController(JdbcClient jdbc, LeaveService leave, LeaveEntitlements entitlements, AuditLog audit) {
        this.jdbc = jdbc;
        this.leave = leave;
        this.entitlements = entitlements;
        this.audit = audit;
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    @GetMapping("/types")
    @PreAuthorize("@perm.has('leave:manage')")
    List<LeaveService.LeaveType> types() {
        return leave.types(true);
    }

    @PostMapping("/types")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('leave:manage')")
    @Transactional
    LeaveService.LeaveType create(@RequestBody TypeInput in, @AuthenticationPrincipal Jwt jwt) {
        String name = requireName(in.name());
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into leave_type (id, tenant_id, code, name, tone, paid, track_balance, default_days, half_day_allowed,
                                            approval_levels, hr_approval, active, pro_rate, carry_max, carry_expiry_month,
                                            requires_attachment)
                    values (:id, :tenant, :code, :name, :tone, :paid, :track, :days, :half, :levels, :hr, true, :prorate, :carry,
                            :expiry, :attach)""")
                .param("id", id).param("tenant", TenantContext.require()).param("code", codeFor(name)).param("name", name)
                .param("tone", tone(in.tone())).param("paid", or(in.paid(), true)).param("track", or(in.trackBalance(), true))
                .param("days", days(in.defaultDays())).param("half", or(in.halfDayAllowed(), true))
                .param("levels", levels(in.approvalLevels())).param("hr", or(in.hrApproval(), false))
                .param("prorate", or(in.proRate(), false)).param("carry", days(in.carryMax()))
                .param("expiry", month(in.carryExpiryMonth())).param("attach", or(in.requiresAttachment(), false)).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "LEAVE_TYPE_EXISTS", "There's already a leave type called " + name + ".");
        }
        writeTiers(id, in.serviceTiers());
        audit.record(actor(jwt), "LEAVE_TYPE_CREATED", "leave_type", id, Map.of("name", name));
        return leave.type(id);
    }

    /** Changes apply to new requests; requests already in approval keep their chain. */
    @PutMapping("/types/{id}")
    @PreAuthorize("@perm.has('leave:manage')")
    @Transactional
    LeaveService.LeaveType update(@PathVariable UUID id, @RequestBody TypeInput in, @AuthenticationPrincipal Jwt jwt) {
        LeaveService.LeaveType t = leave.type(id);
        String name = requireName(in.name());
        jdbc.sql("""
                update leave_type set name = :name, tone = :tone, paid = :paid, track_balance = :track, default_days = :days,
                       half_day_allowed = :half, approval_levels = :levels, hr_approval = :hr, active = :active,
                       pro_rate = :prorate, carry_max = :carry, carry_expiry_month = :expiry, requires_attachment = :attach
                where id = :id""")
            .param("name", name).param("tone", tone(in.tone() == null ? t.tone() : in.tone()))
            .param("paid", or(in.paid(), t.paid())).param("track", or(in.trackBalance(), t.trackBalance()))
            .param("days", in.defaultDays() == null ? t.defaultDays() : days(in.defaultDays()))
            .param("half", or(in.halfDayAllowed(), t.halfDayAllowed()))
            .param("levels", in.approvalLevels() == null ? t.approvalLevels() : levels(in.approvalLevels()))
            .param("hr", or(in.hrApproval(), t.hrApproval())).param("active", or(in.active(), t.active()))
            .param("prorate", or(in.proRate(), t.proRate()))
            .param("carry", in.carryMax() == null ? t.carryMax() : days(in.carryMax()))
            .param("expiry", in.carryExpiryMonth() == null ? t.carryExpiryMonth() : month(in.carryExpiryMonth()))
            .param("attach", or(in.requiresAttachment(), t.requiresAttachment()))
            .param("id", id).update();
        if (in.serviceTiers() != null) writeTiers(id, in.serviceTiers());
        audit.record(actor(jwt), "LEAVE_TYPE_UPDATED", "leave_type", id, Map.of("name", name));
        return leave.type(id);
    }

    // ---------- per-person entitlements ----------

    /** A person's balances with the policy figure and HR's figure side by side. */
    @GetMapping("/entitlements/{employeeId}")
    @PreAuthorize("@perm.has('leave:manage')")
    List<LeaveService.Balance> entitlements(@PathVariable UUID employeeId, @RequestParam(required = false) Integer year) {
        return leave.balances(employeeId, year == null ? LocalDate.now().getYear() : year);
    }

    @PutMapping("/entitlements/{employeeId}/{typeId}")
    @PreAuthorize("@perm.has('leave:manage')")
    List<LeaveService.Balance> setEntitlement(@PathVariable UUID employeeId, @PathVariable UUID typeId,
                                              @RequestParam int year, @RequestBody LeaveEntitlements.Override in,
                                              @AuthenticationPrincipal Jwt jwt) {
        return entitlements.set(actor(jwt), employeeId, typeId, year, in);
    }

    @DeleteMapping("/entitlements/{employeeId}/{typeId}")
    @PreAuthorize("@perm.has('leave:manage')")
    List<LeaveService.Balance> resetEntitlement(@PathVariable UUID employeeId, @PathVariable UUID typeId,
                                                @RequestParam int year, @AuthenticationPrincipal Jwt jwt) {
        return entitlements.reset(actor(jwt), employeeId, typeId, year);
    }

    // ---------- year-end carry-over ----------

    @GetMapping("/year-end")
    @PreAuthorize("@perm.has('leave:manage')")
    List<LeaveEntitlements.YearEndRun> yearEndRuns() {
        return entitlements.runs();
    }

    @PostMapping("/year-end/{year}")
    @PreAuthorize("@perm.has('leave:manage')")
    LeaveEntitlements.YearEndResult runYearEnd(@PathVariable int year, @AuthenticationPrincipal Jwt jwt) {
        return entitlements.runYearEnd(actor(jwt), year);
    }

    // ---------- approval preview (for the approval workflow builder) ----------

    /** Which workflow a request would use and who would approve it, without creating anything. */
    @GetMapping("/approval-preview")
    @PreAuthorize("@perm.has('workflow:manage') or @perm.has('leave:manage')")
    LeaveService.ApprovalPreview approvalPreview(@RequestParam UUID employeeId, @RequestParam UUID leaveTypeId,
                                                 @RequestParam(defaultValue = "1") BigDecimal days) {
        boolean exists = jdbc.sql("select exists (select 1 from employee where id = :id)").param("id", employeeId)
            .query(Boolean.class).single();
        if (!exists) throw new ApiException(HttpStatus.NOT_FOUND, "UNKNOWN_EMPLOYEE", "That employee doesn't exist.");
        return leave.previewApproval(employeeId, leaveTypeId, days);
    }

    // ---------- working week ----------

    @GetMapping("/work-week")
    @PreAuthorize("@perm.has('leave:manage')")
    List<WorkWeek> workWeeks() {
        return jdbc.sql("select id, name, work_days from legal_entity order by created_at")
            .query((rs, i) -> new WorkWeek(rs.getObject("id", UUID.class), rs.getString("name"),
                java.util.Arrays.stream((Object[]) rs.getArray("work_days").getArray()).map(d -> ((Number) d).intValue()).sorted().toList()))
            .list();
    }

    /** ISO days, 1 = Monday ... 7 = Sunday. Applies to leave counted from now on; existing requests keep their days. */
    @PutMapping("/work-week/{entityId}")
    @PreAuthorize("@perm.has('leave:manage')")
    List<WorkWeek> setWorkWeek(@PathVariable UUID entityId, @RequestBody WorkWeekInput in, @AuthenticationPrincipal Jwt jwt) {
        List<Integer> days = in.workDays() == null ? List.of() : in.workDays().stream().distinct().sorted().toList();
        if (days.isEmpty() || days.stream().anyMatch(d -> d < 1 || d > 7)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_WORK_WEEK", "Choose at least one working day.");
        }
        int n = jdbc.sql("update legal_entity set work_days = cast(:days as smallint[]) where id = :id")
            .param("days", days.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",", "{", "}")))
            .param("id", entityId).update();
        if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "UNKNOWN_ENTITY", "That legal entity doesn't exist.");
        audit.record(actor(jwt), "WORK_WEEK_CHANGED", "legal_entity", entityId, Map.of("workDays", days.toString()));
        return workWeeks();
    }

    // ---------- holidays ----------

    @GetMapping("/holidays")
    @PreAuthorize("@perm.has('leave:manage')")
    List<LeaveService.Holiday> holidays() {
        int year = LocalDate.now().getYear();
        return leave.holidays(LocalDate.of(year - 1, 1, 1), LocalDate.of(year + 1, 12, 31));
    }

    @PostMapping("/holidays")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('leave:manage')")
    LeaveService.Holiday addHoliday(@RequestBody HolidayInput in, @AuthenticationPrincipal Jwt jwt) {
        if (in.date() == null || in.name() == null || in.name().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FIELDS_REQUIRED", "Enter the date and the holiday's name.");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("insert into public_holiday (id, tenant_id, date, name) values (:id, :tenant, :date, :name)")
                .param("id", id).param("tenant", TenantContext.require()).param("date", in.date()).param("name", in.name().trim()).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "HOLIDAY_EXISTS", "There's already a holiday on that date.");
        }
        audit.record(actor(jwt), "HOLIDAY_ADDED", "public_holiday", id, Map.of("date", in.date().toString(), "name", in.name().trim()));
        return new LeaveService.Holiday(id, in.date(), in.name().trim());
    }

    @DeleteMapping("/holidays/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('leave:manage')")
    void deleteHoliday(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        int n = jdbc.sql("delete from public_holiday where id = :id").param("id", id).update();
        if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "HOLIDAY_NOT_FOUND", "That holiday doesn't exist.");
        audit.record(actor(jwt), "HOLIDAY_REMOVED", "public_holiday", id, Map.of());
    }

    /** Replaces a type's service tiers. Years 1–50, days 0–365, one tier per year count. */
    private void writeTiers(UUID typeId, List<EntitlementPolicy.Tier> tiers) {
        jdbc.sql("delete from leave_service_tier where leave_type_id = :id").param("id", typeId).update();
        if (tiers == null) return;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (EntitlementPolicy.Tier t : tiers) {
            if (t.minYears() < 1 || t.minYears() > 50 || !seen.add(t.minYears())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_TIER", "Each service tier needs a different number of years, from 1 to 50.");
            }
            jdbc.sql("insert into leave_service_tier (leave_type_id, tenant_id, min_years, days) values (:type, :tenant, :years, :days)")
                .param("type", typeId).param("tenant", TenantContext.require()).param("years", t.minYears()).param("days", days(t.days())).update();
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 60) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NAME_REQUIRED", "Give the leave type a name of up to 60 characters.");
        }
        return name.trim();
    }

    private static String codeFor(String name) {
        return name.toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
    }

    private static String tone(String tone) {
        return tone != null && TONES.contains(tone) ? tone : "teal";
    }

    private static BigDecimal days(BigDecimal d) {
        if (d == null) return BigDecimal.ZERO;
        if (d.signum() < 0 || d.compareTo(BigDecimal.valueOf(365)) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_ENTITLEMENT", "Yearly days must be between 0 and 365.");
        }
        return d;
    }

    /** null or 0: carried days never expire. */
    private static Integer month(Integer m) {
        if (m == null || m == 0) return null;
        if (m < 1 || m > 12) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_MONTH", "Choose a month from January to December.");
        return m;
    }

    private static int levels(Integer l) {
        if (l == null) return 1;
        if (l < 0 || l > 2) throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_LEVELS", "Choose 0, 1 or 2 manager levels.");
        return l;
    }

    private static boolean or(Boolean b, boolean fallback) {
        return b == null ? fallback : b;
    }
}
