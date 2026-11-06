package com.hrms.attendance;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Locking months for payroll. Whether a month is locked: anyone who uses attendance (the history needs
 * attendance:lock). Locking and unlocking: attendance:lock. month is YYYY-MM.
 */
@RestController
@RequestMapping("/api/v1/attendance/locks")
class PeriodLockController {

    record LockInput(YearMonth month) {
    }

    record UnlockInput(YearMonth month, String reason) {
    }

    private static final String ANY_ATTENDANCE = "@perm.has('attendance:clock') or @perm.has('attendance:view') "
        + "or @perm.has('attendance:timesheets') or @perm.has('attendance:lock') or @perm.has('reports:attendance')";

    private final PeriodLocks locks;
    private final com.hrms.shared.security.PermissionChecker perm;

    PeriodLockController(PeriodLocks locks, com.hrms.shared.security.PermissionChecker perm) {
        this.locks = locks;
        this.perm = perm;
    }

    @GetMapping
    @PreAuthorize(ANY_ATTENDANCE)
    PeriodLocks.Status status(@RequestParam YearMonth month) {
        return locks.status(month, perm.has("attendance:lock"));
    }

    /** The locked months of a year, e.g. ["2026-09", "2026-10"]. */
    @GetMapping("/year")
    @PreAuthorize(ANY_ATTENDANCE)
    List<String> year(@RequestParam int year) {
        return locks.lockedIn(year);
    }

    @PostMapping
    @PreAuthorize("@perm.has('attendance:lock')")
    PeriodLocks.Status lock(@RequestBody LockInput in, @AuthenticationPrincipal Jwt jwt) {
        if (in == null || in.month() == null) throw bad();
        return locks.lock(UUID.fromString(jwt.getSubject()), in.month());
    }

    @PostMapping("/unlock")
    @PreAuthorize("@perm.has('attendance:lock')")
    PeriodLocks.Status unlock(@RequestBody UnlockInput in, @AuthenticationPrincipal Jwt jwt) {
        if (in == null || in.month() == null) throw bad();
        return locks.unlock(UUID.fromString(jwt.getSubject()), in.month(), in.reason());
    }

    private static com.hrms.shared.web.ApiException bad() {
        return new com.hrms.shared.web.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "MONTH_REQUIRED", "Choose the month (YYYY-MM).");
    }
}
