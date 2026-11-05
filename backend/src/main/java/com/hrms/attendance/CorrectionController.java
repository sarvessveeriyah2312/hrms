package com.hrms.attendance;

import com.hrms.shared.security.DataScope;
import com.hrms.shared.security.PermissionChecker;
import com.hrms.shared.web.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Attendance corrections. Asking, changing and withdrawing your own: attendance:clock. Seeing one: its owner, or someone
 * whose attendance:timesheets or attendance:approve scope covers the owner (approvers act through /approvals).
 */
@RestController
@RequestMapping("/api/v1/attendance/corrections")
class CorrectionController {

    private final CorrectionService corrections;
    private final AttendanceFacts facts;
    private final PermissionChecker perm;

    CorrectionController(CorrectionService corrections, AttendanceFacts facts, PermissionChecker perm) {
        this.corrections = corrections;
        this.facts = facts;
        this.perm = perm;
    }

    @GetMapping
    @PreAuthorize("@perm.has('attendance:clock')")
    List<CorrectionService.Correction> mine(@AuthenticationPrincipal Jwt jwt) {
        return corrections.mine(me(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('attendance:clock')")
    CorrectionService.Correction submit(@RequestBody CorrectionService.Input in, @AuthenticationPrincipal Jwt jwt) {
        return corrections.submit(me(jwt), in);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.has('attendance:clock')")
    CorrectionService.Correction resubmit(@PathVariable UUID id, @RequestBody CorrectionService.Input in, @AuthenticationPrincipal Jwt jwt) {
        return corrections.resubmit(me(jwt), id, in);
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('attendance:clock')")
    void cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        corrections.cancel(me(jwt), id);
    }

    /** Answers 404 when you may not see it, so its existence isn't revealed. */
    @GetMapping("/{id}")
    CorrectionService.Detail detail(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        CorrectionService.Detail detail = corrections.detail(id);
        UUID owner = detail.correction().employeeId();
        UUID me = meOrNull(jwt);
        if (!owner.equals(me) && !covers(me, "attendance:timesheets", owner) && !covers(me, "attendance:approve", owner)) {
            throw CorrectionService.notFound();
        }
        return detail;
    }

    private boolean covers(UUID me, String permission, UUID owner) {
        if (!perm.has(permission)) return false;
        return facts.canSee(me, perm.scopeOf(permission).orElse(DataScope.SELF), owner);
    }

    private static UUID me(Jwt jwt) {
        UUID me = meOrNull(jwt);
        if (me == null) throw new ApiException(HttpStatus.FORBIDDEN, "NO_EMPLOYEE_RECORD", "Your account has no employee record.");
        return me;
    }

    private static UUID meOrNull(Jwt jwt) {
        String id = jwt.getClaimAsString("employee_id");
        return id == null ? null : UUID.fromString(id);
    }
}
