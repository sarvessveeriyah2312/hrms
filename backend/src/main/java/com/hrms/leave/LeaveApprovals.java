package com.hrms.leave;

import com.hrms.leave.LeaveDays.DayPart;
import com.hrms.workflow.ApprovalSubject;
import com.hrms.workflow.ApproverAvailability;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Connects leave requests to the approval workflow: how a request reads in the inbox, what happens when it's
 * decided, and who is away (approved leave today), so their backup approver can act.
 */
@Component
class LeaveApprovals implements ApprovalSubject, ApproverAvailability {

    static final String SUBJECT = "LEAVE";

    private final JdbcClient jdbc;
    private final LeaveService leave;

    LeaveApprovals(JdbcClient jdbc, @Lazy LeaveService leave) {
        this.jdbc = jdbc;
        this.leave = leave;
    }

    @Override
    public String subjectType() {
        return SUBJECT;
    }

    @Override
    public String module() {
        return "leave";
    }

    @Override
    public Summary summarize(UUID subjectId) {
        LeaveService.Request r = leave.find(subjectId).orElseThrow(LeaveService::notFound);
        StringBuilder detail = new StringBuilder(LeaveDays.days(r.days()));
        leave.balances(r.employeeId(), r.startDate().getYear()).stream()
            .filter(b -> b.leaveTypeId().equals(r.leaveTypeId()) && b.trackBalance())
            .findFirst()
            .ifPresent(b -> detail.append(" · ").append(LeaveDays.days(b.available().max(BigDecimal.ZERO))).append(" left after this"));
        if (r.attachment() != null) detail.append(" · document attached");
        if (r.reason() != null) detail.append(" · ").append(r.reason());
        String title = r.typeName() + ", " + LeaveDays.range(r.startDate(), r.endDate(), DayPart.valueOf(r.dayPart()));
        return new Summary(title, detail.toString(), r.tone());
    }

    @Override
    public String requesterLink(UUID subjectId) {
        return "/app/leave?request=" + subjectId;
    }

    @Override
    public void onFinished(UUID subjectId, Outcome outcome, String comment) {
        jdbc.sql("""
                update leave_request set status = :status, decided_at = now(), version = version + 1,
                       returned_comment = case when :status = 'RETURNED' then :comment else returned_comment end
                where id = :id and status = 'PENDING'""")
            .param("status", outcome.name()).param("comment", comment).param("id", subjectId).update();
    }

    @Override
    public boolean isAway(UUID employeeId, LocalDate day) {
        return leave.isOnLeave(employeeId, day);
    }
}
