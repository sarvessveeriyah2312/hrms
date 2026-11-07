package com.hrms.employee;

import com.hrms.workflow.ApprovalSubject;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Connects profile change requests to the approval workflow: how one reads in HR's inbox, and what approving does. */
@Component
class ProfileChangeApprovals implements ApprovalSubject {

    static final String SUBJECT = "PROFILE_CHANGE";

    static final Map<String, String> SECTION_NAMES = Map.of(
        "personal", "Personal details", "contact", "Contact details", "address", "Address", "bank", "Bank details",
        "emergency", "Emergency contacts", "spouse", "Spouse", "children", "Children", "education", "Education",
        "national_service", "National service");

    private final JdbcClient jdbc;
    private final ProfileChangeService changes;

    ProfileChangeApprovals(JdbcClient jdbc, @Lazy ProfileChangeService changes) {
        this.jdbc = jdbc;
        this.changes = changes;
    }

    @Override
    public String subjectType() {
        return SUBJECT;
    }

    @Override
    public String module() {
        return "employee";
    }

    @Override
    public Summary summarize(UUID subjectId) {
        ProfileChangeService.Change c = changes.find(subjectId).orElseThrow(ProfileChangeService::notFound);
        String section = SECTION_NAMES.getOrDefault(c.section(), c.section());
        int n = c.changedFields().size();
        String what = c.changedFields().contains(ProfileSections.ITEMS) ? "list changed" : n + (n == 1 ? " field changed" : " fields changed");
        return new Summary("Profile change: " + section, c.note() == null ? what : what + " · " + c.note(), "green");
    }

    @Override
    public String requesterLink(UUID subjectId) {
        return "/app/profile?tab=changes";
    }

    @Override
    public void onFinished(UUID subjectId, Outcome outcome, String comment) {
        int updated = jdbc.sql("""
                update employee_change_request set status = :status, decided_at = now(), version = version + 1,
                       returned_comment = case when :status = 'RETURNED' then :comment else returned_comment end
                where id = :id and status = 'PENDING'""")
            .param("status", outcome.name()).param("comment", comment).param("id", subjectId).update();
        if (updated == 1 && outcome == Outcome.APPROVED) changes.apply(subjectId);
    }
}
