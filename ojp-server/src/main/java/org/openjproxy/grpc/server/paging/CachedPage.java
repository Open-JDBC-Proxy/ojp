package org.openjproxy.grpc.server.paging;

import java.util.Collections;
import java.util.List;

/**
 * Holds a single cached page of query results.
 *
 * <p>Instances are immutable once created.  The {@link #isExpired(long)} method
 * can be used to check whether the entry has exceeded its time-to-live.</p>
 */
public class CachedPage {

    private final List<String> columnLabels;
    private final List<Object[]> rows;
    private final long createdAtMs;

    /**
     * @param columnLabels ordered list of column names from the result set metadata
     * @param rows         result rows; each element is an array of column values
     */
    public CachedPage(List<String> columnLabels, List<Object[]> rows) {
        this.columnLabels = Collections.unmodifiableList(columnLabels);
        this.rows = Collections.unmodifiableList(rows);
        this.createdAtMs = System.currentTimeMillis();
    }

    /**
     * Returns the ordered list of column labels for this result set.
     */
    public List<String> getColumnLabels() {
        return columnLabels;
    }

    /**
     * Returns the cached rows.  Each element is an array of column values
     * in the same order as {@link #getColumnLabels()}.
     */
    public List<Object[]> getRows() {
        return rows;
    }

    /**
     * Returns the epoch milliseconds at which this entry was created.
     */
    public long getCreatedAtMs() {
        return createdAtMs;
    }

    /**
     * Returns {@code true} if the entry is older than {@code ttlMs} milliseconds.
     *
     * @param ttlMs time-to-live in milliseconds
     */
    public boolean isExpired(long ttlMs) {
        return System.currentTimeMillis() - createdAtMs > ttlMs;
    }
}
