package com.hrms.identity;

import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who am I and what can I do. Clients use this to decide which screens to show;
 * the server still checks every action on its own.
 */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

    record Me(String userId, String employeeId, String tenantId, String companyCode, String companyName,
              String name, String email, List<String> roles, List<String> permissions,
              Map<String, Object> scopes, List<String> modules, boolean mustChangePassword) {
    }

    @GetMapping
    Me me(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> scopes = jwt.getClaimAsMap("scopes");
        return new Me(jwt.getSubject(), jwt.getClaimAsString("employee_id"), jwt.getClaimAsString("tenant_id"),
            jwt.getClaimAsString("company_code"), jwt.getClaimAsString("company_name"),
            jwt.getClaimAsString("name"), jwt.getClaimAsString("email"),
            orEmpty(jwt.getClaimAsStringList("roles")), orEmpty(jwt.getClaimAsStringList("perms")),
            scopes == null ? Map.of() : scopes, orEmpty(jwt.getClaimAsStringList("modules")),
            Boolean.TRUE.equals(jwt.getClaimAsBoolean("pwd_change")));
    }

    @GetMapping("/permissions")
    Map<String, List<String>> permissions(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("permissions", orEmpty(jwt.getClaimAsStringList("perms")));
    }

    private static List<String> orEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }
}
