package com.hrms.identity;

import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.security.DataScope;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Roles and their permissions. Built-in roles can't be deleted; the Super admin role can't be changed at all. */
@Service
class RoleAdmin {

    record RoleView(UUID id, String code, String name, String description, boolean system, int userCount,
                    Map<String, DataScope> permissions) {
    }

    record RoleInput(String name, String description, Map<String, String> permissions) {
    }

    private final JdbcClient jdbc;
    private final AdminGuards guards;
    private final AuditLog audit;

    RoleAdmin(JdbcClient jdbc, AdminGuards guards, AuditLog audit) {
        this.jdbc = jdbc;
        this.guards = guards;
        this.audit = audit;
    }

    List<RoleView> list() {
        record Row(UUID id, String code, String name, String description, boolean system, int userCount) {
        }
        List<Row> rows = jdbc.sql("""
                select r.id, r.code, r.name, r.description, r.system,
                       (select count(*) from user_role ur where ur.role_id = r.id) as user_count
                from role r order by r.system desc, r.name""")
            .query(Row.class).list();
        List<RoleView> out = new ArrayList<>();
        for (Row r : rows) {
            out.add(new RoleView(r.id(), r.code(), r.name(), r.description(), r.system(), r.userCount(),
                new TreeMap<>(guards.permissionsOfRole(r.id()))));
        }
        return out;
    }

    @Transactional
    RoleView create(UUID actor, RoleInput in) {
        String name = requireName(in.name());
        Map<String, DataScope> perms = AdminGuards.validate(in.permissions());
        AdminGuards.requireCovers(guards.grantsOf(actor), perms, "create this role");

        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("insert into role (id, tenant_id, code, name, description) values (:id, :tenant, :code, :name, :desc)")
                .param("id", id).param("tenant", TenantContext.require()).param("code", codeFor(name))
                .param("name", name).param("desc", blankToNull(in.description())).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "ROLE_NAME_TAKEN", "A role called \"" + name + "\" already exists.");
        }
        writePermissions(id, perms);
        audit.record(actor, "ROLE_CREATED", "role", id, Map.of("name", name, "permissions", scopeNames(perms)));
        return find(id);
    }

    @Transactional
    RoleView update(UUID actor, UUID roleId, RoleInput in) {
        RoleView existing = find(roleId);
        if (AdminGuards.SUPER_ADMIN.equals(existing.code())) {
            throw new ApiException(HttpStatus.CONFLICT, "ROLE_LOCKED",
                "The Super admin role always has every permission and can't be changed.");
        }
        String name = requireName(in.name());
        Map<String, DataScope> perms = AdminGuards.validate(in.permissions());
        Map<String, DataScope> actorGrants = guards.grantsOf(actor);
        // Editing a role changes access for everyone who has it: you must cover both the old and the new set.
        AdminGuards.requireCovers(actorGrants, existing.permissions(), "change this role");
        AdminGuards.requireCovers(actorGrants, perms, "give this role these permissions");

        try {
            jdbc.sql("update role set name = :name, description = :desc where id = :id")
                .param("name", name).param("desc", blankToNull(in.description())).param("id", roleId).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "ROLE_NAME_TAKEN", "A role called \"" + name + "\" already exists.");
        }
        jdbc.sql("delete from role_permission where role_id = :id").param("id", roleId).update();
        writePermissions(roleId, perms);
        audit.record(actor, "ROLE_UPDATED", "role", roleId, Map.of("name", name, "permissions", scopeNames(perms)));
        return find(roleId);
    }

    @Transactional
    void delete(UUID actor, UUID roleId) {
        RoleView existing = find(roleId);
        if (existing.system()) {
            throw new ApiException(HttpStatus.CONFLICT, "ROLE_BUILT_IN", "Built-in roles can't be deleted.");
        }
        if (existing.userCount() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ROLE_IN_USE",
                existing.userCount() + " " + (existing.userCount() == 1 ? "person has" : "people have")
                    + " this role. Give them another role first.");
        }
        AdminGuards.requireCovers(guards.grantsOf(actor), existing.permissions(), "delete this role");
        jdbc.sql("delete from role where id = :id").param("id", roleId).update();
        audit.record(actor, "ROLE_DELETED", "role", roleId, Map.of("name", existing.name()));
    }

    RoleView find(UUID roleId) {
        return list().stream().filter(r -> r.id().equals(roleId)).findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND", "That role doesn't exist."));
    }

    private void writePermissions(UUID roleId, Map<String, DataScope> perms) {
        perms.forEach((perm, scope) -> jdbc.sql(
                "insert into role_permission (role_id, permission, data_scope) values (:role, :perm, :scope)")
            .param("role", roleId).param("perm", perm).param("scope", scope.name()).update());
    }

    private static Map<String, String> scopeNames(Map<String, DataScope> perms) {
        Map<String, String> out = new TreeMap<>();
        perms.forEach((k, v) -> out.put(k, v.name()));
        return out;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 60) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROLE_NAME_REQUIRED", "Give the role a name of up to 60 characters.");
        }
        return name.trim();
    }

    private static String codeFor(String name) {
        String code = name.toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        return code.isEmpty() ? "ROLE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase() : code;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
