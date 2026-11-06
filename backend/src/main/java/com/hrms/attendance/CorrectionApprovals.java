package com.hrms.attendance;

import com.hrms.workflow.ApprovalSubject;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Connects attendance corrections to the approval workflow: how a request reads in the inbox, and what deciding does. */
@Component
class CorrectionApprovals implements ApprovalSubject {

    static final String SUBJECT = "CORRECTION";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private final JdbcClient jdbc;
    private final CorrectionService corrections;
    private final PeriodLocks locks;

    CorrectionApprovals(JdbcClient jdbc, @Lazy CorrectionService corrections, PeriodLocks locks) {
        this.jdbc = jdbc;
        this.corrections = corrections;
        this.locks = locks;
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
        CorrectionService.Correction c = corrections.find(subjectId).orElseThrow(CorrectionService::notFound);
        return new Summary("Attendance correction for " + c.date().format(DAY), detail(c.clockIn(), c.clockOut(), c.reason()), "teal");
    }

    /** "In 09:00 · Out 18:00 · Forgot to clock out" */
    static String detail(LocalTime in, LocalTime out, String reason) {
        List<String> parts = new ArrayList<>();
        if (in != null) parts.add("In " + in);
        if (out != null) parts.add("Out " + out);
        if (reason != null && !reason.isBlank()) parts.add(reason.trim());
        return String.join(" · ", parts);
    }

    @Override
    public String requesterLink(UUID subjectId) {
        return corrections.find(subjectId)
            .map(c -> "/app/attendance?tab=mine&month=" + c.date().toString().substring(0, 7))
            .orElse("/app/attendance?tab=mine");
    }

    @Override
    public void onFinished(UUID subjectId, Outcome outcome, String comment) {
        // Approving changes the day's times, so a locked month refuses it (and the approval rolls back).
        if (outcome == Outcome.APPROVED) corrections.find(subjectId).ifPresent(c -> locks.requireOpen(c.date(), "attendance"));
        jdbc.sql("""
                update attendance_correction set status = :status, decided_at = now(), version = version + 1,
                       returned_comment = case when :status = 'RETURNED' then :comment else returned_comment end
                where id = :id and status = 'PENDING'""")
            .param("status", outcome.name()).param("comment", comment).param("id", subjectId).update();
    }
}
