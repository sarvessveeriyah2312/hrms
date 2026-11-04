package com.hrms.leave;

import com.hrms.files.FileStore;
import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.security.DataScope;
import com.hrms.shared.security.PermissionChecker;
import com.hrms.shared.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * Leave for the signed-in employee (apply, balances, own requests) and for the people their
 * leave:view scope covers (team requests, who's off).
 */
@RestController
@RequestMapping("/api/v1/leave")
class LeaveController {

    record Calendar(DataScope scope, List<LeaveService.Request> requests) {
    }

    /** HR recording leave for someone; {@code approveNow} records it as already approved (no approval chain). */
    record OnBehalfInput(UUID employeeId, UUID leaveTypeId, LocalDate startDate, LocalDate endDate, String dayPart,
                         String reason, UUID attachmentId, boolean approveNow) {
    }

    record ReasonInput(String reason) {
    }

    private final LeaveService leave;
    private final PermissionChecker perm;
    private final FileStore files;
    private final AuditLog audit;

    LeaveController(LeaveService leave, PermissionChecker perm, FileStore files, AuditLog audit) {
        this.leave = leave;
        this.perm = perm;
        this.files = files;
        this.audit = audit;
    }

    private static UUID me(Jwt jwt) {
        String id = jwt.getClaimAsString("employee_id");
        if (id == null) throw new ApiException(HttpStatus.FORBIDDEN, "NO_EMPLOYEE_RECORD", "Your account has no employee record, so it has no leave.");
        return UUID.fromString(id);
    }

    private static UUID meOrNull(Jwt jwt) {
        String id = jwt.getClaimAsString("employee_id");
        return id == null ? null : UUID.fromString(id);
    }

    private DataScope viewScope() {
        return perm.scopeOf("leave:view").orElse(DataScope.SELF);
    }

    @GetMapping("/types")
    @PreAuthorize("@perm.has('leave:apply') or @perm.has('leave:view')")
    List<LeaveService.LeaveType> types() {
        return leave.types(false);
    }

    @GetMapping("/holidays")
    @PreAuthorize("@perm.has('leave:apply') or @perm.has('leave:view')")
    List<LeaveService.Holiday> holidays(@RequestParam(required = false) Integer year) {
        int y = year == null ? LocalDate.now().getYear() : year;
        return leave.holidays(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31));
    }

    /** Balances for yourself, or for someone your leave:view scope covers. */
    @GetMapping("/balances")
    @PreAuthorize("@perm.has('leave:apply') or @perm.has('leave:view')")
    List<LeaveService.Balance> balances(@RequestParam(required = false) UUID employeeId, @RequestParam(required = false) Integer year,
                                        @AuthenticationPrincipal Jwt jwt) {
        UUID target = employeeId == null ? me(jwt) : employeeId;
        if (!leave.canSee(meOrNull(jwt), viewScope(), target)) throw forbidden();
        return leave.balances(target, year == null ? LocalDate.now().getYear() : year);
    }

    @GetMapping("/preview")
    @PreAuthorize("@perm.has('leave:apply')")
    LeaveService.Preview preview(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
                                 @RequestParam(required = false) String dayPart, @AuthenticationPrincipal Jwt jwt) {
        return leave.preview(meOrNull(jwt), start, end, dayPart);
    }

    @GetMapping("/requests/mine")
    @PreAuthorize("@perm.has('leave:apply') or @perm.has('leave:view')")
    List<LeaveService.Request> mine(@RequestParam(required = false) Integer year, @AuthenticationPrincipal Jwt jwt) {
        return leave.mine(me(jwt), year == null ? LocalDate.now().getYear() : year);
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('leave:apply')")
    LeaveService.Request apply(@RequestBody LeaveService.ApplyInput in, @AuthenticationPrincipal Jwt jwt) {
        return leave.apply(me(jwt), in);
    }

    /** Edit a request that was sent back for changes; it goes through approval again. */
    @PutMapping("/requests/{id}")
    @PreAuthorize("@perm.has('leave:apply')")
    LeaveService.Request resubmit(@PathVariable UUID id, @RequestBody LeaveService.ApplyInput in, @AuthenticationPrincipal Jwt jwt) {
        return leave.resubmit(me(jwt), id, in);
    }

    /** HR records leave for someone in the company (for example sick leave phoned in). */
    @PostMapping("/requests/on-behalf")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('leave:manage')")
    LeaveService.Request onBehalf(@RequestBody OnBehalfInput in, @AuthenticationPrincipal Jwt jwt) {
        if (in.employeeId() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "FIELDS_REQUIRED", "Choose the employee.");
        boolean exists = leave.canSee(meOrNull(jwt), DataScope.TENANT, in.employeeId());
        if (!exists) throw new ApiException(HttpStatus.NOT_FOUND, "UNKNOWN_EMPLOYEE", "That employee doesn't exist.");
        UUID actor = UUID.fromString(jwt.getSubject());
        LeaveService.Request r = leave.apply(in.employeeId(),
            new LeaveService.ApplyInput(in.leaveTypeId(), in.startDate(), in.endDate(), in.dayPart(), in.reason(), in.attachmentId()),
            actor, new LeaveService.OnBehalf(actor, in.approveNow()));
        audit.record(actor, "LEAVE_RECORDED_FOR", "leave_request", r.id(),
            java.util.Map.of("employee", r.employeeName(), "approvedNow", in.approveNow(), "days", r.days().toPlainString()));
        return r;
    }

    /** HR cancels approved leave (even after it started), with a reason the employee sees. */
    @PostMapping("/requests/{id}/hr-cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('leave:manage')")
    void cancelByHr(@PathVariable UUID id, @RequestBody ReasonInput in, @AuthenticationPrincipal Jwt jwt) {
        UUID actor = UUID.fromString(jwt.getSubject());
        leave.cancelByHr(actor, id, in == null ? null : in.reason());
        audit.record(actor, "LEAVE_CANCELLED_BY_HR", "leave_request", id, java.util.Map.of("reason", in.reason().trim()));
    }

    /** The supporting document, for anyone who may see the request. */
    @GetMapping("/requests/{id}/attachment")
    @PreAuthorize("@perm.has('leave:apply') or @perm.has('leave:view') or @perm.has('leave:approve')")
    ResponseEntity<byte[]> attachment(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        LeaveService.RequestDetail detail = detail(id, jwt);
        var att = detail.request().attachment();
        if (att == null) throw new ApiException(HttpStatus.NOT_FOUND, "NO_ATTACHMENT", "This request has no document.");
        var file = files.read(att.id()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_ATTACHMENT", "The document is missing."));
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.meta().contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(file.meta().name()).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .cacheControl(CacheControl.noStore())
            .body(file.data());
    }

    // ---------- planner (drafts) ----------

    record SubmitDraft(UUID attachmentId) {
    }

    @GetMapping("/drafts")
    @PreAuthorize("@perm.has('leave:apply')")
    List<LeaveService.Request> drafts(@AuthenticationPrincipal Jwt jwt) {
        return leave.drafts(me(jwt));
    }

    @PostMapping("/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('leave:apply')")
    LeaveService.Request createDraft(@RequestBody LeaveService.ApplyInput in, @AuthenticationPrincipal Jwt jwt) {
        return leave.saveDraft(me(jwt), null, in);
    }

    @PutMapping("/drafts/{id}")
    @PreAuthorize("@perm.has('leave:apply')")
    LeaveService.Request updateDraft(@PathVariable UUID id, @RequestBody LeaveService.ApplyInput in, @AuthenticationPrincipal Jwt jwt) {
        return leave.saveDraft(me(jwt), id, in);
    }

    @DeleteMapping("/drafts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('leave:apply')")
    void deleteDraft(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        leave.deleteDraft(me(jwt), id);
    }

    /** Sends a plan for approval; the same checks as applying. */
    @PostMapping("/drafts/{id}/submit")
    @PreAuthorize("@perm.has('leave:apply')")
    LeaveService.Request submitDraft(@PathVariable UUID id, @RequestBody(required = false) SubmitDraft in, @AuthenticationPrincipal Jwt jwt) {
        return leave.submitDraft(me(jwt), id, in == null ? null : in.attachmentId());
    }

    @PostMapping("/requests/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('leave:apply')")
    void cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        leave.cancel(me(jwt), id);
    }

    /** One request with its approval timeline: yours, or someone your leave:view scope covers, or one you're approving. */
    @GetMapping("/requests/{id}")
    @PreAuthorize("@perm.has('leave:apply') or @perm.has('leave:view') or @perm.has('leave:approve')")
    LeaveService.RequestDetail detail(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        LeaveService.RequestDetail detail = leave.detail(id);
        UUID me = meOrNull(jwt);
        if ("DRAFT".equals(detail.request().status()) && !detail.request().employeeId().equals(me)) throw forbidden();
        boolean allowed = leave.canSee(me, viewScope(), detail.request().employeeId())
            || (perm.has("leave:approve") && leave.canSee(me, perm.scopeOf("leave:approve").orElse(DataScope.SELF), detail.request().employeeId()))
            || perm.has("leave:manage");
        if (!allowed) throw forbidden();
        return detail;
    }

    /** Approved (and optionally pending) leave of other people in a date range, within your leave:view scope. */
    @GetMapping("/calendar")
    @PreAuthorize("@perm.has('leave:view')")
    Calendar calendar(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                      @RequestParam(defaultValue = "false") boolean includePending,
                      @AuthenticationPrincipal Jwt jwt) {
        LocalDate start = from == null ? LocalDate.now() : from;
        LocalDate end = to == null ? start.plusDays(30) : to;
        if (end.isBefore(start) || end.isAfter(start.plusDays(366))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_RANGE", "Choose a range of up to a year.");
        }
        DataScope scope = viewScope();
        return new Calendar(scope, leave.others(meOrNull(jwt), scope, start, end, includePending));
    }

    private static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You can't see this person's leave.");
    }
}
