package com.hrms.attendance;

import com.hrms.shared.security.DataScope;
import com.hrms.shared.security.PermissionChecker;
import com.hrms.shared.web.ApiException;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Attendance → Timesheets. Your own: with attendance:clock or attendance:timesheets. Other people's:
 * attendance:timesheets, within its scope. from/to are dates (up to 62 days); without them, this month so far.
 */
@RestController
@RequestMapping("/api/v1/attendance/timesheets")
class TimesheetController {

    private final TimesheetService timesheets;
    private final AttendanceFacts facts;
    private final PermissionChecker perm;

    TimesheetController(TimesheetService timesheets, AttendanceFacts facts, PermissionChecker perm) {
        this.timesheets = timesheets;
        this.facts = facts;
        this.perm = perm;
    }

    /** Totals per person, for everyone in your attendance:timesheets scope. */
    @GetMapping
    @PreAuthorize("@perm.has('attendance:timesheets')")
    TimesheetService.Team team(@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
                               @AuthenticationPrincipal Jwt jwt) {
        return timesheets.team(meOrNull(jwt), scope(), from, to);
    }

    @GetMapping("/me")
    @PreAuthorize("@perm.has('attendance:clock') or @perm.has('attendance:timesheets')")
    TimesheetService.Sheet mine(@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
                                @AuthenticationPrincipal Jwt jwt) {
        UUID me = meOrNull(jwt);
        if (me == null) throw new ApiException(HttpStatus.FORBIDDEN, "NO_EMPLOYEE_RECORD", "Your account has no employee record, so it has no timesheet.");
        return timesheets.sheet(me, from, to);
    }

    /** One person's days. Answers 404 for people outside your scope, so their existence isn't revealed. */
    @GetMapping("/{employeeId}")
    @PreAuthorize("@perm.has('attendance:clock') or @perm.has('attendance:timesheets')")
    TimesheetService.Sheet person(@PathVariable UUID employeeId,
                                  @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
                                  @AuthenticationPrincipal Jwt jwt) {
        UUID me = meOrNull(jwt);
        boolean self = employeeId.equals(me);
        if (!self && !(perm.has("attendance:timesheets") && facts.canSee(me, scope(), employeeId))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EMPLOYEE_NOT_FOUND", "That person doesn't exist.");
        }
        return timesheets.sheet(employeeId, from, to);
    }

    private DataScope scope() {
        return perm.scopeOf("attendance:timesheets").orElse(DataScope.SELF);
    }

    private static UUID meOrNull(Jwt jwt) {
        String id = jwt.getClaimAsString("employee_id");
        return id == null ? null : UUID.fromString(id);
    }
}
