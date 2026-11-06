package com.hrms.workflow;

import com.hrms.shared.web.ApiException;
import java.util.List;
import com.hrms.shared.security.PermissionChecker;
import java.util.Map;
import java.util.Set;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Approval workflow settings for leave ({@code subject=LEAVE}, the default), claims ({@code subject=CLAIM}) and
 * attendance corrections ({@code subject=CORRECTION}) and overtime ({@code subject=OVERTIME}).
 * Needs workflow:manage (the Super admin role, unless given to others in Roles).
 */
@RestController
@RequestMapping("/api/v1/workflow/flows")
class ApprovalFlowsController {

    record Order(List<UUID> order) {
    }

    private static final Set<String> SUBJECTS = Set.of("LEAVE", "CLAIM", "CORRECTION", "OVERTIME");

    /** Who manages each subject's own settings, and may read (not change) its workflows there. */
    private static final Map<String, String> SETTINGS_PERMISSION = Map.of(
        "LEAVE", "leave:manage", "CLAIM", "claims:manage", "CORRECTION", "attendance:manage", "OVERTIME", "attendance:manage");

    private final ApprovalFlows flows;
    private final PermissionChecker perm;

    ApprovalFlowsController(ApprovalFlows flows, PermissionChecker perm) {
        this.flows = flows;
        this.perm = perm;
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static String subject(String value) {
        String s = value == null || value.isBlank() ? "LEAVE" : value.trim().toUpperCase();
        if (!SUBJECTS.contains(s)) throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_SUBJECT", "Workflows are for LEAVE, CLAIM, CORRECTION or OVERTIME.");
        return s;
    }

    /**
     * workflow:manage, or the subject's own settings permission (e.g. claims:manage for CLAIM), so claim and leave type
     * settings can show which workflows take over from a type's own approval chain.
     */
    @GetMapping
    List<ApprovalFlows.Flow> list(@RequestParam(required = false) String subject) {
        String s = subject(subject);
        if (!perm.has("workflow:manage") && !perm.has(SETTINGS_PERMISSION.get(s))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You don't have access to these workflows.");
        }
        return flows.list(s);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('workflow:manage')")
    ApprovalFlows.Flow create(@RequestParam(required = false) String subject, @RequestBody ApprovalFlows.FlowInput in,
                              @AuthenticationPrincipal Jwt jwt) {
        return flows.create(actor(jwt), subject(subject), in);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.has('workflow:manage')")
    ApprovalFlows.Flow update(@PathVariable UUID id, @RequestBody ApprovalFlows.FlowInput in, @AuthenticationPrincipal Jwt jwt) {
        return flows.update(actor(jwt), id, in);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('workflow:manage')")
    void delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        flows.delete(actor(jwt), id);
    }

    /** For a leave or claim type: use this workflow. {@code dryRun}: only say what would change. */
    record AssignInput(UUID typeId, UUID flowId, Boolean dryRun) {
    }

    @PostMapping("/assign")
    @PreAuthorize("@perm.has('workflow:manage')")
    ApprovalFlows.Assignment assign(@RequestParam(required = false) String subject, @RequestBody AssignInput in,
                                    @AuthenticationPrincipal Jwt jwt) {
        return flows.assign(actor(jwt), subject(subject), in.typeId(), in.flowId(), in.dryRun() == null || in.dryRun());
    }

    @PutMapping("/order")
    @PreAuthorize("@perm.has('workflow:manage')")
    List<ApprovalFlows.Flow> reorder(@RequestParam(required = false) String subject, @RequestBody Order in,
                                     @AuthenticationPrincipal Jwt jwt) {
        return flows.reorder(actor(jwt), subject(subject), in.order());
    }
}
