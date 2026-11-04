package com.hrms.workflow;

import com.hrms.shared.security.PermissionChecker;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The approver's inbox. Who may act is decided per step by {@link WorkflowService}; the acting employee
 * always comes from the token, never from the request body.
 */
@RestController
@RequestMapping("/api/v1/approvals")
class ApprovalsController {

    record Decision(@Size(max = 500) String comment) {
    }

    /** {@code count} is what's waiting for you; overrides (waiting for someone else) aren't counted. */
    record Inbox(int count, List<WorkflowService.InboxItem> items) {
    }

    private final WorkflowService workflow;
    private final PermissionChecker perm;

    ApprovalsController(WorkflowService workflow, PermissionChecker perm) {
        this.workflow = workflow;
        this.perm = perm;
    }

    private WorkflowService.Actor actor(Jwt jwt) {
        String employee = jwt.getClaimAsString("employee_id");
        return new WorkflowService.Actor(employee == null ? null : UUID.fromString(employee), perm.allScopes());
    }

    @GetMapping("/inbox")
    Inbox inbox(@AuthenticationPrincipal Jwt jwt) {
        var items = workflow.inbox(actor(jwt));
        int mine = (int) items.stream().filter(i -> !"OVERRIDE".equals(i.actingAs())).count();
        return new Inbox(mine, items);
    }

    @PostMapping("/{instanceId}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void approve(@PathVariable UUID instanceId, @RequestBody(required = false) Decision body, @AuthenticationPrincipal Jwt jwt) {
        workflow.decide(actor(jwt), instanceId, true, body == null ? null : body.comment());
    }

    /** Send it back to the requester to change and resubmit. A comment is required. */
    @PostMapping("/{instanceId}/return")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void sendBack(@PathVariable UUID instanceId, @RequestBody(required = false) Decision body, @AuthenticationPrincipal Jwt jwt) {
        workflow.decide(actor(jwt), instanceId, WorkflowService.Decision.RETURN, body == null ? null : body.comment());
    }

    @PostMapping("/{instanceId}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reject(@PathVariable UUID instanceId, @RequestBody(required = false) Decision body, @AuthenticationPrincipal Jwt jwt) {
        workflow.decide(actor(jwt), instanceId, false, body == null ? null : body.comment());
    }
}
