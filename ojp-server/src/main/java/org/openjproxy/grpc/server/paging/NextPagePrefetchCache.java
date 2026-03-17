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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Cache for pre-fetched next pages of paginated SELECT queries.
 *
 * <h2>Behaviour</h2>
 * <ol>
 *   <li>When a paginated query is executed, the server fires a virtual thread that
 *       executes the <em>next</em> page SQL against the database and stores the result
 *       in this cache, keyed by the datasource identifier and (trimmed) next-page SQL
 *       string.</li>
 *   <li>When the client subsequently requests the next page, the server first checks
 *       this cache.  If a matching entry is found the result is served from memory,
 *       and another prefetch is started for the page after that.</li>
 *   <li>If the client requests a page that is still being fetched, the server waits
 *       up to {@code prefetchWaitTimeoutMs} for the operation to complete before
 *       falling back to a regular database query.</li>
 * </ol>
 *
 * <h2>Datasource isolation</h2>
 * Each cache entry is scoped to a specific datasource by including the
 * {@code datasourceId} in the cache key.  Two datasources executing the same SQL
 * query will never share a prefetched page.
 *
 * <h2>Materialised LOB data</h2>
 * All column types are cached:
 * <ul>
 *   <li>BLOB / LONGVARBINARY / VARBINARY / BINARY → materialized as {@code byte[]}</li>
 *   <li>CLOB / NCLOB / LONGVARCHAR / LONGNVARCHAR → materialized as {@code String}</li>
 *   <li>All other types → stored using {@code ResultSet.getObject()}</li>
 * </ul>
 * Queries that use <em>LOB session references</em> as input parameters (i.e., parameters
 * of type BLOB or CLOB that reference a session-scoped LOB object) are still skipped
 * because those references cannot be transferred to a separate prefetch connection.
 *
 * <h2>Background cleanup</h2>
 * All cache instances share a single application-wide daemon thread
 * ({@link #CLEANUP_EXECUTOR}) that is created once for the lifetime of the JVM.
 * When enabled, each instance registers its own periodic eviction task on that shared
 * executor; {@link #shutdown()} cancels the task for that instance without affecting
 * the shared thread or any other instance's tasks.  Entries expire after
 * {@code ttlSeconds} regardless of whether they were ever consumed.
 *
 * <h2>Thread safety</h2>
 * All public methods are thread-safe.  The internal cache uses a
 * {@link ConcurrentHashMap} and prefetch threads are Java 21 virtual threads.
 */
@Slf4j
public class NextPagePrefetchCache implements AutoCloseable {

    /**
     * Application-wide single-threaded executor shared by ALL enabled cache instances.
     * Using a {@code static final} field guarantees exactly ONE background cleanup thread
     * per JVM regardless of how many {@code NextPagePrefetchCache} instances are created.
     * The executor runs on a virtual thread; virtual threads are always daemon threads,
     * so they never prevent JVM shutdown.
     */
    private static final ScheduledExecutorService CLEANUP_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r ->
                    Thread.ofVirtual().name("ojp-prefetch-cache-cleanup").unstarted(r));

    /** Pre-compiled pattern for stripping newlines and tabs in log abbreviations. */
    private static final Pattern NEWLINE_PATTERN = Pattern.compile("[\\r\\n\\t]+");

    private final boolean enabled;
    private final int maxEntries;
    private final long ttlMs;
    private final long prefetchWaitTimeoutMs;

    /**
     * Per-datasource prefetch-wait timeout overrides.
     * Key: datasource connection hash (see {@code ConnectionHashGenerator}).
     * Value: timeout in milliseconds.
     * When an entry is present it takes precedence over {@link #prefetchWaitTimeoutMs}.
     */
    private final ConcurrentHashMap<String, Long> datasourcePrefetchWaitTimeoutMs
            = new ConcurrentHashMap<>();

    /**
     * Maps {@code "<datasourceId>\u0001<normalized-sql>"} to the asynchronous result of the prefetch.
     * Including the datasource ID in the key ensures that two different datasources executing
     * the same SQL do not share cache entries.
     */
    private final ConcurrentHashMap<String, CompletableFuture<CachedPage>> cache
            = new ConcurrentHashMap<>();

    /**
     * Handle to this instance's eviction task on {@link #CLEANUP_EXECUTOR}.
     * {@code null} reference when the cleanup job is disabled ({@code cleanupIntervalSeconds == 0}).
     * Cancelled atomically by {@link #shutdown()} to avoid concurrent double-cancel races.
     */
    private final AtomicReference<ScheduledFuture<?>> cleanupTask = new AtomicReference<>();

    /**
     * Creates a new cache instance.
     *
     * @param enabled                whether the feature is enabled
     * @param maxEntries             maximum number of entries to keep (oldest removed first)
     * @param ttlSeconds             time-to-live for each entry in seconds
     * @param prefetchWaitTimeoutMs  max time (ms) to wait for an in-progress prefetch
     *                               before falling back to a live DB query
     * @param cleanupIntervalSeconds interval (seconds) between background eviction sweeps;
     *                               {@code 0} disables the background job for this instance
     */
    public NextPagePrefetchCache(boolean enabled, int maxEntries,
                                 long ttlSeconds, long prefetchWaitTimeoutMs,
                                 long cleanupIntervalSeconds) {
        this.enabled = enabled;
        this.maxEntries = maxEntries;
        this.ttlMs = ttlSeconds * 1000L;
        this.prefetchWaitTimeoutMs = prefetchWaitTimeoutMs;

        if (enabled && cleanupIntervalSeconds > 0) {
            // Register this instance's eviction task on the single shared executor.
            // The executor has exactly one thread, so all tasks run sequentially on
            // that same thread — never more than one cleanup thread in the JVM.
            cleanupTask.set(CLEANUP_EXECUTOR.scheduleAtFixedRate(
                    this::evictExpiredOrCompleted,
                    cleanupIntervalSeconds, cleanupIntervalSeconds, TimeUnit.SECONDS));
            log.debug("Prefetch cache cleanup registered every {}s on shared executor",
                    cleanupIntervalSeconds);
        }
    }

    /**
     * Returns {@code true} when this cache is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the current number of entries in the cache (in-progress + completed).
     * Primarily intended for monitoring and testing.
     */
    public int cacheSize() {
        return cache.size();
    }

    /**
     * Registers a per-datasource prefetch-wait timeout that overrides the global default
     * for the specified datasource.
     *
     * <p>Calling this method multiple times for the same {@code datasourceId} simply
     * replaces the previously registered value.  The registration is thread-safe.</p>
     *
     * @param datasourceId the unique identifier of the datasource (connection hash)
     * @param timeoutMs    the maximum time in milliseconds to wait for an in-progress
     *                     prefetch before falling back to a live DB query
     */
    public void registerDatasourcePrefetchWaitTimeout(String datasourceId, long timeoutMs) {
        if (datasourceId != null) {
            datasourcePrefetchWaitTimeoutMs.put(datasourceId, timeoutMs);
            log.debug("Registered per-datasource prefetchWaitTimeoutMs={} for datasourceId={}",
                    timeoutMs, datasourceId);
        }
    }

    /**
     * Cancels this instance's periodic cleanup task on the shared executor.
     * The shared executor itself is left running so that other cache instances
     * (if any) are not affected.  Safe to call multiple times; uses an atomic
     * swap to prevent concurrent double-cancel races.
     */
    public void shutdown() {
        ScheduledFuture<?> task = cleanupTask.getAndSet(null);
        if (task != null) {
            task.cancel(false);
            log.debug("Prefetch cache cleanup task cancelled");
        }
    }

    /** Implements {@link AutoCloseable} by delegating to {@link #shutdown()}. */
    @Override
    public void close() {
        shutdown();
    }

    // -----------------------------------------------------------------
    // Cache read
    // -----------------------------------------------------------------

    /**
     * Retrieves the cached page for the given datasource + SQL pair, waiting up to
     * {@code prefetchWaitTimeoutMs} when the prefetch is still in progress.
     *
     * <p>Returns an empty Optional when:</p>
     * <ul>
     *   <li>no entry exists for {@code datasourceId} + {@code sql}</li>
     *   <li>the entry is expired</li>
     *   <li>the prefetch failed or timed out</li>
     * </ul>
     *
     * <p>The entry is removed from the cache after a successful retrieval (single-use
     * semantics) so that concurrent requests for the same page can each independently
     * obtain the result and start the next prefetch.</p>
     *
     * @param datasourceId the unique identifier of the datasource (e.g. connection hash);
     *                     used to isolate entries from different datasources that may
     *                     share the same SQL text
     * @param sql          the exact paginated SQL sent by the client
     * @return an Optional containing the cached page, or empty if unavailable
     */
    public Optional<CachedPage> getIfReady(String datasourceId, String sql) {
        String key = normalizeKey(datasourceId, sql);
        CompletableFuture<CachedPage> future = cache.get(key);
        if (future == null) {
            return Optional.empty();
        }

        long effectiveTimeoutMs = datasourcePrefetchWaitTimeoutMs.getOrDefault(
                datasourceId, prefetchWaitTimeoutMs);

        try {
            CachedPage page = future.get(effectiveTimeoutMs, TimeUnit.MILLISECONDS);
            // Remove after use (single-use semantics; if another thread also grabs
            // the same entry concurrently, it gets a copy of the same data).
            cache.remove(key, future);

            if (page == null) {
                log.debug("Prefetch for '{}' returned no-cache result", abbreviate(sql));
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
                    abbreviate(sql), effectiveTimeoutMs);
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
     * <p>The method returns immediately.  If an entry for {@code datasourceId} +
     * {@code nextPageSql} already exists (either in-progress or completed), no new
     * prefetch is started.  Entries are evicted lazily when the cache exceeds
     * {@code maxEntries}.</p>
     *
     * <p>BLOB/CLOB parameters are not supported; if any parameter has type
     * {@code BLOB} or {@code CLOB} the prefetch is silently skipped.</p>
     *
     * @param dataSource  the DataSource from which to obtain a dedicated prefetch connection
     * @param datasourceId the unique identifier of the datasource (e.g. connection hash);
     *                     used to scope the cache entry so two datasources do not share pages
     * @param nextPageSql the SQL for the next page (produced by {@link PaginationDetector#buildNextPageSql})
     * @param params      the query parameters (may be null or empty for non-prepared queries)
     */
    public void prefetchAsync(DataSource dataSource, String datasourceId,
                              String nextPageSql, List<Parameter> params) {
        if (!enabled || dataSource == null || nextPageSql == null) {
            return;
        }

        // Skip if any parameter is a LOB reference (session-scoped, can't be used in prefetch)
        if (params != null && params.stream().anyMatch(NextPagePrefetchCache::isLobParameter)) {
            log.debug("Skipping prefetch – query contains LOB parameters");
            return;
        }

        String key = normalizeKey(datasourceId, nextPageSql);

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
        // Include a safe SQL snippet in the thread name for easier thread-dump analysis
        String threadName = "ojp-next-page-prefetch[" + abbreviate(nextPageSql, 40) + "]";

        Thread.ofVirtual().name(threadName).start(() -> {
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
     */
    private static CachedPage executeAndReadAllRows(Connection conn, String sql,
                                                    List<Parameter> params) throws SQLException {
        if (params.isEmpty()) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                return readAllRows(rs);
            }
        } else {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                setNonLobParameters(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    return readAllRows(rs);
                }
            }
        }
    }

    /**
     * Materialises all rows from {@code rs}, eagerly reading all column values
     * (including LOB types) into in-memory representations.
     */
    private static CachedPage readAllRows(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();

        // Collect column labels
        List<String> labels = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            labels.add(meta.getColumnName(i));
        }

        // Read all rows eagerly; materialise binary and character LOBs
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
     * Reads a single column value, eagerly materialising LOB data so that
     * it remains valid after the connection is closed:
     * <ul>
     *   <li>BLOB / LONGVARBINARY → {@code byte[]}</li>
     *   <li>VARBINARY / BINARY → {@code byte[]}</li>
     *   <li>CLOB / NCLOB / LONGVARCHAR / LONGNVARCHAR → {@code String}</li>
     *   <li>All other types → returned as-is via {@code ResultSet.getObject()}</li>
     * </ul>
     */
    private static Object readColumnValue(ResultSet rs, int col, int sqlType) throws SQLException {
        switch (sqlType) {
            case Types.BLOB:
            case Types.LONGVARBINARY: {
                java.sql.Blob blob = rs.getBlob(col);
                if (blob == null) {
                    return null;
                }
                try (java.io.InputStream stream = blob.getBinaryStream()) {
                    return stream.readAllBytes();
                } catch (java.io.IOException e) {
                    throw new SQLException("Failed to read BLOB data", e);
                }
            }
            case Types.VARBINARY:
            case Types.BINARY:
                return rs.getBytes(col);
            case Types.CLOB:
            case Types.LONGVARCHAR: {
                try (java.io.Reader reader = rs.getCharacterStream(col)) {
                    if (reader == null) {
                        return null;
                    }
                    java.io.StringWriter sw = new java.io.StringWriter();
                    reader.transferTo(sw);
                    return sw.toString();
                } catch (java.io.IOException e) {
                    throw new SQLException("Failed to read CLOB data", e);
                }
            }
            case Types.NCLOB:
            case Types.LONGNVARCHAR: {
                try (java.io.Reader reader = rs.getNCharacterStream(col)) {
                    if (reader == null) {
                        return null;
                    }
                    java.io.StringWriter sw = new java.io.StringWriter();
                    reader.transferTo(sw);
                    return sw.toString();
                } catch (java.io.IOException e) {
                    throw new SQLException("Failed to read NCLOB data", e);
                }
            }
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
     * The datasource ID is separated from the SQL by the ASCII SOH character
     * ({@code \u0001}), which cannot appear in a SQL string or a connection hash,
     * guaranteeing no key collisions regardless of the datasource ID content.
     */
    private static String normalizeKey(String datasourceId, String sql) {
        String normalizedSql = sql.trim().toLowerCase(java.util.Locale.ROOT);
        return (datasourceId == null ? "" : datasourceId) + '\u0001' + normalizedSql;
    }

    /** Returns a safe short preview of an SQL string for log messages. */
    private static String abbreviate(String sql) {
        return abbreviate(sql, 80);
    }

    /** Returns a safe short preview of an SQL string, truncated to {@code maxLen} characters. */
    private static String abbreviate(String sql, int maxLen) {
        if (sql == null) {
            return "<null>";
        }
        // Remove newlines/tabs for single-line thread names
        String singleLine = NEWLINE_PATTERN.matcher(sql).replaceAll(" ").trim();
        return singleLine.length() <= maxLen ? singleLine : singleLine.substring(0, maxLen - 3) + "...";
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
