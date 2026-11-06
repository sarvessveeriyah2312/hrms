package com.hrms.workflow;

import com.hrms.shared.security.PermissionChecker;
import com.hrms.shared.web.ApiException;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
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

    /** Approve several requests at once (e.g. a team's overtime), with one optional comment for all. */
    record ApproveMany(List<UUID> instanceIds, @Size(max = 500) String comment) {
    }

    /** What happened to each: approved, or why not (the others still go through). */
    record ManyResult(List<UUID> approved, List<Failed> failed) {
    }

    record Failed(UUID instanceId, String code, String message) {
    }

    static final int MAX_AT_ONCE = 100;

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

    /**
     * Each request is decided on its own (its own transaction and checks), so one that someone else just decided, or
     * that isn't yours to approve, doesn't stop the rest.
     */
    @PostMapping("/approve")
    ManyResult approveMany(@RequestBody ApproveMany body, @AuthenticationPrincipal Jwt jwt) {
        List<UUID> ids = body == null || body.instanceIds() == null ? List.of() : body.instanceIds().stream().distinct().toList();
        if (ids.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "NOTHING_CHOSEN", "Choose the requests to approve.");
        if (ids.size() > MAX_AT_ONCE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOO_MANY", "Approve up to " + MAX_AT_ONCE + " requests at a time.");
        }
        if (body.comment() != null && body.comment().length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "COMMENT_TOO_LONG", "Keep the comment under 500 characters.");
        }
        WorkflowService.Actor actor = actor(jwt);
        List<UUID> approved = new ArrayList<>();
        List<Failed> failed = new ArrayList<>();
        for (UUID id : ids) {
            try {
                workflow.decide(actor, id, WorkflowService.Decision.APPROVE, body.comment());
                approved.add(id);
            } catch (ApiException e) {
                failed.add(new Failed(id, e.code(), e.getMessage()));
            }
        }
        return new ManyResult(approved, failed);
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
