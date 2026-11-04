package com.hrms.shared.security;

/**
 * How far a permission reaches, narrowest first. A wider scope includes every narrower one:
 * TEAM covers your own records and your direct reports; TENANT covers the whole company.
 */
public enum DataScope {
    SELF, TEAM, DEPARTMENT, ENTITY, TENANT;

    /** True when this scope reaches at least as far as {@code other}. */
    public boolean covers(DataScope other) {
        return ordinal() >= other.ordinal();
    }

    public static DataScope widest(DataScope a, DataScope b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
