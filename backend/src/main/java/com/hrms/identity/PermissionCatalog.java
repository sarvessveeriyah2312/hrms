package com.hrms.identity;

import com.hrms.shared.security.DataScope;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Everything a role can grant: modules, their actions, and the data scopes each action may have.
 * Roles are validated against this list, and the web role editor is built from it.
 */
public final class PermissionCatalog {

    public record Action(String key, String name, String description, Set<DataScope> scopes) {
        public String permission(String module) {
            return module + ":" + key;
        }
    }

    /** {@code licensable} modules can be switched off per company; the others are always on. */
    public record Module(String key, String name, boolean licensable, List<Action> actions) {
    }

    private static final Set<DataScope> SELF_ONLY = EnumSet.of(DataScope.SELF);
    private static final Set<DataScope> ANY = EnumSet.allOf(DataScope.class);
    private static final Set<DataScope> OTHERS = EnumSet.of(DataScope.TEAM, DataScope.DEPARTMENT, DataScope.ENTITY, DataScope.TENANT);
    private static final Set<DataScope> COMPANY = EnumSet.of(DataScope.TENANT);

    public static final List<Module> MODULES = List.of(
        new Module("attendance", "Attendance", true, List.of(
            new Action("clock", "Clock in and out", "Record their own clock-ins.", SELF_ONLY),
            new Action("view", "See attendance", "See who's in today and their clock-ins.", ANY),
            new Action("timesheets", "See timesheets", "Each person's days: hours worked, late, left early, missed clock-outs.", ANY),
            new Action("approve", "Approve corrections and overtime", "Act on attendance requests.", OTHERS),
            new Action("rosters", "See site rosters", "The published roster of the sites they're at: who works which shift (names and times only).", SELF_ONLY),
            new Action("roster", "Plan rosters", "Plan, publish and change shifts for people at their sites.", OTHERS),
            new Action("manage", "Manage attendance rules", "Sites, geofences, Wi-Fi, overtime rules and shift templates.", COMPANY),
            new Action("lock", "Lock attendance periods", "Lock a month before payroll so timesheets, corrections and overtime can't change; unlock with a reason.", COMPANY))),
        new Module("leave", "Leave", true, List.of(
            new Action("apply", "Apply for leave", "Submit their own leave requests.", SELF_ONLY),
            new Action("view", "See leave", "See leave balances and requests.", ANY),
            new Action("approve", "Approve leave", "Act on leave requests.", OTHERS),
            new Action("manage", "Manage leave policies", "Leave types, entitlements and holidays.", COMPANY))),
        new Module("claims", "Claims", true, List.of(
            new Action("submit", "Submit claims", "Submit their own expense claims.", SELF_ONLY),
            new Action("view", "See claims", "See claims and receipts.", ANY),
            new Action("approve", "Approve claims", "Act on claims.", OTHERS),
            new Action("manage", "Manage claim policies", "Claim types and limits.", COMPANY))),
        new Module("employee", "People", false, List.of(
            new Action("view", "See employee records", "See names, departments and managers.", ANY),
            new Action("manage", "Edit employee records", "Change job and personal details.", EnumSet.of(DataScope.DEPARTMENT, DataScope.ENTITY, DataScope.TENANT)))),
        new Module("reports", "Reports", true, List.of(
            new Action("leave", "See leave reports", "Leave balances and days taken.", OTHERS),
            new Action("claims", "See claims reports", "Claims approved, waiting and paid.", OTHERS),
            new Action("attendance", "See attendance reports", "Hours, lateness, absence and overtime by person and month.", OTHERS))),
        new Module("workflow", "Approval workflows", false, List.of(
            new Action("manage", "Manage approval workflows", "Build approval workflows: who approves which requests, in what order.", COMPANY))),
        new Module("admin", "Administration", false, List.of(
            new Action("users", "Manage users", "Add people, assign roles and approvers, disable accounts.", COMPANY),
            new Action("roles", "Manage roles", "Create roles and choose their permissions.", COMPANY),
            new Action("company", "Manage company settings", "Company details, code, modules and departments.", COMPANY))));

    private static final Map<String, Action> BY_PERMISSION = new LinkedHashMap<>();

    static {
        for (Module m : MODULES) {
            for (Action a : m.actions()) BY_PERMISSION.put(a.permission(m.key()), a);
        }
    }

    private PermissionCatalog() {
    }

    public static Optional<Action> action(String permission) {
        return Optional.ofNullable(BY_PERMISSION.get(permission));
    }

    /** The module key of a permission, e.g. "leave" for "leave:approve". */
    public static String moduleOf(String permission) {
        int i = permission.indexOf(':');
        return i < 0 ? permission : permission.substring(0, i);
    }

    public static boolean isLicensable(String module) {
        return MODULES.stream().anyMatch(m -> m.key().equals(module) && m.licensable());
    }

    public static List<String> licensableModules() {
        return MODULES.stream().filter(Module::licensable).map(Module::key).toList();
    }

    /** Every permission at its widest allowed scope (what the Super admin role holds). */
    public static Map<String, DataScope> everything() {
        Map<String, DataScope> all = new LinkedHashMap<>();
        BY_PERMISSION.forEach((perm, action) ->
            all.put(perm, action.scopes().stream().reduce(DataScope.SELF, DataScope::widest)));
        return all;
    }
}
