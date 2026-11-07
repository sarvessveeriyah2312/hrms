package com.hrms.comms;

import com.hrms.shared.security.PermissionChecker;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Grievances and feedback. Raising and following your own: everyone with an employee record. Handling: comms:grievances. */
@RestController
@RequestMapping("/api/v1/grievances")
class GrievancesController {

    record StatusInput(String status, String note) {
    }

    record AssignInput(UUID assignee) {
    }

    record ReopenInput(String reason) {
    }

    private final Grievances grievances;
    private final PermissionChecker perm;

    GrievancesController(Grievances grievances, PermissionChecker perm) {
        this.grievances = grievances;
        this.perm = perm;
    }

    private boolean handler() {
        return perm.has(Grievances.PERMISSION);
    }

    @GetMapping("/mine")
    List<Grievances.Case> mine(@AuthenticationPrincipal Jwt jwt) {
        return grievances.mine(CommsWeb.requireMe(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Grievances.Detail submit(@RequestBody Grievances.Input body, @AuthenticationPrincipal Jwt jwt) {
        return grievances.submit(CommsWeb.requireMe(jwt), CommsWeb.user(jwt), body);
    }

    /** status: ACTIVE (open or in progress), OPEN, IN_PROGRESS, RESOLVED, CLOSED or ALL. */
    @GetMapping
    @PreAuthorize("@perm.has('comms:grievances')")
    List<Grievances.Case> all(@RequestParam(required = false) String status, @AuthenticationPrincipal Jwt jwt) {
        return grievances.all(CommsWeb.me(jwt), status);
    }

    @GetMapping("/handlers")
    @PreAuthorize("@perm.has('comms:grievances')")
    List<Grievances.Handler> handlers() {
        return grievances.handlers();
    }

    @GetMapping("/{id}")
    Grievances.Detail detail(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return grievances.detail(CommsWeb.me(jwt), id, handler());
    }

    @PostMapping("/{id}/replies")
    Grievances.Detail reply(@PathVariable UUID id, @RequestBody Grievances.ReplyInput body, @AuthenticationPrincipal Jwt jwt) {
        return grievances.reply(CommsWeb.requireMe(jwt), CommsWeb.user(jwt), id, body, handler());
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("@perm.has('comms:grievances')")
    Grievances.Detail status(@PathVariable UUID id, @RequestBody StatusInput body, @AuthenticationPrincipal Jwt jwt) {
        return grievances.setStatus(CommsWeb.requireMe(jwt), CommsWeb.user(jwt), id, body == null ? null : body.status(),
            body == null ? null : body.note());
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("@perm.has('comms:grievances')")
    Grievances.Detail assign(@PathVariable UUID id, @RequestBody AssignInput body, @AuthenticationPrincipal Jwt jwt) {
        return grievances.assign(CommsWeb.requireMe(jwt), CommsWeb.user(jwt), id, body == null ? null : body.assignee());
    }

    @PostMapping("/{id}/close")
    Grievances.Detail close(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return grievances.close(CommsWeb.requireMe(jwt), id);
    }

    @PostMapping("/{id}/reopen")
    Grievances.Detail reopen(@PathVariable UUID id, @RequestBody ReopenInput body, @AuthenticationPrincipal Jwt jwt) {
        return grievances.reopen(CommsWeb.requireMe(jwt), id, body == null ? null : body.reason());
    }

    @GetMapping("/{id}/files/{fileId}")
    ResponseEntity<byte[]> file(@PathVariable UUID id, @PathVariable UUID fileId, @AuthenticationPrincipal Jwt jwt) {
        return CommsWeb.inline(grievances.file(CommsWeb.me(jwt), id, fileId, handler()));
    }
}
