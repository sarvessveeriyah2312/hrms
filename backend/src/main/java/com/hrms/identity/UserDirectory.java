package com.hrms.identity;

import com.hrms.shared.security.DataScope;
import com.hrms.shared.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads users and what they're allowed to do.
 * app_user and tenant are global tables (no RLS) because login happens before the tenant is known;
 * roles, modules and employees are under RLS, so {@link #principalFor} binds the user's tenant first.
 */
@Component
class UserDirectory {

    record UserRow(UUID id, UUID tenantId, String companyCode, String companyName, String tenantStatus,
                   String email, String passwordHash, String status, boolean mustChangePassword) {
    }

    private static final String SELECT_USER = """
        select u.id, u.tenant_id, t.code::text as company_code, t.name as company_name, t.status as tenant_status,
               u.email::text as email, u.password_hash, u.status, u.must_change_password
        from app_user u join tenant t on t.id = u.tenant_id
        """;

    private final JdbcClient jdbc;

    UserDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Matches a work email (case-insensitive) or a mobile number, optionally within one company.
     * Without a company code the same person can match accounts in several companies.
     */
    List<UserRow> findByLogin(String login, String companyCode) {
        String where = companyCode == null
            ? "where (u.email = cast(:login as citext) or u.phone = :login) limit 10"
            : "where (u.email = cast(:login as citext) or u.phone = :login) and t.code = cast(:code as citext)";
        var spec = jdbc.sql(SELECT_USER + where).param("login", login);
        if (companyCode != null) spec = spec.param("code", companyCode);
        return spec.query(UserRow.class).list();
    }

    Optional<UserRow> findById(UUID id) {
        return jdbc.sql(SELECT_USER + "where u.id = :id").param("id", id).query(UserRow.class).optional();
    }

    void recordLogin(UUID userId) {
        jdbc.sql("update app_user set last_login_at = now() where id = :id").param("id", userId).update();
    }

    AuthenticatedUser principalFor(UserRow user) {
        Optional<UUID> previous = TenantContext.current();
        TenantContext.set(user.tenantId());
        try {
            List<String> roles = jdbc.sql("""
                    select r.code from role r join user_role ur on ur.role_id = r.id
                    where ur.user_id = :user order by r.code""")
                .param("user", user.id())
                .query(String.class)
                .list();

            record Grant(String permission, String dataScope) {
            }
            List<Grant> grants = jdbc.sql("""
                    select rp.permission, rp.data_scope from role_permission rp
                    join role r on r.id = rp.role_id
                    join user_role ur on ur.role_id = r.id
                    where ur.user_id = :user""")
                .param("user", user.id())
                .query(Grant.class)
                .list();

            Set<String> enabled = jdbc.sql("select module from tenant_module where enabled")
                .query(String.class).list().stream().collect(Collectors.toSet());

            // Several roles can grant the same permission: keep the widest scope.
            // Drop permissions of licensable modules the company has switched off, and anything not in the catalog.
            Map<String, DataScope> scopes = new TreeMap<>();
            for (Grant g : grants) {
                if (PermissionCatalog.action(g.permission()).isEmpty()) continue;
                String module = PermissionCatalog.moduleOf(g.permission());
                if (PermissionCatalog.isLicensable(module) && !enabled.contains(module)) continue;
                scopes.merge(g.permission(), DataScope.valueOf(g.dataScope()), DataScope::widest);
            }

            List<String> modules = new ArrayList<>();
            for (PermissionCatalog.Module m : PermissionCatalog.MODULES) {
                if (!m.licensable() || enabled.contains(m.key())) modules.add(m.key());
            }

            record EmployeeRow(UUID id, String fullName) {
            }
            Optional<EmployeeRow> employee = jdbc.sql("select id, full_name from employee where user_id = :user")
                .param("user", user.id())
                .query(EmployeeRow.class)
                .optional();
            return new AuthenticatedUser(user.id(), user.tenantId(), user.companyCode(), user.companyName(),
                employee.map(EmployeeRow::id).orElse(null),
                employee.map(EmployeeRow::fullName).orElse(null),
                user.email(), roles, new LinkedHashMap<>(scopes), modules, user.mustChangePassword());
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }
}
