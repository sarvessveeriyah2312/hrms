package com.hrms.identity;

import com.hrms.shared.audit.AuditLog;
import com.hrms.shared.tenancy.TenantContext;
import com.hrms.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Company details, the company code used at login, module licences and departments. */
@Service
class CompanyAdmin {

    record ModuleView(String key, String name, boolean enabled) {
    }

    record CompanyView(String name, String code, String country, List<ModuleView> modules) {
    }

    /** managerCount: people in the department who manage someone; workflowCount: approval workflows that name it. */
    record DepartmentView(UUID id, String name, int employeeCount, int managerCount, int workflowCount) {
    }

    record Member(UUID employeeId, String fullName, String employeeNo, String email, String manager, boolean managesOthers,
                  String status) {
    }

    private final JdbcClient jdbc;
    private final AuditLog audit;

    CompanyAdmin(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    CompanyView company() {
        record Row(String name, String code, String country) {
        }
        Row t = jdbc.sql("select name, code::text as code, country from tenant where id = :id")
            .param("id", TenantContext.require()).query(Row.class).single();
        Set<String> enabled = jdbc.sql("select module from tenant_module where enabled").query(String.class).list()
            .stream().collect(Collectors.toSet());
        List<ModuleView> modules = PermissionCatalog.MODULES.stream()
            .filter(PermissionCatalog.Module::licensable)
            .map(m -> new ModuleView(m.key(), m.name(), enabled.contains(m.key())))
            .toList();
        return new CompanyView(t.name(), t.code(), t.country().trim(), modules);
    }

    @Transactional
    CompanyView update(UUID actor, String name, String code) {
        if (name == null || name.isBlank() || name.trim().length() > 120) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "COMPANY_NAME_REQUIRED", "Enter the company name.");
        }
        String c = code == null ? "" : code.trim();
        if (!c.matches("[A-Za-z0-9-]{3,20}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "COMPANY_CODE_INVALID",
                "Use 3 to 20 letters, numbers or hyphens for the company code.");
        }
        try {
            jdbc.sql("update tenant set name = :name, code = cast(:code as citext) where id = :id")
                .param("name", name.trim()).param("code", c.toUpperCase()).param("id", TenantContext.require()).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "COMPANY_CODE_TAKEN", "Another company already uses that code.");
        }
        audit.record(actor, "COMPANY_UPDATED", "tenant", TenantContext.require(), Map.of("name", name.trim(), "code", c.toUpperCase()));
        return company();
    }

    @Transactional
    CompanyView setModule(UUID actor, String module, boolean enabled) {
        if (!PermissionCatalog.isLicensable(module)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_MODULE", "There's no module called " + module + " that can be switched.");
        }
        jdbc.sql("""
                insert into tenant_module (tenant_id, module, enabled) values (:tenant, :module, :enabled)
                on conflict (tenant_id, module) do update set enabled = excluded.enabled, updated_at = now()""")
            .param("tenant", TenantContext.require()).param("module", module).param("enabled", enabled).update();
        audit.record(actor, enabled ? "MODULE_ENABLED" : "MODULE_DISABLED", "tenant", TenantContext.require(), Map.of("module", module));
        return company();
    }

    List<DepartmentView> departments() {
        return jdbc.sql("""
                select d.id, d.name,
                       (select count(*) from employee e where e.department_id = d.id) as employee_count,
                       (select count(*) from employee e where e.department_id = d.id
                          and exists (select 1 from employee r where r.manager_id = e.id)) as manager_count,
                       (select count(*) from approval_flow f where d.id = any(f.department_ids)) as workflow_count
                from department d order by d.name""")
            .query(DepartmentView.class).list();
    }

    DepartmentView department(UUID id) {
        return departments().stream().filter(d -> d.id().equals(id)).findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DEPARTMENT_NOT_FOUND", "That department doesn't exist."));
    }

    List<Member> members(UUID departmentId) {
        department(departmentId);
        return jdbc.sql("""
                select e.id as employee_id, e.full_name, e.employee_no, u.email::text as email, m.full_name as manager,
                       exists (select 1 from employee r where r.manager_id = e.id) as manages_others, e.status
                from employee e
                left join app_user u on u.id = e.user_id
                left join employee m on m.id = e.manager_id
                where e.department_id = :id order by e.full_name""")
            .param("id", departmentId).query(Member.class).list();
    }

    @Transactional
    DepartmentView renameDepartment(UUID actor, UUID id, String name) {
        DepartmentView existing = department(id);
        if (name == null || name.isBlank() || name.trim().length() > 80) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DEPARTMENT_NAME_REQUIRED", "Enter a department name.");
        }
        boolean taken = jdbc.sql("select exists (select 1 from department where lower(name) = lower(:name) and id <> :id)")
            .param("name", name.trim()).param("id", id).query(Boolean.class).single();
        if (taken) throw new ApiException(HttpStatus.CONFLICT, "DEPARTMENT_EXISTS", "There's already a department with that name.");
        jdbc.sql("update department set name = :name where id = :id").param("name", name.trim()).param("id", id).update();
        audit.record(actor, "DEPARTMENT_RENAMED", "department", id, Map.of("from", existing.name(), "to", name.trim()));
        return department(id);
    }

    /** Only an empty department that no approval workflow uses can be deleted. */
    @Transactional
    void deleteDepartment(UUID actor, UUID id) {
        DepartmentView existing = department(id);
        if (existing.employeeCount() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "DEPARTMENT_NOT_EMPTY",
                existing.employeeCount() + " " + (existing.employeeCount() == 1 ? "person is" : "people are")
                    + " in this department. Move them to another department in Users first.");
        }
        List<String> flows = jdbc.sql("select name from approval_flow where :id = any(department_ids) order by name")
            .param("id", id).query(String.class).list();
        if (!flows.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "DEPARTMENT_IN_USE",
                "These approval workflows use this department: " + String.join(", ", flows) + ". Change them first.");
        }
        jdbc.sql("delete from department where id = :id").param("id", id).update();
        audit.record(actor, "DEPARTMENT_DELETED", "department", id, Map.of("name", existing.name()));
    }

    @Transactional
    DepartmentView addDepartment(UUID actor, String name) {
        if (name == null || name.isBlank() || name.trim().length() > 80) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DEPARTMENT_NAME_REQUIRED", "Enter a department name.");
        }
        boolean exists = jdbc.sql("select exists (select 1 from department where lower(name) = lower(:name))")
            .param("name", name.trim()).query(Boolean.class).single();
        if (exists) throw new ApiException(HttpStatus.CONFLICT, "DEPARTMENT_EXISTS", "There's already a department with that name.");
        UUID id = UUID.randomUUID();
        UUID entity = jdbc.sql("select id from legal_entity order by created_at limit 1").query(UUID.class).single();
        jdbc.sql("insert into department (id, tenant_id, entity_id, name) values (:id, :tenant, :entity, :name)")
            .param("id", id).param("tenant", TenantContext.require()).param("entity", entity).param("name", name.trim()).update();
        audit.record(actor, "DEPARTMENT_CREATED", "department", id, Map.of("name", name.trim()));
        return new DepartmentView(id, name.trim(), 0, 0, 0);
    }
}
