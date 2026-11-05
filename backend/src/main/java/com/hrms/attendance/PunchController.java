package com.hrms.attendance;

import com.hrms.shared.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/punches")
class PunchController {

    /** What the app sends. The employee comes from the token, never from this body. */
    record ClockRequest(
        @NotNull Punch.Type type,
        @DecimalMin("-90") @DecimalMax("90") Double lat,
        @DecimalMin("-180") @DecimalMax("180") Double lng,
        Integer accuracyM,
        @Size(max = 64) String wifiBssid,
        Instant clientTime,
        boolean mockLocation,
        boolean rootedOrJailbroken,
        boolean developerMode,
        UUID selfieFileId,
        @Size(max = 500) String remarks,
        Punch.Source source) {
    }

    record ClockResponse(UUID id, Punch.Result result, Instant serverTime, UUID siteId, List<String> flags,
                         Integer distanceM, String code) {
    }

    private final PunchService service;

    PunchController(PunchService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('attendance:clock')")
    ClockResponse clock(@Valid @RequestBody ClockRequest request,
                        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                        @AuthenticationPrincipal Jwt jwt) {
        return service.clock(employeeOf(jwt), UUID.fromString(jwt.getSubject()), request, idempotencyKey);
    }

    /** Your active sites and what each needs, so the app can check before sending (the server checks again). */
    @GetMapping("/sites")
    @PreAuthorize("@perm.has('attendance:clock')")
    List<PunchService.MySite> mySites(@AuthenticationPrincipal Jwt jwt) {
        return service.mySites(employeeOf(jwt));
    }

    private static UUID employeeOf(Jwt jwt) {
        String id = jwt.getClaimAsString("employee_id");
        if (id == null) throw new ApiException(HttpStatus.FORBIDDEN, "NO_EMPLOYEE_RECORD", "Your account has no employee record, so it can't clock in.");
        return UUID.fromString(id);
    }
}
