package com.hrms.claims;

import com.hrms.shared.security.DataScope;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.workflow.WorkflowService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Local development only: claim types for each company that has none, and for the main demo company a few
 * claims that go through the real workflow (waiting, approved, sent back). Limits and rates are samples.
 */
@Component
@Order(40)
@ConditionalOnProperty(name = "hrms.seed.enabled", havingValue = "true")
class ClaimsDemoData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ClaimsDemoData.class);

    private final JdbcClient jdbc;
    private final ClaimsService claims;
    private final WorkflowService workflow;

    ClaimsDemoData(JdbcClient jdbc, ClaimsService claims, WorkflowService workflow) {
        this.jdbc = jdbc;
        this.claims = claims;
        this.workflow = workflow;
    }

    record Tenant(UUID id, String code) {
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Tenant> tenants = jdbc.sql("select id, code::text as code from tenant").query(Tenant.class).list();
        for (Tenant t : tenants) {
            TenantContext.set(t.id());
            try {
                boolean has = jdbc.sql("select exists (select 1 from claim_type)").query(Boolean.class).single();
                if (has) continue;
                seedTypes(t.id());
                if ("DEMO".equalsIgnoreCase(t.code())) seedClaims();
                log.info("Added claim types for {}", t.code());
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void seedTypes(UUID tenant) {
        type(tenant, "TRAVEL", "Travel", "blue", "RECEIPT", "500", null, null, true, 1, false);
        type(tenant, "MEALS", "Meals and entertainment", "orange", "RECEIPT", "150", "400", null, true, 1, false);
        type(tenant, "MILEAGE", "Mileage", "teal", "MILEAGE", null, "800", "0.60", false, 1, false);
        type(tenant, "PARKING", "Parking and tolls", "purple", "RECEIPT", null, "200", null, false, 1, false);
        type(tenant, "MEDICAL", "Medical (outpatient)", "pink", "RECEIPT", "200", null, null, true, 1, true);
    }

    private void type(UUID tenant, String code, String name, String tone, String kind, String perClaim, String perMonth,
                      String rate, boolean receipt, int levels, boolean hr) {
        jdbc.sql("""
                insert into claim_type (tenant_id, code, name, tone, kind, max_per_claim, max_per_month, rate_per_km,
                                        receipt_required, approval_levels, hr_approval)
                values (:tenant, :code, :name, :tone, :kind, :perClaim, :perMonth, :rate, :receipt, :levels, :hr)""")
            .param("tenant", tenant).param("code", code).param("name", name).param("tone", tone).param("kind", kind)
            .param("perClaim", perClaim == null ? null : new BigDecimal(perClaim))
            .param("perMonth", perMonth == null ? null : new BigDecimal(perMonth))
            .param("rate", rate == null ? null : new BigDecimal(rate)).param("receipt", receipt).param("levels", levels)
            .param("hr", hr).update();
    }

    private void seedClaims() {
        Optional<UUID> weiling = employee("weiling@demo.hrms.local");
        Optional<UUID> arjun = employee("arjun@demo.hrms.local");
        Optional<UUID> daniel = employee("daniel@demo.hrms.local");
        if (weiling.isEmpty() || arjun.isEmpty() || daniel.isEmpty()) return;

        UUID mileage = typeId("MILEAGE"), parking = typeId("PARKING");
        LocalDate today = LocalDate.now();
        var actorDaniel = new WorkflowService.Actor(daniel.get(), Map.of("claims:approve", DataScope.TEAM));

        // Waiting for Daniel.
        claims.submit(weiling.get(), new ClaimsService.ClaimInput(mileage, today.minusDays(2), "Client visit, Shah Alam", null, null,
            new BigDecimal("42"), "Office, Bangsar South", "Client site, Shah Alam", null));
        // Approved by Daniel, not paid yet (for HR to mark paid).
        var trip = claims.submit(arjun.get(), new ClaimsService.ClaimInput(mileage, today.minusDays(6), "Supplier meeting, Klang", null, null,
            new BigDecimal("68.5"), "Office", "Port Klang", null));
        workflow.decide(actorDaniel, trip.workflowId(), true, null);
        var park = claims.submit(arjun.get(), new ClaimsService.ClaimInput(parking, today.minusDays(6), "Parking at supplier", "Klang Parking",
            new BigDecimal("12.00"), null, null, null, null));
        workflow.decide(actorDaniel, park.workflowId(), true, null);
        // Sent back by Daniel.
        var toll = claims.submit(weiling.get(), new ClaimsService.ClaimInput(parking, today.minusDays(4), "Tolls", null,
            new BigDecimal("18.40"), null, null, null, null));
        workflow.decide(actorDaniel, toll.workflowId(), WorkflowService.Decision.RETURN, "Which trip was this for?");
        // Waiting for Daniel's manager.
        claims.submit(daniel.get(), new ClaimsService.ClaimInput(mileage, today.minusDays(3), "Team offsite venue check", null, null,
            new BigDecimal("25"), "Office", "Putrajaya", null));
    }

    private Optional<UUID> employee(String email) {
        return jdbc.sql("select e.id from employee e join app_user u on u.id = e.user_id where u.email = cast(:email as citext)")
            .param("email", email).query(UUID.class).optional();
    }

    private UUID typeId(String code) {
        return jdbc.sql("select id from claim_type where code = :code").param("code", code).query(UUID.class).single();
    }
}
