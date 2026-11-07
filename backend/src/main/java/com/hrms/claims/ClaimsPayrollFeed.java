package com.hrms.claims;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** What payroll needs from claims: approved claims by expense month (the same set as the claims payroll export). */
@Component
public class ClaimsPayrollFeed {

    public record ApprovedClaim(UUID employeeId, String employeeNo, String employeeName, String type, BigDecimal amount,
                                String currency, boolean paid) {
    }

    private final ClaimsService claims;

    ClaimsPayrollFeed(ClaimsService claims) {
        this.claims = claims;
    }

    @Transactional(readOnly = true)
    public List<ApprovedClaim> approvedIn(YearMonth month) {
        return claims.approvedIn(month).stream()
            .map(c -> new ApprovedClaim(c.employeeId(), c.employeeNo(), c.employeeName(), c.typeName(), c.amount(), c.currency(),
                c.paidAt() != null))
            .toList();
    }
}
