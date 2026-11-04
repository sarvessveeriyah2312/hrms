package com.hrms.identity;

import com.hrms.shared.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Keeps every company's Super admin role holding the whole catalog, so new permissions added in code
 * reach Super admins without a manual step. Runs at startup after the demo seeder.
 */
@Component
@Order(20)
class SuperAdminSync implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminSync.class);

    private final JdbcClient jdbc;

    SuperAdminSync(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<UUID> tenants = jdbc.sql("select id from tenant").query(UUID.class).list(); // tenant has no RLS
        int added = 0;
        for (UUID tenant : tenants) {
            TenantContext.set(tenant);
            try {
                List<UUID> roles = jdbc.sql("select id from role where code = :code")
                    .param("code", AdminGuards.SUPER_ADMIN).query(UUID.class).list();
                for (UUID role : roles) {
                    for (var entry : PermissionCatalog.everything().entrySet()) {
                        added += jdbc.sql("""
                                insert into role_permission (role_id, permission, data_scope) values (:role, :perm, :scope)
                                on conflict (role_id, permission) do update set data_scope = excluded.data_scope
                                where role_permission.data_scope <> excluded.data_scope""")
                            .param("role", role).param("perm", entry.getKey()).param("scope", entry.getValue().name())
                            .update();
                    }
                }
            } finally {
                TenantContext.clear();
            }
        }
        if (added > 0) log.info("Super admin roles updated with {} catalog permission(s)", added);
    }
}
