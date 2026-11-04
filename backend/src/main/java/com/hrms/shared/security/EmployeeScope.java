package com.hrms.shared.security;

/**
 * Turns a data scope into a SQL condition on an employee table alias, so "Team" or "Department"
 * means the same thing in every module. The query must bind {@code :me} to the caller's employee id
 * (null when the user has no employee record, which then matches only TENANT).
 *
 * <ul>
 *   <li>SELF: only the caller</li>
 *   <li>TEAM: the caller, their direct reports, and people they are backup approver for</li>
 *   <li>DEPARTMENT: everyone in the caller's department</li>
 *   <li>ENTITY: everyone in the caller's legal entity</li>
 *   <li>TENANT: the whole company (row-level security still limits it to this company)</li>
 * </ul>
 */
public final class EmployeeScope {

    private EmployeeScope() {
    }

    public static String predicate(DataScope scope, String alias) {
        String e = alias;
        return switch (scope) {
            case SELF -> e + ".id = :me";
            case TEAM -> "(" + e + ".id = :me or " + e + ".manager_id = :me or " + e + ".backup_approver_id = :me)";
            case DEPARTMENT -> "(" + e + ".id = :me or " + e + ".department_id = (select department_id from employee where id = :me))";
            case ENTITY -> "(" + e + ".id = :me or " + e + ".entity_id = (select entity_id from employee where id = :me))";
            case TENANT -> "true";
        };
    }
}
