package org.openjproxy.grpc.server;
import net.jpountz.xxhash.XXHashFactory;
import net.jpountz.xxhash.XXHash64;
import java.nio.charset.StandardCharsets;

/**
 * SqlQueryXXHash provides a utility for generating unique, deterministic IDs for SQL query strings using the xxHash64 algorithm.
 *
 * Why xxHash?
 * ------------
 * For identifying queries in a database system, we require a hash function that is:
 *   - Fast (cheap in processing power)
 *   - Has a very low probability of collisions for non-malicious input
 *   - Suitable for large-scale, non-cryptographic scenarios (e.g., deduplication, caching, indexing)
 *
 * The following table compares several hash algorithms:
 *
 * | Hash         | Speed            | Collision Resistance (Non-crypto use) | Cryptographic? | Typical Usage             |
 * |--------------|------------------|---------------------------------------|----------------|--------------------------|
 * | SHA-256      | Slow             | Excellent                             | Yes            | Security, signatures     |
 * | MD5          | Moderate         | Good (for non-crypto)                 | Yes*           | Checksums, dedup         |
 * | MurmurHash3  | Very fast        | Excellent (for non-crypto)            | No             | Databases, hashing       |
 * | xxHash       | Extremely fast   | Excellent (for non-crypto)            | No             | Databases, file hashing  |
 * | CityHash     | Very fast        | Excellent (for non-crypto)            | No             | Large-scale systems      |
 *
 * xxHash is chosen here because it is extremely fast, has excellent distribution for typical database workloads,
 * and provides more than enough uniqueness for non-cryptographic use cases—making it ideal for lightweight query identification.
 */
public class SqlStatementXXHash {
    private static final XXHashFactory FACTORY = XXHashFactory.fastestInstance();
    private static final long SEED = 0x9747b28c; // Arbitrary seed, can be any long

    /**
     * Normalize the SQL query string for better consistency.
     * - Lowercase
     * - Trim
     * - Collapse multiple whitespace
     */
    public static String normalizeSql(String sql) {
        if (sql == null) {
            return "";
        }
        return sql.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    /**
     * Returns the 64-bit xxHash of the normalized SQL query as a hex string.
     */
    public static String hashSqlQuery(String sql) {
        String normalized = normalizeSql(sql);
        byte[] data = normalized.getBytes(StandardCharsets.UTF_8);
        XXHash64 hash64 = FACTORY.hash64();
        long hash = hash64.hash(data, 0, data.length, SEED);
        return Long.toHexString(hash);
    }
}
