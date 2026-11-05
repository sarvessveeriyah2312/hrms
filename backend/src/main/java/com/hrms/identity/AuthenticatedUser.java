package com.hrms.identity;

import com.hrms.shared.security.DataScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything an access token says about the signed-in user. Built from the database at login and refresh.
 * {@code scopes} maps each permission to its widest granted scope; permissions of switched-off modules are left out.
 * {@code mustChangePassword}: they signed in with a temporary password an admin set, and must choose their own.
 */
record AuthenticatedUser(UUID userId, UUID tenantId, String companyCode, String companyName,
                         UUID employeeId, String name, String email,
                         List<String> roles, Map<String, DataScope> scopes, List<String> modules,
                         boolean mustChangePassword) {

    List<String> permissions() {
        return List.copyOf(scopes.keySet());
    }
}
