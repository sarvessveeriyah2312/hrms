package com.hrms.shared.security;

import java.util.Map;
import java.util.Optional;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Used in method security expressions: {@code @PreAuthorize("@perm.has('leave:approve')")}.
 * Data-scope checks (SELF / TEAM / DEPARTMENT / ENTITY / TENANT) belong in each module's service,
 * because they depend on the record being accessed; {@link #scopeOf} gives the caller's scope.
 */
@Component("perm")
public class PermissionChecker {

    public boolean has(String permission) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        String wanted = "PERM_" + permission;
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(wanted::equals);
    }

    /** The caller's scope for a permission, from the token's {@code scopes} claim. Empty when not granted. */
    public Optional<DataScope> scopeOf(String permission) {
        return scopes().map(m -> m.get(permission));
    }

    /** All of the caller's permissions with their scopes. */
    public Map<String, DataScope> allScopes() {
        return scopes().orElse(Map.of());
    }

    private Optional<Map<String, DataScope>> scopes() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt)) return Optional.empty();
        Map<String, Object> raw = jwt.getToken().getClaimAsMap("scopes");
        if (raw == null) return Optional.of(Map.of());
        var out = new java.util.HashMap<String, DataScope>();
        raw.forEach((k, v) -> {
            try {
                out.put(k, DataScope.valueOf(String.valueOf(v)));
            } catch (IllegalArgumentException ignored) {
                // unknown scope in a token: treat as not granted
            }
        });
        return Optional.of(out);
    }
}
