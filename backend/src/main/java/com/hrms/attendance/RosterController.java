package com.hrms.attendance;

import com.hrms.shared.security.DataScope;
import com.hrms.shared.security.PermissionChecker;
import com.hrms.shared.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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

/**
 * Scheduling. Shift templates: read with attendance:roster or attendance:manage, change with attendance:manage.
 * Rosters: attendance:roster, for the people in its scope. Your own schedule: attendance:clock. week is any day of it.
 */
@RestController
@RequestMapping("/api/v1/attendance")
class RosterController {

    record WeekInput(UUID siteId, LocalDate week, Boolean acknowledge) {
    }

    private final RosterService rosters;
    private final ShiftTemplates templates;
    private final PermissionChecker perm;

    RosterController(RosterService rosters, ShiftTemplates templates, PermissionChecker perm) {
        this.rosters = rosters;
        this.templates = templates;
        this.perm = perm;
    }

    // ---------- shift templates ----------

    @GetMapping("/shift-templates")
    @PreAuthorize("@perm.has('attendance:roster') or @perm.has('attendance:manage')")
    List<ShiftTemplates.Template> templates() {
        return templates.list();
    }

    @PostMapping("/shift-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('attendance:manage')")
    ShiftTemplates.Template createTemplate(@RequestBody ShiftTemplates.Input in, @AuthenticationPrincipal Jwt jwt) {
        return templates.create(actor(jwt), in);
    }

    @PutMapping("/shift-templates/{id}")
    @PreAuthorize("@perm.has('attendance:manage')")
    ShiftTemplates.Template updateTemplate(@PathVariable UUID id, @RequestBody ShiftTemplates.Input in, @AuthenticationPrincipal Jwt jwt) {
        return templates.update(actor(jwt), id, in);
    }

    @DeleteMapping("/shift-templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('attendance:manage')")
    void deleteTemplate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        templates.delete(actor(jwt), id);
    }

    // ---------- rosters ----------

    @GetMapping("/rosters/sites")
    @PreAuthorize("@perm.has('attendance:roster')")
    List<RosterService.Site> sites() {
        return rosters.sites();
    }

    @GetMapping("/rosters")
    @PreAuthorize("@perm.has('attendance:roster')")
    RosterService.Week week(@RequestParam UUID siteId, @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate week,
                            @AuthenticationPrincipal Jwt jwt) {
        return rosters.week(meOrNull(jwt), scope(), siteId, week == null ? LocalDate.now() : week);
    }

    @PostMapping("/rosters/shifts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('attendance:roster')")
    RosterService.Shift add(@RequestBody RosterService.ShiftInput in, @AuthenticationPrincipal Jwt jwt) {
        return rosters.add(meOrNull(jwt), scope(), in);
    }

    @PutMapping("/rosters/shifts/{id}")
    @PreAuthorize("@perm.has('attendance:roster')")
    RosterService.Shift update(@PathVariable UUID id, @RequestBody RosterService.ShiftInput in, @AuthenticationPrincipal Jwt jwt) {
        return rosters.update(meOrNull(jwt), scope(), id, in);
    }

    @DeleteMapping("/rosters/shifts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('attendance:roster')")
    void delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        rosters.delete(meOrNull(jwt), scope(), id);
    }

    @PostMapping("/rosters/copy-previous")
    @PreAuthorize("@perm.has('attendance:roster')")
    RosterService.CopyResult copy(@RequestBody WeekInput in, @AuthenticationPrincipal Jwt jwt) {
        return rosters.copyPreviousWeek(meOrNull(jwt), scope(), in.siteId(), week(in));
    }

    @PostMapping("/rosters/clear")
    @PreAuthorize("@perm.has('attendance:roster')")
    int clear(@RequestBody WeekInput in, @AuthenticationPrincipal Jwt jwt) {
        return rosters.clear(meOrNull(jwt), scope(), in.siteId(), week(in));
    }

    @PostMapping("/rosters/publish")
    @PreAuthorize("@perm.has('attendance:roster')")
    RosterService.Week publish(@RequestBody WeekInput in, @AuthenticationPrincipal Jwt jwt) {
        return rosters.publish(actor(jwt), meOrNull(jwt), scope(), in.siteId(), week(in), Boolean.TRUE.equals(in.acknowledge()));
    }

    @PostMapping("/rosters/unpublish")
    @PreAuthorize("@perm.has('attendance:roster')")
    RosterService.Week unpublish(@RequestBody WeekInput in, @AuthenticationPrincipal Jwt jwt) {
        return rosters.unpublish(actor(jwt), meOrNull(jwt), scope(), in.siteId(), week(in));
    }

    // ---------- your own schedule ----------

    /** The published roster of a site you're at (any day of the week). */
    @GetMapping("/site-rosters")
    @PreAuthorize("@perm.has('attendance:rosters')")
    RosterService.SiteRoster siteRoster(@RequestParam(required = false) UUID siteId,
                                        @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate week,
                                        @AuthenticationPrincipal Jwt jwt) {
        UUID me = meOrNull(jwt);
        if (me == null) throw new ApiException(HttpStatus.FORBIDDEN, "NO_EMPLOYEE_RECORD", "Your account has no employee record.");
        return rosters.siteRoster(me, siteId, week == null ? LocalDate.now() : week);
    }

    @GetMapping("/schedule/me")
    @PreAuthorize("@perm.has('attendance:clock')")
    List<RosterService.MyShift> mine(@RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
                                     @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to, @AuthenticationPrincipal Jwt jwt) {
        UUID me = meOrNull(jwt);
        if (me == null) throw new ApiException(HttpStatus.FORBIDDEN, "NO_EMPLOYEE_RECORD", "Your account has no employee record.");
        return rosters.mySchedule(me, from, to);
    }

    private static LocalDate week(WeekInput in) {
        if (in == null || in.siteId() == null || in.week() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEEK_REQUIRED", "Choose the site and the week.");
        }
        return in.week();
    }

    private DataScope scope() {
        return perm.scopeOf("attendance:roster").orElse(DataScope.TEAM);
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static UUID meOrNull(Jwt jwt) {
        String id = jwt.getClaimAsString("employee_id");
        return id == null ? null : UUID.fromString(id);
    }
}
