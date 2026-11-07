package com.hrms.employee;

import com.hrms.shared.security.DataScope;
import com.hrms.shared.security.EmployeeScope;
import com.hrms.shared.security.PermissionChecker;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Employee directory. What you see depends on the scope of your employee:view permission:
 * yourself, your team, your department, your entity or the whole company. The filter runs in SQL.
 */
@RestController
@RequestMapping("/api/v1/employees")
class EmployeeController {

    record EmployeeView(UUID id, String employeeNo, String fullName, String email, String department,
                        String manager, String status, String jobTitle, boolean hasPhoto) {
    }

    record Directory(DataScope scope, List<EmployeeView> employees) {
    }

    private final JdbcClient jdbc;
    private final PermissionChecker perm;

    EmployeeController(JdbcClient jdbc, PermissionChecker perm) {
        this.jdbc = jdbc;
        this.perm = perm;
    }

    @GetMapping
    @PreAuthorize("@perm.has('employee:view')")
    Directory list(@RequestParam(required = false) String q, @AuthenticationPrincipal Jwt jwt) {
        DataScope scope = perm.scopeOf("employee:view").orElse(DataScope.SELF);
        String me = jwt.getClaimAsString("employee_id");
        String search = q == null || q.isBlank() ? null : "%" + q.trim() + "%";

        List<EmployeeView> rows = jdbc.sql("""
                select e.id, e.employee_no, e.full_name, u.email::text as email, d.name as department,
                       m.full_name as manager, e.status,
                       e.job_title, e.photo_file_id is not null as has_photo
                from employee e
                left join app_user u on u.id = e.user_id
                left join department d on d.id = e.department_id
                left join employee m on m.id = e.manager_id
                where %s
                  and (cast(:q as text) is null or e.full_name ilike cast(:q as text) or e.employee_no ilike cast(:q as text))
                order by e.full_name
                limit 500""".formatted(EmployeeScope.predicate(scope, "e")))
            .param("me", me == null ? null : UUID.fromString(me))
            .param("q", search)
            .query(EmployeeView.class)
            .list();
        return new Directory(scope, rows);
    }
}
