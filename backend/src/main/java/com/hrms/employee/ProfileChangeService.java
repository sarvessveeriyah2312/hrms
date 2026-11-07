package com.hrms.employee;

import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import com.hrms.workflow.WorkflowService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * People change their own profile through requests: one section at a time, waiting for HR (approval workflow subject
 * PROFILE_CHANGE, an HR step: anyone who approves profile changes company-wide). The section as it was is kept with the
 * request, so HR sees current and new side by side. Approving writes the new values ({@link ProfileChangeApprovals}).
 */
@Service
class ProfileChangeService {

    static final int MAX_NOTE = 500;

    record Input(String section, Object data, String note) {
    }

    /** changedFields: what differs from the section when it was sent. canCancel: waiting or sent back; canEdit: sent back. */
    record Change(UUID id, UUID employeeId, String employeeName, String employeeNo, String section, List<String> changedFields,
                  String note, String status, String returnedComment, Instant createdAt, Instant decidedAt,
                  WorkflowService.PendingWith pendingWith, boolean canCancel, boolean canEdit) {
    }

    /** current: the section when the request was sent; live: as it is now (it may have changed since). */
    record Detail(Change change, Map<String, Object> current, Map<String, Object> proposed, Map<String, Object> live,
                  List<WorkflowService.TimelineStep> timeline) {
    }

    private record Row(UUID id, UUID employeeId, String employeeName, String employeeNo, String section, String proposed,
                       String current, String note, String status, String returnedComment, Instant createdAt, Instant decidedAt,
                       UUID workflowId) {
    }

    private static final String SELECT = """
            select c.id, c.employee_id, e.full_name as employee_name, e.employee_no, c.section, c.proposed::text as proposed,
                   c.current::text as current, c.note, c.status, c.returned_comment, c.created_at, c.decided_at, c.workflow_id
            from employee_change_request c join employee e on e.id = c.employee_id
            """;

    private final JdbcClient jdbc;
    private final ProfileSections sections;
    private final ProfileService profiles;
    private final WorkflowService workflow;

    ProfileChangeService(JdbcClient jdbc, ProfileSections sections, ProfileService profiles, WorkflowService workflow) {
        this.jdbc = jdbc;
        this.sections = sections;
        this.profiles = profiles;
        this.workflow = workflow;
    }

    // ---------- reading ----------

    /** A person's requests, newest first (the last 50, withdrawn ones left out). */
    @Transactional(readOnly = true)
    List<Change> mine(UUID employeeId) {
        return jdbc.sql(SELECT + "where c.employee_id = :emp and c.status <> 'CANCELLED' order by c.created_at desc limit 50")
            .param("emp", employeeId).query(Row.class).list().stream().map(this::toChange).toList();
    }

    /** Waiting for HR or sent back. */
    @Transactional(readOnly = true)
    List<Change> open(UUID employeeId) {
        return jdbc.sql(SELECT + "where c.employee_id = :emp and c.status in ('PENDING', 'RETURNED') order by c.created_at desc")
            .param("emp", employeeId).query(Row.class).list().stream().map(this::toChange).toList();
    }

    Optional<Change> find(UUID id) {
        return findRow(id).map(this::toChange);
    }

    private Optional<Row> findRow(UUID id) {
        return jdbc.sql(SELECT + "where c.id = :id").param("id", id).query(Row.class).optional();
    }

    @Transactional(readOnly = true)
    Detail detail(UUID id) {
        Row r = findRow(id).orElseThrow(ProfileChangeService::notFound);
        ProfileSections.Section s = sections.require(r.section());
        return new Detail(toChange(r), sections.open(s, profiles.parse(r.current())), sections.open(s, profiles.parse(r.proposed())),
            sections.open(s, profiles.storedSection(r.employeeId(), r.section())),
            r.workflowId() == null ? List.of() : workflow.timeline(r.workflowId()));
    }

    // ---------- asking, changing, withdrawing ----------

    @Transactional
    Change submit(UUID employeeId, Input in) {
        Prepared p = prepare(employeeId, in);
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into employee_change_request (id, tenant_id, employee_id, section, proposed, current, note, status)
                    values (:id, :tenant, :emp, :section, cast(:proposed as jsonb), cast(:current as jsonb), :note, 'PENDING')""")
                .param("id", id).param("tenant", TenantContext.require()).param("emp", employeeId).param("section", p.section().key())
                .param("proposed", profiles.toJson(p.proposed())).param("current", profiles.toJson(p.current()))
                .param("note", p.note()).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "CHANGE_OPEN",
                "You already have a change to this section waiting for HR. Withdraw or change it instead.");
        }
        startApproval(id, employeeId);
        return find(id).orElseThrow();
    }

    /** A request sent back for changes: edit it and it starts a fresh approval. */
    @Transactional
    Change resubmit(UUID employeeId, UUID id, Input in) {
        Row r = findRow(id).orElseThrow(ProfileChangeService::notFound);
        if (!r.employeeId().equals(employeeId)) throw notFound();
        if (!"RETURNED".equals(r.status())) throw new ApiException(HttpStatus.CONFLICT, "NOT_RETURNED", "Only a change sent back to you can be edited.");
        Prepared p = prepare(employeeId, new Input(r.section(), in == null ? null : in.data(), in == null ? null : in.note()));
        jdbc.sql("""
                update employee_change_request set proposed = cast(:proposed as jsonb), current = cast(:current as jsonb), note = :note,
                       status = 'PENDING', returned_comment = null, decided_at = null, version = version + 1
                where id = :id""")
            .param("proposed", profiles.toJson(p.proposed())).param("current", profiles.toJson(p.current()))
            .param("note", p.note()).param("id", id).update();
        startApproval(id, employeeId);
        return find(id).orElseThrow();
    }

    @Transactional
    void cancel(UUID employeeId, UUID id) {
        Row r = findRow(id).orElseThrow(ProfileChangeService::notFound);
        if (!r.employeeId().equals(employeeId)) throw notFound();
        if ("PENDING".equals(r.status())) {
            workflow.cancel(r.workflowId(), employeeId); // marks it CANCELLED through ProfileChangeApprovals
        } else if ("RETURNED".equals(r.status())) {
            jdbc.sql("update employee_change_request set status = 'CANCELLED', decided_at = now(), version = version + 1 where id = :id")
                .param("id", id).update();
        } else {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_CANCEL", "This change has already been decided.");
        }
    }

    /** Called when HR approves: the new values replace the section. */
    void apply(UUID id) {
        Row r = findRow(id).orElseThrow(ProfileChangeService::notFound);
        profiles.write(r.employeeId(), r.section(), profiles.parse(r.proposed()), null);
    }

    private record Prepared(ProfileSections.Section section, Map<String, Object> proposed, Map<String, Object> current, String note) {
    }

    private Prepared prepare(UUID employeeId, Input in) {
        if (in == null || in.section() == null) throw bad("SECTION_REQUIRED", "Choose the section to change.");
        ProfileSections.Section s = sections.require(in.section());
        Map<String, Object> clean = sections.clean(s, in.data());
        Map<String, Object> current = profiles.storedSection(employeeId, s.key());
        if (sections.changedFields(s, sections.open(s, current), clean).isEmpty()) {
            throw bad("NOTHING_CHANGED", "Nothing has changed in this section.");
        }
        String note = in.note() == null || in.note().isBlank() ? null : in.note().trim();
        if (note != null && note.length() > MAX_NOTE) throw bad("NOTE_TOO_LONG", "Keep the note under " + MAX_NOTE + " characters.");
        return new Prepared(s, sections.seal(s, clean), current, note);
    }

    private void startApproval(UUID id, UUID employeeId) {
        UUID flow = workflow.start(ProfileChangeApprovals.SUBJECT, id, employeeId, new WorkflowService.ChainSpec(0, true));
        jdbc.sql("update employee_change_request set workflow_id = :flow where id = :id").param("flow", flow).param("id", id).update();
    }

    private Change toChange(Row r) {
        ProfileSections.Section s = sections.require(r.section());
        List<String> changed = sections.changedFields(s, sections.open(s, profiles.parse(r.current())), sections.open(s, profiles.parse(r.proposed())));
        var pending = "PENDING".equals(r.status()) && r.workflowId() != null ? workflow.pendingWith(r.workflowId()).orElse(null) : null;
        boolean open = "PENDING".equals(r.status()) || "RETURNED".equals(r.status());
        return new Change(r.id(), r.employeeId(), r.employeeName(), r.employeeNo(), r.section(), changed, r.note(), r.status(),
            r.returnedComment(), r.createdAt(), r.decidedAt(), pending, open, "RETURNED".equals(r.status()));
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "CHANGE_NOT_FOUND", "That change request doesn't exist.");
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
