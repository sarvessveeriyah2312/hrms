package com.hrms.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administration API for the web portal. Each group needs its own permission (admin:users, admin:roles,
 * admin:company); the acting user always comes from the token, never from the request.
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminController {

    record CompanyInput(String name, String code) {
    }

    record ModuleInput(boolean enabled) {
    }

    record DepartmentInput(String name) {
    }

    record PasswordReset(String temporaryPassword) {
    }

    private final UserAdmin users;
    private final RoleAdmin roles;
    private final CompanyAdmin company;

    AdminController(UserAdmin users, RoleAdmin roles, CompanyAdmin company) {
        this.users = users;
        this.roles = roles;
        this.company = company;
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    // ---------- permission catalog ----------

    @GetMapping("/catalog")
    @PreAuthorize("@perm.has('admin:roles') or @perm.has('admin:users')")
    List<PermissionCatalog.Module> catalog() {
        return PermissionCatalog.MODULES;
    }

    // ---------- users ----------

    @GetMapping("/users")
    @PreAuthorize("@perm.has('admin:users')")
    List<UserAdmin.UserView> users(@RequestParam(required = false) String q) {
        return users.list(q);
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('admin:users')")
    UserAdmin.Created createUser(@RequestBody UserAdmin.UserInput in, @AuthenticationPrincipal Jwt jwt) {
        return users.create(actor(jwt), in);
    }

    @PutMapping("/users/{id}")
    @PreAuthorize("@perm.has('admin:users')")
    UserAdmin.UserView updateUser(@PathVariable UUID id, @RequestBody UserAdmin.UserInput in, @AuthenticationPrincipal Jwt jwt) {
        return users.update(actor(jwt), id, in);
    }

    @PostMapping("/users/{id}/reset-password")
    @PreAuthorize("@perm.has('admin:users')")
    PasswordReset resetPassword(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return new PasswordReset(users.resetPassword(actor(jwt), id));
    }

    // ---------- roles ----------

    @GetMapping("/roles")
    @PreAuthorize("@perm.has('admin:roles') or @perm.has('admin:users')")
    List<RoleAdmin.RoleView> roles() {
        return roles.list();
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('admin:roles')")
    RoleAdmin.RoleView createRole(@RequestBody RoleAdmin.RoleInput in, @AuthenticationPrincipal Jwt jwt) {
        return roles.create(actor(jwt), in);
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("@perm.has('admin:roles')")
    RoleAdmin.RoleView updateRole(@PathVariable UUID id, @RequestBody RoleAdmin.RoleInput in, @AuthenticationPrincipal Jwt jwt) {
        return roles.update(actor(jwt), id, in);
    }

    @DeleteMapping("/roles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('admin:roles')")
    void deleteRole(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        roles.delete(actor(jwt), id);
    }

    // ---------- company ----------

    @GetMapping("/company")
    @PreAuthorize("@perm.has('admin:company')")
    CompanyAdmin.CompanyView company() {
        return company.company();
    }

    @PutMapping("/company")
    @PreAuthorize("@perm.has('admin:company')")
    CompanyAdmin.CompanyView updateCompany(@RequestBody CompanyInput in, @AuthenticationPrincipal Jwt jwt) {
        return company.update(actor(jwt), in.name(), in.code());
    }

    @PutMapping("/company/modules/{module}")
    @PreAuthorize("@perm.has('admin:company')")
    CompanyAdmin.CompanyView setModule(@PathVariable String module, @RequestBody ModuleInput in, @AuthenticationPrincipal Jwt jwt) {
        return company.setModule(actor(jwt), module, in.enabled());
    }

    @GetMapping("/departments")
    @PreAuthorize("@perm.has('admin:company') or @perm.has('admin:users')")
    List<CompanyAdmin.DepartmentView> departments() {
        return company.departments();
    }

    @GetMapping("/departments/{id}/members")
    @PreAuthorize("@perm.has('admin:company') or @perm.has('admin:users')")
    List<CompanyAdmin.Member> departmentMembers(@PathVariable UUID id) {
        return company.members(id);
    }

    @PutMapping("/departments/{id}")
    @PreAuthorize("@perm.has('admin:company')")
    CompanyAdmin.DepartmentView renameDepartment(@PathVariable UUID id, @RequestBody DepartmentInput in, @AuthenticationPrincipal Jwt jwt) {
        return company.renameDepartment(actor(jwt), id, in.name());
    }

    @DeleteMapping("/departments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('admin:company')")
    void deleteDepartment(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        company.deleteDepartment(actor(jwt), id);
    }

    @PostMapping("/departments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('admin:company')")
    CompanyAdmin.DepartmentView addDepartment(@RequestBody DepartmentInput in, @AuthenticationPrincipal Jwt jwt) {
        return company.addDepartment(actor(jwt), in.name());
    }
}
