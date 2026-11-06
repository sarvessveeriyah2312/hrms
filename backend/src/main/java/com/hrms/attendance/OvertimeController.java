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
 * Overtime requests. Asking, changing and withdrawing your own: attendance:clock. Seeing one: its owner, or someone
 * whose attendance:timesheets or attendance:approve scope covers the owner (approvers act through /approvals).
 */
@RestController
@RequestMapping("/api/v1/attendance/overtime")
class OvertimeController {

    private final OvertimeService overtime;
    private final AttendanceFacts facts;
    private final PermissionChecker perm;

    OvertimeController(OvertimeService overtime, AttendanceFacts facts, PermissionChecker perm) {
        this.overtime = overtime;
        this.facts = facts;
        this.perm = perm;
    }

    @GetMapping
    @PreAuthorize("@perm.has('attendance:clock')")
    List<OvertimeService.Overtime> mine(@AuthenticationPrincipal Jwt jwt) {
        return overtime.mine(me(jwt));
    }

    /** Your overtime rule (paid, time off or your choice) and banked hours, for the request form. */
    @GetMapping("/rule")
    @PreAuthorize("@perm.has('attendance:clock')")
    OvertimeService.MyRule myRule(@AuthenticationPrincipal Jwt jwt) {
        return overtime.myRule(me(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('attendance:clock')")
    OvertimeService.Overtime submit(@RequestBody OvertimeService.Input in, @AuthenticationPrincipal Jwt jwt) {
        return overtime.submit(me(jwt), in);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.has('attendance:clock')")
    OvertimeService.Overtime resubmit(@PathVariable UUID id, @RequestBody OvertimeService.Input in, @AuthenticationPrincipal Jwt jwt) {
        return overtime.resubmit(me(jwt), id, in);
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('attendance:clock')")
    void cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        overtime.cancel(me(jwt), id);
    }

    /** Answers 404 when you may not see it, so its existence isn't revealed. */
    @GetMapping("/{id}")
    OvertimeService.Detail detail(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        OvertimeService.Detail detail = overtime.detail(id);
        UUID owner = detail.overtime().employeeId();
        UUID me = meOrNull(jwt);
        if (!owner.equals(me) && !covers(me, "attendance:timesheets", owner) && !covers(me, "attendance:approve", owner)) {
            throw OvertimeService.notFound();
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
