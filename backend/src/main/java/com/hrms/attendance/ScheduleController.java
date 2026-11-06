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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Your side of scheduling (attendance:clock): availability, open shifts, and asking to give, swap or claim shifts.
 * A request's detail: its requester or colleague, or someone whose attendance:approve or attendance:roster scope covers
 * the requester (approvers act through /approvals).
 */
@RestController
@RequestMapping("/api/v1/attendance")
class ScheduleController {

    record AvailabilityInput(List<Availability.Day> days) {
    }

    private final Availability availability;
    private final ShiftRequests requests;
    private final AttendanceFacts facts;
    private final PermissionChecker perm;

    ScheduleController(Availability availability, ShiftRequests requests, AttendanceFacts facts, PermissionChecker perm) {
        this.availability = availability;
        this.requests = requests;
        this.facts = facts;
        this.perm = perm;
    }

    @GetMapping("/availability/me")
    @PreAuthorize("@perm.has('attendance:clock')")
    List<Availability.Day> myAvailability(@AuthenticationPrincipal Jwt jwt) {
        return availability.of(me(jwt));
    }

    @PutMapping("/availability/me")
    @PreAuthorize("@perm.has('attendance:clock')")
    List<Availability.Day> saveAvailability(@RequestBody AvailabilityInput in, @AuthenticationPrincipal Jwt jwt) {
        return availability.save(me(jwt), in == null ? null : in.days());
    }

    @GetMapping("/open-shifts")
    @PreAuthorize("@perm.has('attendance:clock')")
    List<ShiftRequests.OpenShift> openShifts(@AuthenticationPrincipal Jwt jwt) {
        return requests.openShifts(me(jwt));
    }

    @GetMapping("/shift-requests")
    @PreAuthorize("@perm.has('attendance:clock')")
    List<ShiftRequests.Request> myRequests(@AuthenticationPrincipal Jwt jwt) {
        return requests.mine(me(jwt));
    }

    /** Who you could give or swap a shift with. */
    @GetMapping("/shift-requests/options")
    @PreAuthorize("@perm.has('attendance:clock')")
    ShiftRequests.Options options(@RequestParam UUID shiftId, @AuthenticationPrincipal Jwt jwt) {
        return requests.options(me(jwt), shiftId);
    }

    @PostMapping("/shift-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('attendance:clock')")
    ShiftRequests.Request ask(@RequestBody ShiftRequests.Input in, @AuthenticationPrincipal Jwt jwt) {
        return requests.ask(me(jwt), in);
    }

    @PostMapping("/shift-requests/{id}/accept")
    @PreAuthorize("@perm.has('attendance:clock')")
    ShiftRequests.Request accept(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return requests.accept(me(jwt), id);
    }

    @PostMapping("/shift-requests/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('attendance:clock')")
    void decline(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        requests.decline(me(jwt), id);
    }

    @PostMapping("/shift-requests/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('attendance:clock')")
    void cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        requests.cancel(me(jwt), id);
    }

    /** Answers 404 when you may not see it. */
    @GetMapping("/shift-requests/{id}")
    ShiftRequests.Detail detail(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID me = meOrNull(jwt);
        ShiftRequests.Detail d = requests.detail(id, me);
        UUID owner = d.request().requesterId();
        boolean colleague = me != null && me.equals(d.request().colleagueId());
        if (!owner.equals(me) && !colleague
            && !covers(me, "attendance:approve", owner) && !covers(me, "attendance:roster", owner)) {
            throw ShiftRequests.notFound();
        }
        return d;
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
