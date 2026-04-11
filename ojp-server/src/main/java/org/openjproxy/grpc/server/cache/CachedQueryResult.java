package org.openjproxy.grpc.server.cache;

import com.openjproxy.grpc.OpQueryResultProto;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable cached query result with proto data and expiration information.
 * Thread-safe and suitable for concurrent access.
 * <p>
 * Stores the proto object directly to avoid conversion overhead on cache hits.
 * </p>
 */
public final class CachedQueryResult {
    private final OpQueryResultProto queryResultProto;  // Cached proto - immutable
    private final Instant cachedAt;
    private final Instant expiresAt;
    private final Set<String> affectedTables;  // For invalidation
    private final int serializedSizeBytes;  // Proto's actual serialized size

    /**
     * Creates a new cached query result from a proto object.
     *
     * @param queryResultProto the query result proto (immutable)
     * @param cachedAt when the result was cached
     * @param expiresAt when the result expires
     * @param affectedTables tables affected by this query (for invalidation)
     */
    public CachedQueryResult(
            OpQueryResultProto queryResultProto,
            Instant cachedAt,
            Instant expiresAt,
            Set<String> affectedTables) {
        this.queryResultProto = Objects.requireNonNull(queryResultProto, "queryResultProto must not be null");
        this.cachedAt = Objects.requireNonNull(cachedAt, "cachedAt must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.affectedTables = affectedTables == null ? Set.of() : Set.copyOf(affectedTables);
        this.serializedSizeBytes = queryResultProto.getSerializedSize();
    }

    /**
     * Checks if this cache entry has expired.
     *
     * @return true if the current time is after the expiration time
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Checks if this cache entry has expired at a specific time.
     *
     * @param now the time to check against
     * @return true if the specified time is after the expiration time
     */
    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    /**
     * Gets the cached query result proto.
     * This proto can be sent directly to the client without conversion.
     *
     * @return the immutable query result proto
     */
    public OpQueryResultProto getQueryResultProto() {
        return queryResultProto;
    }

    public Instant getCachedAt() {
        return cachedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Set<String> getAffectedTables() {
        // Always return a new defensive copy - can't use Set.copyOf() because it returns
        // the same instance when given an already-immutable Set (idempotent behavior).
        // Wrap in unmodifiableSet to maintain immutability while ensuring new instance.
        return java.util.Collections.unmodifiableSet(new java.util.HashSet<>(affectedTables));
    }

    public long getEstimatedSizeBytes() {
        return serializedSizeBytes;
    }

    public int getRowCount() {
        return queryResultProto.getRowsCount();
    }

    public int getColumnCount() {
        return queryResultProto.getLabelsCount();
    }

    @Override
    public String toString() {
        return "CachedQueryResult{" +
            "rowCount=" + getRowCount() +
            ", columnCount=" + getColumnCount() +
            ", cachedAt=" + cachedAt +
            ", expiresAt=" + expiresAt +
            ", sizeBytes=" + serializedSizeBytes +
            ", affectedTables=" + affectedTables +
            '}';
    }
}
