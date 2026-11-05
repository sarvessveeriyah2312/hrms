package com.hrms.attendance;

import com.hrms.shared.web.ApiException;
import java.util.List;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Attendance → Sites. Needs "Manage attendance rules" (attendance:manage), and the attendance module switched on. */
@RestController
@RequestMapping("/api/v1/attendance/sites")
class SiteController {

    record People(List<UUID> employeeIds) {
    }

    /** A test clock-in for one person (nothing is saved). */
    record CheckInput(UUID employeeId, Double lat, Double lng, Integer accuracyM, String wifiBssid, boolean selfie,
                      String remarks, boolean mockLocation) {
    }

    private final SiteService sites;
    private final PunchService punches;

    SiteController(SiteService sites, PunchService punches) {
        this.sites = sites;
        this.punches = punches;
    }

    /** Would this person be able to clock in, with this location and Wi-Fi? Uses their real sites; saves nothing. */
    @PostMapping("/check")
    @PreAuthorize("@perm.has('attendance:manage')")
    PunchService.Check check(@RequestBody CheckInput in) {
        if (in == null || in.employeeId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FIELDS_REQUIRED", "Choose the person to test.");
        }
        return punches.check(in.employeeId(), in.lat(), in.lng(), in.accuracyM(), in.wifiBssid(), in.selfie(), in.remarks(), in.mockLocation());
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    @GetMapping
    @PreAuthorize("@perm.has('attendance:manage')")
    List<SiteService.Summary> list() {
        return sites.list();
    }

    @GetMapping("/entities")
    @PreAuthorize("@perm.has('attendance:manage')")
    List<SiteService.Entity> entities() {
        return sites.entities();
    }

    @GetMapping("/{id}")
    @PreAuthorize("@perm.has('attendance:manage')")
    SiteService.Detail get(@PathVariable UUID id) {
        return sites.find(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('attendance:manage')")
    SiteService.Detail create(@RequestBody SiteService.Input in, @AuthenticationPrincipal Jwt jwt) {
        return sites.create(actor(jwt), in);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.has('attendance:manage')")
    SiteService.Detail update(@PathVariable UUID id, @RequestBody SiteService.Input in, @AuthenticationPrincipal Jwt jwt) {
        return sites.update(actor(jwt), id, in);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('attendance:manage')")
    void delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        sites.delete(actor(jwt), id);
    }

    @PostMapping("/{id}/members")
    @PreAuthorize("@perm.has('attendance:manage')")
    List<SiteService.Member> addMembers(@PathVariable UUID id, @RequestBody People in, @AuthenticationPrincipal Jwt jwt) {
        return sites.addMembers(actor(jwt), id, in == null ? null : in.employeeIds());
    }

    @PostMapping("/{id}/members/remove")
    @PreAuthorize("@perm.has('attendance:manage')")
    List<SiteService.Member> removeMembers(@PathVariable UUID id, @RequestBody People in, @AuthenticationPrincipal Jwt jwt) {
        return sites.removeMembers(actor(jwt), id, in == null ? null : in.employeeIds());
    }
}
