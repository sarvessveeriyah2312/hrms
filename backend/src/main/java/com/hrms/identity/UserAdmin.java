package com.hrms.identity;

import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.security.DataScope;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * People who can sign in: their login, employee record, approvers and roles.
 * app_user has no RLS, so every query here filters by the current tenant explicitly.
 */
@Service
class UserAdmin {

    record UserView(UUID userId, UUID employeeId, String fullName, String email, String phone, String employeeNo,
                    UUID departmentId, String department, UUID managerId, String manager,
                    UUID backupApproverId, String backupApprover, String status, Instant lastLoginAt,
                    List<UUID> roleIds, List<String> roles) {
    }

    record UserInput(String fullName, String email, String phone, String employeeNo, UUID departmentId,
                     UUID managerId, UUID backupApproverId, LocalDate joinDate, List<UUID> roleIds, String status) {
    }

    record Created(UserView user, String temporaryPassword) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789".toCharArray();

    private final JdbcClient jdbc;
    private final AdminGuards guards;
    private final PasswordEncoder passwords;
    private final RefreshTokens refreshTokens;
    private final AuditLog audit;

    UserAdmin(JdbcClient jdbc, AdminGuards guards, PasswordEncoder passwords, RefreshTokens refreshTokens, AuditLog audit) {
        this.jdbc = jdbc;
        this.guards = guards;
        this.passwords = passwords;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
    }

    List<UserView> list(String q) {
        record Row(UUID userId, UUID employeeId, String fullName, String email, String phone, String employeeNo,
                   UUID departmentId, String department, UUID managerId, String manager,
                   UUID backupApproverId, String backupApprover, String status, Instant lastLoginAt,
                   String roleIds, String roles) {
        }
        String search = q == null || q.isBlank() ? null : "%" + q.trim() + "%";
        return jdbc.sql("""
                select u.id as user_id, e.id as employee_id, e.full_name, u.email::text as email, u.phone, e.employee_no,
                       e.department_id, d.name as department, e.manager_id, m.full_name as manager,
                       e.backup_approver_id, b.full_name as backup_approver, u.status, u.last_login_at,
                       string_agg(r.id::text, chr(31) order by r.name) as role_ids,
                       string_agg(r.name, chr(31) order by r.name) as roles
                from app_user u
                left join employee e on e.user_id = u.id
                left join department d on d.id = e.department_id
                left join employee m on m.id = e.manager_id
                left join employee b on b.id = e.backup_approver_id
                left join user_role ur on ur.user_id = u.id
                left join role r on r.id = ur.role_id
                where u.tenant_id = :tenant
                  and (cast(:q as text) is null or e.full_name ilike cast(:q as text)
                       or u.email::text ilike cast(:q as text) or e.employee_no ilike cast(:q as text))
                group by u.id, e.id, d.name, m.full_name, b.full_name
                order by e.full_name nulls last, u.email""")
            .param("tenant", TenantContext.require())
            .param("q", search)
            .query(Row.class)
            .list()
            .stream()
            .map(r -> new UserView(r.userId(), r.employeeId(), r.fullName(), r.email(), r.phone(), r.employeeNo(),
                r.departmentId(), r.department(), r.managerId(), r.manager(), r.backupApproverId(), r.backupApprover(),
                r.status(), r.lastLoginAt(), splitIds(r.roleIds()), split(r.roles())))
            .toList();
    }

    UserView find(UUID userId) {
        return list(null).stream().filter(u -> u.userId().equals(userId)).findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "That user doesn't exist."));
    }

    @Transactional
    Created create(UUID actor, UserInput in) {
        String fullName = required(in.fullName(), "fullName", "Enter the person's full name.");
        String email = blankToNull(in.email());
        String phone = blankToNull(in.phone());
        if (email == null && phone == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LOGIN_REQUIRED", "Enter a work email or a mobile number to sign in with.");
        }
        String employeeNo = required(in.employeeNo(), "employeeNo", "Enter an employee number.");
        List<UUID> roles = guards.requireRoles(in.roleIds());
        requireCanAssign(actor, Set.of(), new HashSet<>(roles));
        requireDepartment(in.departmentId());

        UUID tenant = TenantContext.require();
        UUID userId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        String temporary = temporaryPassword();
        try {
            jdbc.sql("""
                    insert into app_user (id, tenant_id, email, phone, password_hash, must_change_password)
                    values (:id, :tenant, cast(:email as citext), :phone, :hash, true)""")
                .param("id", userId).param("tenant", tenant).param("email", email).param("phone", phone)
                .param("hash", passwords.encode(temporary)).update();
            UUID entity = jdbc.sql("select id from legal_entity order by created_at limit 1").query(UUID.class).single();
            jdbc.sql("""
                    insert into employee (id, tenant_id, user_id, entity_id, department_id, employee_no, full_name, join_date)
                    values (:id, :tenant, :user, :entity, :department, :no, :name, :joined)""")
                .param("id", employeeId).param("tenant", tenant).param("user", userId).param("entity", entity)
                .param("department", in.departmentId()).param("no", employeeNo).param("name", fullName)
                .param("joined", in.joinDate() == null ? LocalDate.now() : in.joinDate()).update();
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        }
        setApprovers(employeeId, in.managerId(), in.backupApproverId());
        writeRoles(userId, roles);
        audit.record(actor, "USER_CREATED", "user", userId,
            Map.of("fullName", fullName, "email", String.valueOf(email), "roles", roles.toString()));
        return new Created(find(userId), temporary);
    }

    @Transactional
    UserView update(UUID actor, UUID userId, UserInput in) {
        UserView existing = find(userId);
        String fullName = required(in.fullName(), "fullName", "Enter the person's full name.");
        String email = blankToNull(in.email());
        String phone = blankToNull(in.phone());
        if (email == null && phone == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LOGIN_REQUIRED", "Enter a work email or a mobile number to sign in with.");
        }
        String status = in.status() == null ? existing.status() : in.status();
        if (!Set.of("ACTIVE", "DISABLED").contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_STATUS", "Status must be ACTIVE or DISABLED.");
        }
        if (userId.equals(actor) && "DISABLED".equals(status)) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_DISABLE_SELF", "You can't disable your own account.");
        }
        List<UUID> roles = guards.requireRoles(in.roleIds());
        requireCanAssign(actor, new HashSet<>(existing.roleIds()), new HashSet<>(roles));
        requireDepartment(in.departmentId());

        try {
            jdbc.sql("""
                    update app_user set email = cast(:email as citext), phone = :phone, status = :status, version = version + 1
                    where id = :id and tenant_id = :tenant""")
                .param("email", email).param("phone", phone).param("status", status)
                .param("id", userId).param("tenant", TenantContext.require()).update();
            if (existing.employeeId() != null) {
                jdbc.sql("""
                        update employee set full_name = :name, employee_no = coalesce(:no, employee_no),
                               department_id = :department, version = version + 1
                        where id = :id""")
                    .param("name", fullName).param("no", blankToNull(in.employeeNo()))
                    .param("department", in.departmentId()).param("id", existing.employeeId()).update();
            }
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        }
        if (existing.employeeId() != null) setApprovers(existing.employeeId(), in.managerId(), in.backupApproverId());
        jdbc.sql("delete from user_role where user_id = :user").param("user", userId).update();
        writeRoles(userId, roles);

        if (guards.activeSuperAdmins(TenantContext.require()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "LAST_SUPER_ADMIN",
                "This is the company's last active Super admin. Give someone else the Super admin role first.");
        }
        // Role or status changes take effect at the next token refresh; a disabled user is signed out now.
        if ("DISABLED".equals(status)) refreshTokens.revokeAll(userId);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("fullName", fullName);
        after.put("status", status);
        after.put("roles", roles.toString());
        after.put("managerId", String.valueOf(in.managerId()));
        after.put("backupApproverId", String.valueOf(in.backupApproverId()));
        audit.record(actor, "USER_UPDATED", "user", userId, after);
        return find(userId);
    }

    @Transactional
    String resetPassword(UUID actor, UUID userId) {
        UserView user = find(userId);
        // Resetting someone's password lets you sign in as them, so you must cover all of their access.
        requireCanAssign(actor, Set.of(), new HashSet<>(user.roleIds()));
        String temporary = temporaryPassword();
        jdbc.sql("update app_user set password_hash = :hash, must_change_password = true where id = :id and tenant_id = :tenant")
            .param("hash", passwords.encode(temporary)).param("id", userId).param("tenant", TenantContext.require()).update();
        refreshTokens.revokeAll(userId);
        audit.record(actor, "USER_PASSWORD_RESET", "user", userId, Map.of());
        return temporary;
    }

    /** Roles being added or removed must be within the actor's own access. */
    private void requireCanAssign(UUID actor, Set<UUID> before, Set<UUID> after) {
        Set<UUID> changed = new HashSet<>(before);
        changed.addAll(after);
        Set<UUID> unchanged = new HashSet<>(before);
        unchanged.retainAll(after);
        changed.removeAll(unchanged);
        if (changed.isEmpty()) return;
        Map<String, DataScope> mine = guards.grantsOf(actor);
        for (UUID role : changed) {
            AdminGuards.requireCovers(mine, guards.permissionsOfRole(role), "assign or remove this role");
        }
    }

    private void setApprovers(UUID employeeId, UUID managerId, UUID backupId) {
        requireEmployee(managerId, "manager");
        requireEmployee(backupId, "backup approver");
        if (employeeId.equals(managerId) || employeeId.equals(backupId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "APPROVER_IS_SELF", "Someone can't approve their own requests.");
        }
        if (managerId != null) {
            // The new manager must not report (directly or indirectly) to this employee, or the chain loops.
            boolean loops = jdbc.sql("""
                    with recursive chain(id) as (
                        select manager_id from employee where id = :manager
                        union
                        select e.manager_id from employee e join chain c on e.id = c.id where e.manager_id is not null)
                    select exists (select 1 from chain where id = :employee)""")
                .param("manager", managerId).param("employee", employeeId).query(Boolean.class).single();
            if (loops) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "MANAGER_LOOP",
                    "That manager reports to this person, so the approval chain would loop.");
            }
        }
        jdbc.sql("update employee set manager_id = :manager, backup_approver_id = :backup where id = :id")
            .param("manager", managerId).param("backup", backupId).param("id", employeeId).update();
    }

    /** Ids from the request are checked through RLS: foreign keys alone would accept another company's rows. */
    private void requireEmployee(UUID id, String role) {
        if (id == null) return;
        boolean ok = jdbc.sql("select exists (select 1 from employee where id = :id and status = 'ACTIVE')")
            .param("id", id).query(Boolean.class).single();
        if (!ok) throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_EMPLOYEE", "The chosen " + role + " isn't an active employee.");
    }

    private void requireDepartment(UUID id) {
        if (id == null) return;
        boolean ok = jdbc.sql("select exists (select 1 from department where id = :id)").param("id", id).query(Boolean.class).single();
        if (!ok) throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_DEPARTMENT", "That department doesn't exist.");
    }

    private void writeRoles(UUID userId, List<UUID> roles) {
        for (UUID role : roles) {
            jdbc.sql("insert into user_role (user_id, role_id) values (:user, :role)").param("user", userId).param("role", role).update();
        }
    }

    private static ApiException duplicate(DuplicateKeyException e) {
        String msg = String.valueOf(e.getMostSpecificCause().getMessage());
        if (msg.contains("employee_no")) {
            return new ApiException(HttpStatus.CONFLICT, "EMPLOYEE_NO_TAKEN", "Another employee already has that employee number.");
        }
        return new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "Someone in this company already uses that email.");
    }

    private static String temporaryPassword() {
        char[] out = new char[14];
        for (int i = 0; i < out.length; i++) out[i] = PASSWORD_CHARS[RANDOM.nextInt(PASSWORD_CHARS.length)];
        return new String(out);
    }

    private static String required(String value, String field, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FIELD_REQUIRED", message).with("fields", Map.of(field, message));
        }
        return value.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Values joined with the ASCII unit separator, which can't appear in names typed by people. */
    private static List<String> split(String csv) {
        return csv == null ? List.of() : Arrays.asList(csv.split("\u001f"));
    }

    private static List<UUID> splitIds(String csv) {
        return split(csv).stream().map(UUID::fromString).toList();
    }
}
