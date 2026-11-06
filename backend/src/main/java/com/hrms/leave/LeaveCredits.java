package com.hrms.leave;

import com.hrms.shared.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leave earned on top of the yearly entitlement, e.g. time off given for overtime. It counts in the balance of the leave
 * year it's earned in, until it expires; used by other modules, which say where it came from.
 */
@Service
public class LeaveCredits {

    private final JdbcClient jdbc;

    LeaveCredits(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Adds {@code days} of a leave type to someone's balance. {@code expiresOn} null: it doesn't expire. */
    @Transactional
    public UUID credit(UUID employeeId, UUID leaveTypeId, BigDecimal days, LocalDate earnedOn, LocalDate expiresOn,
                       String sourceType, UUID sourceId, String note) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into leave_credit (id, tenant_id, employee_id, leave_type_id, days, earned_on, expires_on, source_type, source_id, note)
                values (:id, :tenant, :emp, :type, :days, :earned, :expires, :source, :sourceId, :note)""")
            .param("id", id).param("tenant", TenantContext.require()).param("emp", employeeId).param("type", leaveTypeId)
            .param("days", days).param("earned", earnedOn).param("expires", expiresOn).param("source", sourceType)
            .param("sourceId", sourceId).param("note", note).update();
        return id;
    }

    /** Whether a leave type exists here (through RLS) and keeps a balance, so earned leave can be added to it. */
    public boolean canCredit(UUID leaveTypeId) {
        return leaveTypeId != null && jdbc.sql("select exists (select 1 from leave_type where id = :id and track_balance and active)")
            .param("id", leaveTypeId).query(Boolean.class).single();
    }
}
