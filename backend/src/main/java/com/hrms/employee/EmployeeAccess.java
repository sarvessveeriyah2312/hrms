package com.hrms.employee;

import com.hrms.shared.security.DataScope;
import com.hrms.shared.security.EmployeeScope;
import com.hrms.shared.security.PermissionChecker;
import com.hrms.shared.web.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Who may see or change an employee's records, from the caller's permission scopes:
 * <ul>
 *   <li>view: yourself, or anyone your {@code employee:view} scope covers;</li>
 *   <li>manage: anyone your {@code employee:manage} scope covers, except yourself (your own changes go to HR like
 *       everyone else's, so nobody approves their own bank details);</li>
 *   <li>private sections, documents and assets: yourself, or anyone you can manage.</li>
 * </ul>
 * Refusals answer 404 so a record's existence isn't revealed.
 */
@Component
class EmployeeAccess {

    private final JdbcClient jdbc;
    private final PermissionChecker perm;

    EmployeeAccess(JdbcClient jdbc, PermissionChecker perm) {
        this.jdbc = jdbc;
        this.perm = perm;
    }

    static UUID me(Jwt jwt) {
        String id = jwt.getClaimAsString("employee_id");
        return id == null ? null : UUID.fromString(id);
    }

    static UUID requireMe(Jwt jwt) {
        UUID me = me(jwt);
        if (me == null) throw new ApiException(HttpStatus.FORBIDDEN, "NO_EMPLOYEE_RECORD", "Your account has no employee record.");
        return me;
    }

    static UUID user(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /** "me" or an employee id. */
    static UUID target(String id, Jwt jwt) {
        if ("me".equals(id)) return requireMe(jwt);
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw notFound();
        }
    }

    boolean covers(UUID me, String permission, UUID target) {
        if (!perm.has(permission)) return false;
        DataScope scope = perm.scopeOf(permission).orElse(DataScope.SELF);
        if (target.equals(me)) return true;
        return jdbc.sql("select exists (select 1 from employee e where e.id = :target and %s)".formatted(EmployeeScope.predicate(scope, "e")))
            .param("target", target).param("me", me).query(Boolean.class).single();
    }

    boolean canView(UUID me, UUID target) {
        return target.equals(me) || covers(me, "employee:view", target);
    }

    boolean canManage(UUID me, UUID target) {
        return !target.equals(me) && covers(me, "employee:manage", target);
    }

    boolean canSeePrivate(UUID me, UUID target) {
        return target.equals(me) || canManage(me, target);
    }

    void requireView(UUID me, UUID target) {
        if (!exists(target) || !canView(me, target)) throw notFound();
    }

    void requirePrivate(UUID me, UUID target) {
        if (!exists(target) || !canSeePrivate(me, target)) throw notFound();
    }

    void requireManage(UUID me, UUID target) {
        if (!exists(target) || !canView(me, target)) throw notFound();
        if (target.equals(me)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OWN_RECORD", "Changes to your own record go to HR: use “Ask for a change” on your profile.");
        }
        if (!canManage(me, target)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CANNOT_MANAGE", "You can't change this person's record.");
        }
    }

    private boolean exists(UUID id) {
        return jdbc.sql("select exists (select 1 from employee where id = :id)").param("id", id).query(Boolean.class).single();
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "EMPLOYEE_NOT_FOUND", "That person doesn't exist.");
    }
}
