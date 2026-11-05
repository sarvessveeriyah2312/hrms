package com.hrms.attendance;

import com.hrms.shared.security.DataScope;
import com.hrms.shared.security.PermissionChecker;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Attendance → Today: who's in, late, not in yet, on leave or off, within the caller's attendance:view scope. */
@RestController
@RequestMapping("/api/v1/attendance/today")
class TodayController {

    private final TodayService today;
    private final PermissionChecker perm;

    TodayController(TodayService today, PermissionChecker perm) {
        this.today = today;
        this.perm = perm;
    }

    /** date: an earlier day to look back at (defaults to today in your legal entity's time zone). */
    @GetMapping
    @PreAuthorize("@perm.has('attendance:view')")
    TodayService.Today get(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                           @AuthenticationPrincipal Jwt jwt) {
        String me = jwt.getClaimAsString("employee_id");
        DataScope scope = perm.scopeOf("attendance:view").orElse(DataScope.SELF);
        return today.today(me == null ? null : UUID.fromString(me), scope, date);
    }
}
