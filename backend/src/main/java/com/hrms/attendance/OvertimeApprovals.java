package com.hrms.attendance;

import com.hrms.workflow.ApprovalSubject;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Connects overtime requests to the approval workflow: how a request reads in the inbox, and what deciding does. */
@Component
class OvertimeApprovals implements ApprovalSubject {

    static final String SUBJECT = "OVERTIME";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private final JdbcClient jdbc;
    private final OvertimeService overtime;
    private final OvertimeTimeOff timeOff;
    private final PeriodLocks locks;

    OvertimeApprovals(JdbcClient jdbc, @Lazy OvertimeService overtime, OvertimeTimeOff timeOff, PeriodLocks locks) {
        this.jdbc = jdbc;
        this.overtime = overtime;
        this.timeOff = timeOff;
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
        OvertimeService.Overtime o = overtime.find(subjectId).orElseThrow(OvertimeService::notFound);
        return new Summary("Overtime on " + o.date().format(DAY), detail(o.hours(), o.rate(), o.compensation(), o.dayKind(), o.reason()), "purple");
    }

    /** "2.5 h at 1.5× · Month-end stock count", "4 h as time off (rest day) · Server move" */
    static String detail(BigDecimal hours, BigDecimal rate, String compensation, String dayKind, String reason) {
        String text = hours.stripTrailingZeros().toPlainString() + " h"
            + ("TIME_OFF".equals(compensation) ? " as time off" : "") + (rate == null ? "" : " at " + rateText(rate))
            + ("REST".equals(dayKind) ? " (rest day)" : "HOLIDAY".equals(dayKind) ? " (public holiday)" : "");
        return reason == null || reason.isBlank() ? text : text + " · " + reason.trim();
    }

    /** 1.5×, 2.0×, 1.75× */
    static String rateText(BigDecimal rate) {
        BigDecimal r = rate.stripTrailingZeros();
        return (r.scale() < 1 ? r.setScale(1) : r).toPlainString() + "×";
    }

    @Override
    public String requesterLink(UUID subjectId) {
        return "/app/attendance?tab=overtime&overtime=" + subjectId;
    }

    @Override
    public void onFinished(UUID subjectId, Outcome outcome, String comment) {
        // Approving adds overtime to the month (and maybe leave), so a locked month refuses it (the approval rolls back).
        if (outcome == Outcome.APPROVED) overtime.find(subjectId).ifPresent(o -> locks.requireOpen(o.date(), "overtime"));
        int changed = jdbc.sql("""
                update overtime_request set status = :status, decided_at = now(), version = version + 1,
                       returned_comment = case when :status = 'RETURNED' then :comment else returned_comment end
                where id = :id and status = 'PENDING'""")
            .param("status", outcome.name()).param("comment", comment).param("id", subjectId).update();
        // Time off becomes leave in the same transaction, so a refusal rolls the approval back.
        if (changed > 0 && outcome == Outcome.APPROVED && overtime.find(subjectId).map(o -> "TIME_OFF".equals(o.compensation())).orElse(false)) {
            timeOff.credit(subjectId);
        }
    }
}
