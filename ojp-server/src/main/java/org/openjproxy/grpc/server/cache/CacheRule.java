package org.openjproxy.grpc.server.cache;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable cache rule defining when and how to cache a specific query pattern.
 * Thread-safe and suitable for concurrent access.
 */
public final class CacheRule {
    private final Pattern sqlPattern;
    private final Duration ttl;
    private final List<String> invalidateOn;  // Table names that trigger invalidation
    private final boolean enabled;

    /**
     * Creates a new cache rule.
     *
     * @param sqlPattern regex pattern to match SQL queries
     * @param ttl time-to-live for cached results
     * @param invalidateOn list of table names that trigger cache invalidation
     * @param enabled whether this rule is enabled
     */
    public CacheRule(Pattern sqlPattern, Duration ttl, List<String> invalidateOn, boolean enabled) {
        this.sqlPattern = Objects.requireNonNull(sqlPattern, "sqlPattern must not be null");
        this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
        this.invalidateOn = invalidateOn == null ? List.of() : List.copyOf(invalidateOn);
        this.enabled = enabled;
        
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("TTL must be positive: " + ttl);
        }
    }

    /**
     * Checks if this rule matches the given SQL query.
     *
     * @param sql the SQL query to check
     * @return true if the pattern matches and the rule is enabled
     */
    public boolean matches(String sql) {
        if (!enabled) {
            return false;
        }
        return sqlPattern.matcher(sql).matches();
    }

    public Pattern getSqlPattern() {
        return sqlPattern;
    }

    public Duration getTtl() {
        return ttl;
    }

    public List<String> getInvalidateOn() {
        return invalidateOn;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Checks if this rule should invalidate cache when the specified table is modified.
     *
     * @param tableName the table name to check
     * @return true if this rule's cache should be invalidated
     */
    public boolean shouldInvalidateOn(String tableName) {
        if (!enabled) {
            return false;
        }
        // Empty invalidateOn means invalidate on ANY table modification
        if (invalidateOn.isEmpty()) {
            return true;
        }
        // Check if the table is in the invalidateOn list (case-insensitive)
        return invalidateOn.stream()
            .anyMatch(table -> table.equalsIgnoreCase(tableName));
    }

    @Override
    public String toString() {
        return "CacheRule{" +
            "pattern=" + sqlPattern.pattern() +
            ", ttl=" + ttl +
            ", invalidateOn=" + invalidateOn +
            ", enabled=" + enabled +
            '}';
    }
}
