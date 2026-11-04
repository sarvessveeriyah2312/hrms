package com.hrms.workflow;

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

/** Approval workflow settings. Needs workflow:manage (the Super admin role, unless given to others in Roles). */
@RestController
@RequestMapping("/api/v1/workflow/flows")
class ApprovalFlowsController {

    record Order(List<UUID> order) {
    }

    private static final String LEAVE = "LEAVE";

    private final ApprovalFlows flows;

    ApprovalFlowsController(ApprovalFlows flows) {
        this.flows = flows;
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    @GetMapping
    @PreAuthorize("@perm.has('workflow:manage')")
    List<ApprovalFlows.Flow> list() {
        return flows.list(LEAVE);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('workflow:manage')")
    ApprovalFlows.Flow create(@RequestBody ApprovalFlows.FlowInput in, @AuthenticationPrincipal Jwt jwt) {
        return flows.create(actor(jwt), LEAVE, in);
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

    @PutMapping("/order")
    @PreAuthorize("@perm.has('workflow:manage')")
    List<ApprovalFlows.Flow> reorder(@RequestBody Order in, @AuthenticationPrincipal Jwt jwt) {
        return flows.reorder(actor(jwt), LEAVE, in.order());
    }
}
