package com.hrms.shared.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * Holds the tenant of the current request. Set from the authenticated token by
 * {@link TenantContextFilter}; never from a request body or URL.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static UUID require() {
        return current().orElseThrow(() -> new IllegalStateException("No tenant bound to this request"));
    }

    public static void clear() {
        CURRENT.remove();
    }
}
