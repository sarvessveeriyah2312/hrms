package com.hrms.leave;

import com.hrms.shared.security.DataScope;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.workflow.WorkflowService;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
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
 * Local development only: leave types and sample public holidays for each company that has none, and for the
 * main demo company a few requests that go through the real workflow (some pending, some approved).
 * The holiday dates are samples; check the official gazette before relying on them.
 */
@Component
@Order(30)
@ConditionalOnProperty(name = "hrms.seed.enabled", havingValue = "true")
class LeaveDemoData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LeaveDemoData.class);

    private final JdbcClient jdbc;
    private final LeaveService leave;
    private final WorkflowService workflow;

    LeaveDemoData(JdbcClient jdbc, LeaveService leave, WorkflowService workflow) {
        this.jdbc = jdbc;
        this.leave = leave;
        this.workflow = workflow;
    }

    record Tenant(UUID id, String code, String country) {
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Tenant> tenants = jdbc.sql("select id, code::text as code, country from tenant").query(Tenant.class).list();
        for (Tenant t : tenants) {
            TenantContext.set(t.id());
            try {
                boolean has = jdbc.sql("select exists (select 1 from leave_type)").query(Boolean.class).single();
                if (has) continue;
                seedTypes(t.id());
                seedHolidays(t.id(), t.country().trim());
                if ("DEMO".equalsIgnoreCase(t.code())) seedRequests();
                log.info("Added leave types and sample holidays for {}", t.code());
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void seedTypes(UUID tenant) {
        type(tenant, "ANNUAL", "Annual leave", "teal", true, true, 14, true, 2, false);
        type(tenant, "MEDICAL", "Medical leave", "pink", true, true, 14, false, 1, true);
        type(tenant, "COMPASSIONATE", "Compassionate leave", "blue", true, true, 3, true, 1, false);
        type(tenant, "UNPAID", "Unpaid leave", "purple", false, false, 0, true, 2, true);
        // Annual leave: pro-rated in the joining year, more days with service, up to 5 days carried over.
        jdbc.sql("update leave_type set pro_rate = true, carry_max = 5 where code = 'ANNUAL'").update();
        UUID annual = typeId("ANNUAL");
        for (int[] tier : new int[][] {{2, 16}, {5, 18}}) {
            jdbc.sql("insert into leave_service_tier (leave_type_id, tenant_id, min_years, days) values (:type, :tenant, :years, :days)")
                .param("type", annual).param("tenant", tenant).param("years", tier[0]).param("days", BigDecimal.valueOf(tier[1])).update();
        }
    }

    private void type(UUID tenant, String code, String name, String tone, boolean paid, boolean track, int days,
                      boolean half, int levels, boolean hr) {
        jdbc.sql("""
                insert into leave_type (tenant_id, code, name, tone, paid, track_balance, default_days, half_day_allowed,
                                        approval_levels, hr_approval)
                values (:tenant, :code, :name, :tone, :paid, :track, :days, :half, :levels, :hr)""")
            .param("tenant", tenant).param("code", code).param("name", name).param("tone", tone).param("paid", paid)
            .param("track", track).param("days", BigDecimal.valueOf(days)).param("half", half).param("levels", levels)
            .param("hr", hr).update();
    }

    private void seedHolidays(UUID tenant, String country) {
        Map<String, String> days = "SG".equals(country)
            ? Map.of("2026-01-01", "New Year's Day", "2026-02-17", "Chinese New Year", "2026-02-18", "Chinese New Year (day 2)",
                     "2026-04-03", "Good Friday", "2026-05-01", "Labour Day", "2026-08-10", "National Day (observed)",
                     "2026-11-09", "Deepavali (observed)", "2026-12-25", "Christmas Day")
            : Map.of("2026-01-01", "New Year's Day", "2026-02-17", "Chinese New Year", "2026-02-18", "Chinese New Year (day 2)",
                     "2026-03-20", "Hari Raya Aidilfitri", "2026-05-01", "Labour Day", "2026-08-31", "National Day",
                     "2026-09-16", "Malaysia Day", "2026-11-09", "Deepavali (observed)", "2026-12-25", "Christmas Day");
        days.forEach((date, name) -> jdbc.sql("insert into public_holiday (tenant_id, date, name) values (:tenant, :date, :name)")
            .param("tenant", tenant).param("date", LocalDate.parse(date)).param("name", name).update());
    }

    private void seedRequests() {
        Optional<UUID> weiling = employee("weiling@demo.hrms.local");
        Optional<UUID> arjun = employee("arjun@demo.hrms.local");
        Optional<UUID> daniel = employee("daniel@demo.hrms.local");
        Optional<UUID> priya = employee("priya@demo.hrms.local");
        Optional<UUID> aisyah = employee("aisyah@demo.hrms.local");
        if (weiling.isEmpty() || arjun.isEmpty() || daniel.isEmpty() || priya.isEmpty() || aisyah.isEmpty()) return;

        // Priya stands in for Wei Ling's approvers while they're away.
        jdbc.sql("update employee set backup_approver_id = :priya where id = :weiling and backup_approver_id is null")
            .param("priya", priya.get()).param("weiling", weiling.get()).update();

        UUID annual = typeId("ANNUAL"), medical = typeId("MEDICAL");
        LocalDate today = LocalDate.now();
        LocalDate nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        LocalDate sickDay = today.getDayOfWeek().getValue() <= 5 ? today : today.with(TemporalAdjusters.previous(DayOfWeek.FRIDAY));

        var actorDaniel = new WorkflowService.Actor(daniel.get(), Map.of("leave:approve", DataScope.TEAM));
        var actorPriya = new WorkflowService.Actor(priya.get(), Map.of("leave:approve", DataScope.TENANT));
        var actorAisyah = new WorkflowService.Actor(aisyah.get(), Map.of("leave:approve", DataScope.TENANT));

        // Pending with Daniel (step 1 of 2).
        leave.apply(weiling.get(), new LeaveService.ApplyInput(annual, nextMonday, nextMonday.plusDays(2), "FULL", "Family trip to Penang", null));
        // Approved by Daniel, then HR: Arjun is off today.
        var sick = leave.apply(arjun.get(), new LeaveService.ApplyInput(medical, sickDay, sickDay, "FULL", "Fever, MC attached", null));
        workflow.decide(actorDaniel, sick.workflowId(), true, "Get well soon");
        workflow.decide(actorPriya, sick.workflowId(), true, null);
        // Approved by Aisyah (Priya's only approver).
        var trip = leave.apply(priya.get(), new LeaveService.ApplyInput(annual, nextMonday.plusDays(7), nextMonday.plusDays(11), "FULL", null, null));
        workflow.decide(actorAisyah, trip.workflowId(), true, null);
        // Pending with Aisyah.
        leave.apply(daniel.get(), new LeaveService.ApplyInput(annual, nextMonday.plusDays(14), nextMonday.plusDays(15), "FULL", "Moving house", null));
    }

    private Optional<UUID> employee(String email) {
        return jdbc.sql("select e.id from employee e join app_user u on u.id = e.user_id where u.email = cast(:email as citext)")
            .param("email", email).query(UUID.class).optional();
    }

    private UUID typeId(String code) {
        return jdbc.sql("select id from leave_type where code = :code").param("code", code).query(UUID.class).single();
    }
}
