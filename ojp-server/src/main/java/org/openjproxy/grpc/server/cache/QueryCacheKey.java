package org.openjproxy.grpc.server.cache;

import java.util.List;
import java.util.Objects;

/**
 * Immutable cache key for query results.
 * Combines datasource name, SQL (normalized), and parameters to uniquely identify a query.
 * Thread-safe and suitable for use as HashMap key.
 */
public final class QueryCacheKey {
    private final String datasourceName;
    private final String normalizedSql;
    private final List<Object> parameters;
    private final int hashCode;  // Pre-computed for O(1) lookup

    /**
     * Creates a new query cache key.
     *
     * @param datasourceName the datasource name (must not be null)
     * @param sql the SQL query (will be normalized)
     * @param parameters the query parameters (will be copied to immutable list)
     */
    public QueryCacheKey(String datasourceName, String sql, List<Object> parameters) {
        this.datasourceName = Objects.requireNonNull(datasourceName, "datasourceName must not be null");
        this.normalizedSql = normalizeSql(Objects.requireNonNull(sql, "sql must not be null"));
        this.parameters = parameters == null ? List.of() : List.copyOf(parameters);
        this.hashCode = computeHashCode();
    }

    /**
     * Normalizes SQL by collapsing whitespace for consistent cache keys.
     * Example: "SELECT  *\n FROM  users" -> "SELECT * FROM users"
     */
    private static String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private int computeHashCode() {
        int result = datasourceName.hashCode();
        result = 31 * result + normalizedSql.hashCode();
        result = 31 * result + parameters.hashCode();
        return result;
    }

    public String getDatasourceName() {
        return datasourceName;
    }

    public String getNormalizedSql() {
        return normalizedSql;
    }

    public List<Object> getParameters() {
        return parameters;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof QueryCacheKey other)) return false;
        return datasourceName.equals(other.datasourceName)
            && normalizedSql.equals(other.normalizedSql)
            && parameters.equals(other.parameters);
    }

    @Override
    public int hashCode() {
        return hashCode;  // Return pre-computed hash for O(1) lookup
    }

    @Override
    public String toString() {
        return "QueryCacheKey{" +
            "datasource='" + datasourceName + '\'' +
            ", sql='" + (normalizedSql.length() > 50 ? normalizedSql.substring(0, 50) + "..." : normalizedSql) + '\'' +
            ", paramCount=" + parameters.size() +
            '}';
    }
}
