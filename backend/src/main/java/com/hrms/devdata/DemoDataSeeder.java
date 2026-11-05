package com.hrms.devdata;

import com.hrms.identity.PermissionCatalog;
import com.hrms.shared.security.DataScope;
import com.hrms.shared.tenancy.TenantContext;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two demo companies for local development, created once (hrms.seed.enabled=true only).
 * Aisyah has an account in both, so signing in as her shows the company picker.
 * Tenant-owned tables are under RLS, so the tenant is bound before each transaction opens its connection.
 */
@Component
@Order(10)
@ConditionalOnProperty(name = "hrms.seed.enabled", havingValue = "true")
class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final PasswordEncoder passwords;
    private final String password;

    DemoDataSeeder(JdbcClient jdbc, TransactionTemplate tx, PasswordEncoder passwords,
                   @Value("${hrms.seed.password}") String password) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.passwords = passwords;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedCompany("DEMO", "Demo Sdn Bhd", "MY", "MYR", "Asia/Kuala_Lumpur", true);
        seedCompany("DEMO-SG", "Demo Pte Ltd", "SG", "SGD", "Asia/Singapore", false);
    }

    private void seedCompany(String code, String name, String country, String currency, String zone, boolean main) {
        boolean exists = jdbc.sql("select exists (select 1 from tenant where code = cast(:code as citext))")
            .param("code", code).query(Boolean.class).single();
        if (exists) return;

        UUID tenant = UUID.randomUUID();
        TenantContext.set(tenant);
        try {
            tx.executeWithoutResult(status -> {
                jdbc.sql("insert into tenant (id, name, country, code) values (:id, :name, :country, :code)")
                    .param("id", tenant).param("name", name).param("country", country).param("code", code).update();
                UUID entity = UUID.randomUUID();
                jdbc.sql("""
                        insert into legal_entity (id, tenant_id, name, country, currency, time_zone)
                        values (:id, :tenant, :name, :country, :currency, :zone)""")
                    .param("id", entity).param("tenant", tenant).param("name", name).param("country", country)
                    .param("currency", currency).param("zone", zone).update();
                for (String module : PermissionCatalog.licensableModules()) {
                    // The Singapore company hasn't licensed claims, to show module switching.
                    boolean on = main || !module.equals("claims");
                    jdbc.sql("insert into tenant_module (tenant_id, module, enabled) values (:tenant, :module, :on)")
                        .param("tenant", tenant).param("module", module).param("on", on).update();
                }
                Map<String, UUID> roles = new LinkedHashMap<>();
                for (BuiltInRole r : BuiltInRole.values()) roles.put(r.name(), role(tenant, r));

                if (main) {
                    UUID ops = department(tenant, entity, "Operations");
                    UUID hr = department(tenant, entity, "Human resources");
                    UUID aisyah = person(tenant, entity, hr, null, "aisyah@demo.hrms.local", "+60120000001", "E0001",
                        "Aisyah Salleh", List.of(roles.get("SUPER_ADMIN")));
                    person(tenant, entity, hr, aisyah, "priya@demo.hrms.local", "+60120000004", "E0004",
                        "Priya Raman", List.of(roles.get("HR_ADMIN"), roles.get("EMPLOYEE")));
                    UUID daniel = person(tenant, entity, ops, aisyah, "daniel@demo.hrms.local", "+60120000003", "E0003",
                        "Daniel Lim", List.of(roles.get("MANAGER")));
                    person(tenant, entity, ops, daniel, "weiling@demo.hrms.local", "+60120000002", "E0002",
                        "Wei Ling Tan", List.of(roles.get("EMPLOYEE")));
                    person(tenant, entity, ops, daniel, "arjun@demo.hrms.local", "+60120000005", "E0005",
                        "Arjun Nair", List.of(roles.get("EMPLOYEE")));
                } else {
                    UUID sales = department(tenant, entity, "Sales");
                    person(tenant, entity, sales, null, "aisyah@demo.hrms.local", "+6580000001", "S0001",
                        "Aisyah Salleh", List.of(roles.get("MANAGER")));
                }
            });
        } finally {
            TenantContext.clear();
        }
        log.info("Created demo company '{}' (code {}). Demo users sign in with the password from hrms.seed.password", name, code);
    }

    /** Starting roles for a new company. Admins can change all but SUPER_ADMIN, and add their own. */
    enum BuiltInRole {
        SUPER_ADMIN("Super admin", "Everything, including roles and company settings. Can't be changed."),
        HR_ADMIN("HR admin", "Everyone's records and requests, and user accounts."),
        MANAGER("Manager", "Their own requests, plus their team's records and approvals."),
        EMPLOYEE("Employee", "Self-service: clock in, apply for leave, submit claims.");

        final String label;
        final String description;

        BuiltInRole(String label, String description) {
            this.label = label;
            this.description = description;
        }

        Map<String, DataScope> permissions() {
            Map<String, DataScope> p = new LinkedHashMap<>();
            switch (this) {
                case SUPER_ADMIN -> p.putAll(PermissionCatalog.everything());
                case HR_ADMIN -> {
                    selfService(p);
                    for (String m : List.of("attendance", "leave", "claims")) {
                        p.put(m + ":view", DataScope.TENANT);
                        p.put(m + ":approve", DataScope.TENANT);
                        p.put(m + ":manage", DataScope.TENANT);
                    }
                    p.put("attendance:timesheets", DataScope.TENANT);
                    p.put("employee:view", DataScope.TENANT);
                    p.put("employee:manage", DataScope.TENANT);
                    p.put("reports:leave", DataScope.TENANT);
                    p.put("reports:claims", DataScope.TENANT);
                    p.put("admin:users", DataScope.TENANT);
                }
                case MANAGER -> {
                    selfService(p);
                    for (String m : List.of("attendance", "leave", "claims")) {
                        p.put(m + ":view", DataScope.TEAM);
                        p.put(m + ":approve", DataScope.TEAM);
                    }
                    p.put("attendance:timesheets", DataScope.TEAM);
                    p.put("employee:view", DataScope.TEAM);
                    p.put("reports:leave", DataScope.TEAM);
                    p.put("reports:claims", DataScope.TEAM);
                }
                case EMPLOYEE -> {
                    selfService(p);
                    for (String m : List.of("attendance", "leave", "claims")) p.put(m + ":view", DataScope.SELF);
                    p.put("attendance:timesheets", DataScope.SELF);
                    p.put("employee:view", DataScope.SELF);
                }
            }
            return p;
        }

        private static void selfService(Map<String, DataScope> p) {
            p.put("attendance:clock", DataScope.SELF);
            p.put("leave:apply", DataScope.SELF);
            p.put("claims:submit", DataScope.SELF);
        }
    }

    private UUID role(UUID tenant, BuiltInRole r) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into role (id, tenant_id, code, name, description, system) values (:id, :tenant, :code, :name, :desc, true)")
            .param("id", id).param("tenant", tenant).param("code", r.name()).param("name", r.label)
            .param("desc", r.description).update();
        r.permissions().forEach((perm, scope) ->
            jdbc.sql("insert into role_permission (role_id, permission, data_scope) values (:role, :perm, :scope)")
                .param("role", id).param("perm", perm).param("scope", scope.name()).update());
        return id;
    }

    private UUID department(UUID tenant, UUID entity, String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into department (id, tenant_id, entity_id, name) values (:id, :tenant, :entity, :name)")
            .param("id", id).param("tenant", tenant).param("entity", entity).param("name", name).update();
        return id;
    }

    /** Creates the login (app_user) and the employee record, and returns the employee id. */
    private UUID person(UUID tenant, UUID entity, UUID department, UUID managerEmployee, String email, String phone,
                        String employeeNo, String fullName, List<UUID> roles) {
        UUID user = UUID.randomUUID();
        jdbc.sql("""
                insert into app_user (id, tenant_id, email, phone, password_hash)
                values (:id, :tenant, :email, :phone, :hash)""")
            .param("id", user).param("tenant", tenant).param("email", email).param("phone", phone)
            .param("hash", passwords.encode(password)).update();
        for (UUID role : roles) {
            jdbc.sql("insert into user_role (user_id, role_id) values (:user, :role)")
                .param("user", user).param("role", role).update();
        }
        UUID employee = UUID.randomUUID();
        jdbc.sql("""
                insert into employee (id, tenant_id, user_id, entity_id, department_id, manager_id, employee_no, full_name, join_date)
                values (:id, :tenant, :user, :entity, :department, :manager, :no, :name, :joined)""")
            .param("id", employee).param("tenant", tenant).param("user", user).param("entity", entity)
            .param("department", department).param("manager", managerEmployee).param("no", employeeNo)
            .param("name", fullName).param("joined", LocalDate.of(2024, 1, 2)).update();
        return employee;
    }
}
