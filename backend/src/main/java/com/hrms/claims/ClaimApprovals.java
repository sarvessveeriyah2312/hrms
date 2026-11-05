package com.hrms.claims;

import com.hrms.workflow.ApprovalSubject;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Connects claims to the approval workflow: how a claim reads in the inbox, and what happens when it's decided. */
@Component
class ClaimApprovals implements ApprovalSubject {

    static final String SUBJECT = "CLAIM";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final JdbcClient jdbc;
    private final ClaimsService claims;

    ClaimApprovals(JdbcClient jdbc, @Lazy ClaimsService claims) {
        this.jdbc = jdbc;
        this.claims = claims;
    }

    @Override
    public String subjectType() {
        return SUBJECT;
    }

    @Override
    public String module() {
        return "claims";
    }

    @Override
    public Summary summarize(UUID subjectId) {
        ClaimsService.Claim c = claims.find(subjectId).orElseThrow(ClaimsService::notFound);
        StringBuilder detail = new StringBuilder(c.description()).append(" · ").append(c.expenseDate().format(DAY));
        if (c.distanceKm() != null) detail.append(" · ").append(c.distanceKm().stripTrailingZeros().toPlainString()).append(" km");
        if (c.merchant() != null) detail.append(" · ").append(c.merchant());
        if (c.receipt() != null) detail.append(" · receipt attached");
        return new Summary(c.typeName() + ", " + ClaimsService.money(c.amount(), c.currency()), detail.toString(), c.tone());
    }

    @Override
    public String requesterLink(UUID subjectId) {
        return "/app/claims?claim=" + subjectId;
    }

    @Override
    public void onFinished(UUID subjectId, Outcome outcome, String comment) {
        jdbc.sql("""
                update claim set status = :status, decided_at = now(), version = version + 1,
                       returned_comment = case when :status = 'RETURNED' then :comment else returned_comment end
                where id = :id and status = 'PENDING'""")
            .param("status", outcome.name()).param("comment", comment).param("id", subjectId).update();
    }

    static String amount(BigDecimal amount) {
        return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
