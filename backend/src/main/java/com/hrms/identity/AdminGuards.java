package com.hrms.identity;

import com.hrms.shared.security.DataScope;
import com.hrms.shared.web.ApiException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Rules every admin change goes through:
 * <ul>
 *   <li>Permissions must exist in the catalog, at a scope that action allows.</li>
 *   <li>No privilege escalation: you can only grant, or assign roles made of, permissions you hold
 *       yourself at the same or a wider scope. Checked against the database, not the (possibly stale) token.</li>
 * </ul>
 */
@Component
class AdminGuards {

    static final String SUPER_ADMIN = "SUPER_ADMIN";

    private final JdbcClient jdbc;

    AdminGuards(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What the acting user holds right now, across all their roles (widest scope wins). */
    Map<String, DataScope> grantsOf(UUID userId) {
        record Grant(String permission, String dataScope) {
        }
        Map<String, DataScope> out = new HashMap<>();
        jdbc.sql("""
                select rp.permission, rp.data_scope from role_permission rp
                join user_role ur on ur.role_id = rp.role_id
                where ur.user_id = :user""")
            .param("user", userId)
            .query(Grant.class)
            .list()
            .forEach(g -> out.merge(g.permission(), DataScope.valueOf(g.dataScope()), DataScope::widest));
        return out;
    }

    Map<String, DataScope> permissionsOfRole(UUID roleId) {
        record Grant(String permission, String dataScope) {
        }
        Map<String, DataScope> out = new HashMap<>();
        jdbc.sql("select permission, data_scope from role_permission where role_id = :role")
            .param("role", roleId)
            .query(Grant.class)
            .list()
            .forEach(g -> out.put(g.permission(), DataScope.valueOf(g.dataScope())));
        return out;
    }

    /** Parses and validates a role's permission map from a request. */
    static Map<String, DataScope> validate(Map<String, String> requested) {
        Map<String, DataScope> out = new HashMap<>();
        if (requested == null) return out;
        requested.forEach((permission, scopeName) -> {
            var action = PermissionCatalog.action(permission).orElseThrow(() ->
                new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_PERMISSION", "There's no permission called " + permission + "."));
            DataScope scope;
            try {
                scope = DataScope.valueOf(scopeName);
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_SCOPE", "Unknown scope " + scopeName + " for " + permission + ".");
            }
            if (!action.scopes().contains(scope)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "SCOPE_NOT_ALLOWED",
                    "\"" + action.name() + "\" can't be given at " + scope.name().toLowerCase() + " level.");
            }
            out.put(permission, scope);
        });
        return out;
    }

    /** Throws unless the actor holds every one of {@code wanted} at the same or a wider scope. */
    static void requireCovers(Map<String, DataScope> actor, Map<String, DataScope> wanted, String what) {
        for (var entry : wanted.entrySet()) {
            DataScope mine = actor.get(entry.getKey());
            if (mine == null || !mine.covers(entry.getValue())) {
                String name = PermissionCatalog.action(entry.getKey()).map(PermissionCatalog.Action::name).orElse(entry.getKey());
                throw new ApiException(HttpStatus.FORBIDDEN, "CANNOT_GRANT",
                    "You can't " + what + " because it includes \"" + name + "\" at " + entry.getValue().name().toLowerCase()
                        + " level, which is more than you have yourself.");
            }
        }
    }

    /** Number of active users holding the Super admin role. */
    int activeSuperAdmins(UUID tenantId) {
        return jdbc.sql("""
                select count(distinct u.id) from app_user u
                join user_role ur on ur.user_id = u.id
                join role r on r.id = ur.role_id
                where u.tenant_id = :tenant and u.status = 'ACTIVE' and r.code = :code""")
            .param("tenant", tenantId).param("code", SUPER_ADMIN)
            .query(Integer.class).single();
    }

    /** Fails unless every id is a role of the current company (RLS hides other companies' roles). */
    List<UUID> requireRoles(List<UUID> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return List.of();
        List<UUID> distinct = roleIds.stream().distinct().toList();
        int found = jdbc.sql("select count(*) from role where id in (:ids)").param("ids", distinct).query(Integer.class).single();
        if (found != distinct.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_ROLE", "One of the chosen roles doesn't exist.");
        }
        return distinct;
    }
}
