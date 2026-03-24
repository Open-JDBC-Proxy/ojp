package org.openjproxy.grpc.server.paging;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects pagination syntax in SQL queries across multiple database dialects and
 * provides utilities to generate the next-page SQL.
 *
 * <p>Supported dialects:</p>
 * <ul>
 *   <li><b>PostgreSQL / MySQL / SQLite:</b> {@code LIMIT n OFFSET m} or {@code LIMIT n}</li>
 *   <li><b>MySQL shorthand:</b> {@code LIMIT m, n} (OFFSET m, page-size n)</li>
 *   <li><b>SQL Server / Oracle 12c+ / DB2:</b>
 *       {@code OFFSET m ROWS FETCH NEXT n ROWS ONLY}</li>
 *   <li><b>DB2 / Oracle first-page:</b> {@code FETCH FIRST n ROWS ONLY}</li>
 * </ul>
 */
public class PaginationDetector {

    // -----------------------------------------------------------------
    // Compiled patterns (immutable, thread-safe)
    // -----------------------------------------------------------------

    /**
     * Pattern 1 – LIMIT n OFFSET m  (PostgreSQL, MySQL ≥5.7, SQLite)
     * Groups: (1)=limit, (2)=offset
     */
    private static final Pattern LIMIT_OFFSET = Pattern.compile(
            "(?i)\\bLIMIT\\s+(\\d+)\\s+OFFSET\\s+(\\d+)\\b"
    );

    /**
     * Pattern 2 – OFFSET m ROWS? FETCH NEXT|FIRST n ROWS? ONLY  (SQL Server, Oracle 12c+, DB2)
     * Groups: (1)=offset, (2)=fetch-size
     */
    private static final Pattern OFFSET_FETCH = Pattern.compile(
            "(?i)\\bOFFSET\\s+(\\d+)\\s+ROWS?\\s+FETCH\\s+(?:NEXT|FIRST)\\s+(\\d+)\\s+ROWS?\\s+ONLY\\b"
    );

    /**
     * Pattern 3 – LIMIT m, n  (MySQL shorthand: first arg = OFFSET, second arg = page-size)
     * Groups: (1)=offset, (2)=limit
     */
    private static final Pattern LIMIT_COMMA = Pattern.compile(
            "(?i)\\bLIMIT\\s+(\\d+)\\s*,\\s*(\\d+)\\b"
    );

    /**
     * Pattern 4 – FETCH NEXT|FIRST n ROWS? ONLY, without preceding OFFSET  (first page)
     * Groups: (1)=fetch-size
     */
    private static final Pattern FETCH_ONLY = Pattern.compile(
            "(?i)\\bFETCH\\s+(?:NEXT|FIRST)\\s+(\\d+)\\s+ROWS?\\s+ONLY\\b"
    );

    /**
     * Pattern 5 – standalone LIMIT n with no OFFSET anywhere  (first page)
     * Groups: (1)=limit
     */
    private static final Pattern LIMIT_ONLY = Pattern.compile(
            "(?i)\\bLIMIT\\s+(\\d+)\\b"
    );

    /** Used to detect any OFFSET keyword in the query (guards Pattern 5 usage). */
    private static final Pattern HAS_OFFSET = Pattern.compile(
            "(?i)\\bOFFSET\\b"
    );

    // Private constructor – static utility class
    private PaginationDetector() {
    }

    // -----------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------

    /**
     * Detects whether {@code sql} contains a pagination clause and returns the
     * corresponding {@link PageInfo}.  Returns an empty Optional when no
     * pagination is detected or when the SQL is null/blank.
     *
     * <p>Patterns are evaluated in priority order; the first match wins.</p>
     *
     * @param sql the SQL string to inspect
     * @return an Optional containing page information, or empty if not paginated
     */
    public static Optional<PageInfo> detect(String sql) {
        if (sql == null || sql.isBlank()) {
            return Optional.empty();
        }

        // Pattern 1: LIMIT n OFFSET m
        Matcher m1 = LIMIT_OFFSET.matcher(sql);
        if (m1.find()) {
            long limit = Long.parseLong(m1.group(1));
            long offset = Long.parseLong(m1.group(2));
            return Optional.of(new PageInfo(offset, limit));
        }

        // Pattern 2: OFFSET m ROWS FETCH NEXT/FIRST n ROWS ONLY
        Matcher m2 = OFFSET_FETCH.matcher(sql);
        if (m2.find()) {
            long offset = Long.parseLong(m2.group(1));
            long fetchSize = Long.parseLong(m2.group(2));
            return Optional.of(new PageInfo(offset, fetchSize));
        }

        // Pattern 3: LIMIT m, n (MySQL shorthand)
        Matcher m3 = LIMIT_COMMA.matcher(sql);
        if (m3.find()) {
            long offset = Long.parseLong(m3.group(1));
            long limit = Long.parseLong(m3.group(2));
            return Optional.of(new PageInfo(offset, limit));
        }

        // Patterns 4 and 5 only apply when the query has no OFFSET clause at all.
        // Evaluate once and reuse the result.
        boolean noOffset = !HAS_OFFSET.matcher(sql).find();

        // Pattern 4: FETCH FIRST/NEXT n ROWS ONLY  (first page, offset = 0)
        if (noOffset) {
            Matcher m4 = FETCH_ONLY.matcher(sql);
            if (m4.find()) {
                long fetchSize = Long.parseLong(m4.group(1));
                return Optional.of(new PageInfo(0, fetchSize));
            }
        }

        // Pattern 5: standalone LIMIT n (first page, offset = 0)
        if (noOffset) {
            Matcher m5 = LIMIT_ONLY.matcher(sql);
            if (m5.find()) {
                long limit = Long.parseLong(m5.group(1));
                return Optional.of(new PageInfo(0, limit));
            }
        }

        return Optional.empty();
    }

    /**
     * Builds the SQL for the <em>next</em> page by incrementing the OFFSET
     * (or inserting one when absent) in the given SQL string.
     *
     * <p>The method applies the same pattern-priority order as {@link #detect}.
     * Returns {@code null} when the next-page SQL cannot be determined.</p>
     *
     * @param sql      the original paginated SQL
     * @param pageInfo the page information returned by {@link #detect}
     * @return the next-page SQL, or {@code null} if transformation is not possible
     */
    public static String buildNextPageSql(String sql, PageInfo pageInfo) {
        if (sql == null || pageInfo == null) {
            return null;
        }

        long nextOffset = pageInfo.getNextPageOffset();

        // Pattern 1: replace OFFSET value in LIMIT n OFFSET m
        Matcher m1 = LIMIT_OFFSET.matcher(sql);
        if (m1.find()) {
            // group(2) is the offset value; replace only that token
            return sql.substring(0, m1.start(2)) + nextOffset + sql.substring(m1.end(2));
        }

        // Pattern 2: replace OFFSET value in OFFSET m ROWS FETCH ... ONLY
        Matcher m2 = OFFSET_FETCH.matcher(sql);
        if (m2.find()) {
            // group(1) is the offset value
            return sql.substring(0, m2.start(1)) + nextOffset + sql.substring(m2.end(1));
        }

        // Pattern 3: replace offset in LIMIT m, n
        Matcher m3 = LIMIT_COMMA.matcher(sql);
        if (m3.find()) {
            // group(1) is the offset value (first number in LIMIT m, n)
            return sql.substring(0, m3.start(1)) + nextOffset + sql.substring(m3.end(1));
        }

        // Patterns 4 and 5 only apply when the query has no OFFSET clause at all.
        // Evaluate once and reuse the result.
        boolean noOffset = !HAS_OFFSET.matcher(sql).find();

        // Pattern 4: FETCH FIRST/NEXT n ROWS ONLY without OFFSET → insert OFFSET before FETCH
        if (noOffset) {
            Matcher m4 = FETCH_ONLY.matcher(sql);
            if (m4.find()) {
                int fetchStart = m4.start();
                return sql.substring(0, fetchStart)
                        + "OFFSET " + nextOffset + " ROWS "
                        + sql.substring(fetchStart);
            }
        }

        // Pattern 5: standalone LIMIT n → append OFFSET n
        if (noOffset) {
            Matcher m5 = LIMIT_ONLY.matcher(sql);
            if (m5.find()) {
                return sql + " OFFSET " + nextOffset;
            }
        }

        return null;
    }
}
