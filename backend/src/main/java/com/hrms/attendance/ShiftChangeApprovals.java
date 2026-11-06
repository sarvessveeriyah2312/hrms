package com.hrms.attendance;

import com.hrms.workflow.ApprovalSubject;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Connects shift swaps, give-aways and open-shift claims to approval workflows. Approving moves the shifts. */
@Component
class ShiftChangeApprovals implements ApprovalSubject {

    static final String SUBJECT = "SHIFT_CHANGE";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private final JdbcClient jdbc;
    private final ShiftRequests requests;

    ShiftChangeApprovals(JdbcClient jdbc, @Lazy ShiftRequests requests) {
        this.jdbc = jdbc;
        this.requests = requests;
    }

    @Override
    public String subjectType() {
        return SUBJECT;
    }

    @Override
    public String module() {
        return "attendance";
    }

    @Override
    public Summary summarize(UUID subjectId) {
        ShiftRequests.Request r = requests.find(subjectId).orElseThrow(ShiftRequests::notFound);
        String when = r.shift() == null ? "" : r.shift().date().format(DAY) + " " + r.shift().startTime() + "–" + r.shift().endTime()
            + " at " + r.shift().site();
        String title = switch (r.kind()) {
            case "CLAIM" -> "Open shift claim";
            case "SWAP" -> "Shift swap with " + r.colleagueName();
            default -> "Shift given to " + r.colleagueName();
        };
        String detail = when + ("SWAP".equals(r.kind()) && r.colleagueShift() != null
            ? " ↔ " + r.colleagueShift().date().format(DAY) + " " + r.colleagueShift().startTime() + "–" + r.colleagueShift().endTime() : "")
            + (r.reason() == null ? "" : " · " + r.reason());
        return new Summary(title, detail, "blue");
    }

    @Override
    public String requesterLink(UUID subjectId) {
        return "/app/attendance?tab=schedule&request=" + subjectId;
    }

    @Override
    public void onFinished(UUID subjectId, Outcome outcome, String comment) {
        if (outcome == Outcome.APPROVED) requests.apply(subjectId);   // refuses (rolling back) when the roster changed
        jdbc.sql("""
                update shift_request set status = :status, decided_at = now(),
                       returned_comment = case when :status = 'RETURNED' then :comment else returned_comment end
                where id = :id and status = 'PENDING'""")
            .param("status", outcome.name()).param("comment", comment).param("id", subjectId).update();
    }
}
