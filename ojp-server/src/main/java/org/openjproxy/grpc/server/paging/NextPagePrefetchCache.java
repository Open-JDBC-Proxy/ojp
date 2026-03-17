package org.openjproxy.grpc.server.paging;

import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.dto.ParameterType;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Cache for pre-fetched next pages of paginated SELECT queries.
 *
 * <h2>Behaviour</h2>
 * <ol>
 *   <li>When a paginated query is executed, the server fires a virtual thread that
 *       executes the <em>next</em> page SQL against the database and stores the result
 *       in this cache, keyed by the (trimmed) next-page SQL string.</li>
 *   <li>When the client subsequently requests the next page, the server first checks
 *       this cache.  If a matching entry is found the result is served from memory,
 *       and another prefetch is started for the page after that.</li>
 *   <li>If the client requests a page that is still being fetched, the server waits
 *       up to {@code prefetchWaitTimeoutMs} for the operation to complete before
 *       falling back to a regular database query.</li>
 * </ol>
 *
 * <h2>Limitations (first-pass implementation)</h2>
 * <ul>
 *   <li>CLOB / NCLOB columns are not cached – the prefetch skips storing the page.</li>
 *   <li>Parameters of type BLOB or CLOB (LOB references) are not supported in
 *       prefetch queries and will cause the prefetch to be skipped.</li>
 *   <li>This feature is <strong>disabled by default</strong> and must be enabled
 *       via {@code ojp.server.nextPageCache.enabled=true}.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * All public methods are thread-safe.  The internal cache uses a
 * {@link ConcurrentHashMap} and prefetch threads are Java 21 virtual threads.
 */
@Slf4j
public class NextPagePrefetchCache {

    private final boolean enabled;
    private final int maxEntries;
    private final long ttlMs;
    private final long prefetchWaitTimeoutMs;

    /**
     * Maps the (trimmed) next-page SQL to the asynchronous result of the prefetch.
     * A {@code null} value inside the future signals that caching was skipped
     * (e.g., CLOB columns detected).
     */
    private final ConcurrentHashMap<String, CompletableFuture<CachedPage>> cache
            = new ConcurrentHashMap<>();

    /**
     * Creates a new cache instance.
     *
     * @param enabled               whether the feature is enabled
     * @param maxEntries            maximum number of entries to keep (oldest removed first)
     * @param ttlSeconds            time-to-live for each entry in seconds
     * @param prefetchWaitTimeoutMs max time (ms) to wait for an in-progress prefetch
     *                              before falling back to a live DB query
     */
    public NextPagePrefetchCache(boolean enabled, int maxEntries,
                                 long ttlSeconds, long prefetchWaitTimeoutMs) {
        this.enabled = enabled;
        this.maxEntries = maxEntries;
        this.ttlMs = ttlSeconds * 1000L;
        this.prefetchWaitTimeoutMs = prefetchWaitTimeoutMs;
    }

    /**
     * Returns {@code true} when this cache is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    // -----------------------------------------------------------------
    // Cache read
    // -----------------------------------------------------------------

    /**
     * Retrieves the cached page for the given SQL, waiting up to
     * {@code prefetchWaitTimeoutMs} when the prefetch is still in progress.
     *
     * <p>Returns an empty Optional when:</p>
     * <ul>
     *   <li>no entry exists for {@code sql}</li>
     *   <li>the entry is expired</li>
     *   <li>the prefetch failed, returned a null result (e.g., CLOB detected), or timed out</li>
     * </ul>
     *
     * <p>The entry is removed from the cache after a successful retrieval (single-use
     * semantics) so that concurrent requests for the same page can each independently
     * obtain the result and start the next prefetch.</p>
     *
     * @param sql the exact paginated SQL sent by the client
     * @return an Optional containing the cached page, or empty if unavailable
     */
    public Optional<CachedPage> getIfReady(String sql) {
        String key = normalizeKey(sql);
        CompletableFuture<CachedPage> future = cache.get(key);
        if (future == null) {
            return Optional.empty();
        }

        try {
            CachedPage page = future.get(prefetchWaitTimeoutMs, TimeUnit.MILLISECONDS);
            // Remove after use (single-use semantics; if another thread also grabs
            // the same entry concurrently, it gets a copy of the same data).
            cache.remove(key, future);

            if (page == null) {
                log.debug("Prefetch for '{}' returned no-cache result (e.g. CLOB columns)", abbreviate(sql));
                return Optional.empty();
            }
            if (page.isExpired(ttlMs)) {
                log.debug("Cached page for '{}' has expired", abbreviate(sql));
                return Optional.empty();
            }
            log.debug("Cache HIT for '{}' ({} rows)", abbreviate(sql), page.getRows().size());
            return Optional.of(page);

        } catch (java.util.concurrent.TimeoutException e) {
            log.debug("Prefetch for '{}' did not complete within {}ms – falling back to live query",
                    abbreviate(sql), prefetchWaitTimeoutMs);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for prefetch of '{}'", abbreviate(sql));
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Prefetch for '{}' failed: {}", abbreviate(sql), e.getMessage());
            cache.remove(key, future);
            return Optional.empty();
        }
    }

    // -----------------------------------------------------------------
    // Cache write / prefetch trigger
    // -----------------------------------------------------------------

    /**
     * Starts an asynchronous prefetch of {@code nextPageSql} on a virtual thread.
     *
     * <p>The method returns immediately.  If an entry for {@code nextPageSql}
     * already exists (either in-progress or completed), no new prefetch is started.
     * Entries are evicted lazily when the cache exceeds {@code maxEntries}.</p>
     *
     * <p>BLOB/CLOB parameters are not supported; if any parameter has type
     * {@code BLOB} or {@code CLOB} the prefetch is silently skipped.</p>
     *
     * @param dataSource  the DataSource from which to obtain a dedicated prefetch connection
     * @param nextPageSql the SQL for the next page (produced by {@link PaginationDetector#buildNextPageSql})
     * @param params      the query parameters (may be null or empty for non-prepared queries)
     */
    public void prefetchAsync(DataSource dataSource, String nextPageSql, List<Parameter> params) {
        if (!enabled || dataSource == null || nextPageSql == null) {
            return;
        }

        // Skip if any parameter is a LOB reference (session-scoped, can't be used in prefetch)
        if (params != null && params.stream().anyMatch(NextPagePrefetchCache::isLobParameter)) {
            log.debug("Skipping prefetch – query contains LOB parameters");
            return;
        }

        String key = normalizeKey(nextPageSql);

        // Don't prefetch if already in-progress or completed
        if (cache.containsKey(key)) {
            log.debug("Prefetch already in progress/completed for '{}'", abbreviate(nextPageSql));
            return;
        }

        // Evict stale entries before inserting to respect max-size
        if (cache.size() >= maxEntries) {
            evictExpiredOrCompleted();
        }

        CompletableFuture<CachedPage> future = new CompletableFuture<>();
        // putIfAbsent avoids a race where two callers try to start the same prefetch
        if (cache.putIfAbsent(key, future) != null) {
            // Another thread won the race
            return;
        }

        log.debug("Starting prefetch for '{}'", abbreviate(nextPageSql));

        List<Parameter> paramsCopy = params == null ? List.of() : List.copyOf(params);

        Thread.ofVirtual().name("ojp-next-page-prefetch").start(() -> {
            try (Connection conn = dataSource.getConnection()) {
                CachedPage page = executeAndReadAllRows(conn, nextPageSql, paramsCopy);
                future.complete(page); // null signals "skip cache"
                log.debug("Prefetch completed for '{}' ({} rows cached)",
                        abbreviate(nextPageSql),
                        page != null ? page.getRows().size() : 0);
            } catch (Exception e) {
                log.warn("Prefetch failed for '{}': {}", abbreviate(nextPageSql), e.getMessage());
                future.completeExceptionally(e);
                cache.remove(key, future);
            }
        });
    }

    // -----------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------

    /**
     * Executes {@code sql} using the given connection and materialises all result
     * rows into a {@link CachedPage}.
     *
     * <p>Returns {@code null} when caching should be skipped (CLOB columns detected).</p>
     */
    private static CachedPage executeAndReadAllRows(Connection conn, String sql,
                                                    List<Parameter> params) throws SQLException {
        ResultSet rs;
        if (params.isEmpty()) {
            Statement stmt = conn.createStatement();
            rs = stmt.executeQuery(sql);
        } else {
            PreparedStatement ps = conn.prepareStatement(sql);
            setNonLobParameters(ps, params);
            rs = ps.executeQuery();
        }

        return readAllRows(rs);
    }

    /**
     * Materialises all rows from {@code rs}.  Returns {@code null} when the
     * result set contains CLOB/NCLOB columns (caching is not supported for those).
     */
    private static CachedPage readAllRows(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();

        // Collect column labels
        List<String> labels = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            labels.add(meta.getColumnName(i));
        }

        // Skip caching if CLOB / NCLOB columns are present
        for (int i = 1; i <= colCount; i++) {
            int sqlType = meta.getColumnType(i);
            if (sqlType == Types.CLOB || sqlType == Types.NCLOB) {
                log.debug("Skipping cache – CLOB/NCLOB column detected at index {}", i);
                return null;
            }
        }

        // Read all rows eagerly; convert binary types to byte arrays
        List<Object[]> rows = new ArrayList<>();
        while (rs.next()) {
            Object[] row = new Object[colCount];
            for (int i = 0; i < colCount; i++) {
                row[i] = readColumnValue(rs, i + 1, meta.getColumnType(i + 1));
            }
            rows.add(row);
        }

        return new CachedPage(labels, rows);
    }

    /**
     * Reads a single column value, eagerly materialising BLOB / binary data as
     * {@code byte[]} so that it remains valid after the connection is closed.
     */
    private static Object readColumnValue(ResultSet rs, int col, int sqlType) throws SQLException {
        switch (sqlType) {
            case Types.BLOB:
            case Types.LONGVARBINARY: {
                java.sql.Blob blob = rs.getBlob(col);
                if (blob == null) {
                    return null;
                }
                try {
                    return blob.getBinaryStream().readAllBytes();
                } catch (java.io.IOException e) {
                    throw new SQLException("Failed to read BLOB data", e);
                }
            }
            case Types.VARBINARY:
            case Types.BINARY:
                return rs.getBytes(col);
            case Types.DATE:
                return rs.getDate(col);
            case Types.TIMESTAMP:
                return rs.getTimestamp(col);
            default:
                return rs.getObject(col);
        }
    }

    /**
     * Sets non-LOB parameters on a PreparedStatement using the parameter list.
     * Only handles basic JDBC types (INT, LONG, STRING, DOUBLE, etc.).
     * LOB parameters are rejected before this method is called.
     */
    private static void setNonLobParameters(PreparedStatement ps,
                                            List<Parameter> params) throws SQLException {
        for (Parameter param : params) {
            int idx = param.getIndex();
            if (param.getValues().isEmpty()) {
                ps.setNull(idx, java.sql.Types.NULL);
                continue;
            }
            Object value = param.getValues().get(0);
            ParameterType type = param.getType();

            switch (type) {
                case INT    -> ps.setInt(idx, (int) value);
                case SHORT  -> ps.setShort(idx, ((Number) value).shortValue());
                case LONG   -> ps.setLong(idx, (long) value);
                case DOUBLE -> ps.setDouble(idx, (double) value);
                case FLOAT  -> ps.setFloat(idx, (float) value);
                case BOOLEAN -> ps.setBoolean(idx, (boolean) value);
                case STRING  -> ps.setString(idx, (String) value);
                case BIG_DECIMAL -> ps.setBigDecimal(idx, (BigDecimal) value);
                case DATE   -> ps.setDate(idx, (Date) value);
                case TIME   -> ps.setTime(idx, (Time) value);
                case TIMESTAMP -> ps.setTimestamp(idx, (Timestamp) value);
                case BYTES  -> ps.setBytes(idx, (byte[]) value);
                case NULL   -> ps.setNull(idx, (int) value);
                default     -> ps.setObject(idx, value);
            }
        }
    }

    /** Returns true for parameter types that reference session-scoped LOB objects. */
    private static boolean isLobParameter(Parameter param) {
        ParameterType type = param.getType();
        return type == ParameterType.BLOB || type == ParameterType.CLOB;
    }

    /**
     * Normalises a SQL string for use as a cache key:
     * strips leading/trailing whitespace and folds to lower-case so that
     * minor formatting differences do not result in cache misses.
     */
    private static String normalizeKey(String sql) {
        return sql.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Returns a safe short preview of an SQL string for log messages. */
    private static String abbreviate(String sql) {
        if (sql == null) {
            return "<null>";
        }
        return sql.length() <= 80 ? sql : sql.substring(0, 77) + "...";
    }

    /**
     * Removes cache entries that are either expired or whose future has completed
     * exceptionally.  Called before inserting a new entry to bound cache size.
     */
    private void evictExpiredOrCompleted() {
        cache.entrySet().removeIf(entry -> {
            CompletableFuture<CachedPage> f = entry.getValue();
            if (!f.isDone()) {
                return false; // still in progress – keep it
            }
            if (f.isCompletedExceptionally()) {
                return true; // failed – evict
            }
            CachedPage page = f.getNow(null);
            return page == null || page.isExpired(ttlMs);
        });
    }
}
