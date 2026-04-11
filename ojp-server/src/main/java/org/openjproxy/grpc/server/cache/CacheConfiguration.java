package org.openjproxy.grpc.server.cache;

import java.util.List;
import java.util.Objects;

/**
 * Immutable collection of cache rules for a specific datasource.
 * Thread-safe and suitable for concurrent access.
 */
public final class CacheConfiguration {
    private final String datasourceName;
    private final boolean enabled;
    private final List<CacheRule> rules;  // Ordered by priority (first match wins)

    /**
     * Creates a new cache configuration.
     *
     * @param datasourceName the datasource name
     * @param enabled whether caching is enabled for this datasource
     * @param rules ordered list of cache rules (first match wins)
     */
    public CacheConfiguration(String datasourceName, boolean enabled, List<CacheRule> rules) {
        this.datasourceName = Objects.requireNonNull(datasourceName, "datasourceName must not be null");
        this.enabled = enabled;
        this.rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /**
     * Creates a disabled cache configuration (no caching).
     *
     * @param datasourceName the datasource name
     * @return disabled configuration
     */
    public static CacheConfiguration disabled(String datasourceName) {
        return new CacheConfiguration(datasourceName, false, List.of());
    }

    /**
     * Finds the first matching rule for the given SQL query.
     *
     * @param sql the SQL query
     * @return the matching rule, or null if no rule matches
     */
    public CacheRule findMatchingRule(String sql) {
        if (!enabled) {
            return null;
        }
        
        for (CacheRule rule : rules) {
            if (rule.matches(sql)) {
                return rule;
            }
        }
        
        return null;
    }

    /**
     * Checks if caching is enabled for this datasource.
     *
     * @return true if caching is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    public String getDatasourceName() {
        return datasourceName;
    }

    public List<CacheRule> getRules() {
        return rules;
    }

    /**
     * Checks if any rule should be invalidated when the specified table is modified.
     *
     * @param tableName the table name
     * @return true if any rule should be invalidated
     */
    public boolean shouldInvalidateOn(String tableName) {
        if (!enabled) {
            return false;
        }
        
        return rules.stream().anyMatch(rule -> rule.shouldInvalidateOn(tableName));
    }

    @Override
    public String toString() {
        return "CacheConfiguration{" +
            "datasource='" + datasourceName + '\'' +
            ", enabled=" + enabled +
            ", ruleCount=" + rules.size() +
            '}';
    }
}
