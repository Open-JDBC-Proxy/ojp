package org.openjproxy.grpc.server.cache;

import com.openjproxy.grpc.OpResult;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * StreamObserver wrapper that intercepts query results for caching.
 * <p>
 * Forwards all calls to the wrapped observer immediately (no delay for client),
 * and stores the first OpResult in cache after successful query completion.
 * </p>
 */
@Slf4j
public class CachingStreamObserver implements StreamObserver<OpResult> {
    private final StreamObserver<OpResult> delegate;
    private final QueryCacheKey cacheKey;
    private final CacheRule cacheRule;
    private final String datasourceName;
    private OpResult capturedResult = null;
    private static final long MAX_CACHE_SIZE_BYTES = 200L * 1024; // 200KB - cast to long
    
    public CachingStreamObserver(
            StreamObserver<OpResult> delegate,
            QueryCacheKey cacheKey,
            CacheRule cacheRule,
            String datasourceName) {
        this.delegate = delegate;
        this.cacheKey = cacheKey;
        this.cacheRule = cacheRule;
        this.datasourceName = datasourceName;
    }
    
    @Override
    public void onNext(OpResult value) {
        // Forward immediately to client (no delay)
        delegate.onNext(value);
        
        // Capture first result for caching (if not already captured)
        if (capturedResult == null && value.hasQueryResult()) {
            capturedResult = value;
        }
    }
    
    @Override
    public void onError(Throwable t) {
        // Query failed - don't cache, forward error
        delegate.onError(t);
    }
    
    @Override
    public void onCompleted() {
        // Forward completion to client first
        delegate.onCompleted();
        
        // Then attempt to cache the result (if captured)
        if (capturedResult != null) {
            try {
                storeToCacheIfEligible();
            } catch (Exception e) {
                // Log but don't fail - caching is best-effort
                log.warn("Failed to store result in cache: datasource={}, error={}", 
                        datasourceName, e.getMessage());
            }
        }
    }
    
    private void storeToCacheIfEligible() {
        com.openjproxy.grpc.OpQueryResultProto queryResult = capturedResult.getQueryResult();
        
        // Check if result is empty
        if (queryResult.getRowsCount() == 0) {
            log.debug("Empty result set, not caching: datasource={}", datasourceName);
            return;
        }
        
        // Check size before caching
        int serializedSize = queryResult.getSerializedSize();
        if (serializedSize > MAX_CACHE_SIZE_BYTES) {
            log.debug("Result too large to cache: size={}KB, max={}KB, datasource={}", 
                    serializedSize / 1024, MAX_CACHE_SIZE_BYTES / 1024, datasourceName);
            return;
        }
        
        // Build CachedQueryResult with proto directly
        Instant now = Instant.now();
        Duration ttl = cacheRule.getTtl();
        Set<String> affectedTables = new HashSet<>(cacheRule.getInvalidateOn());
        
        CachedQueryResult cachedResult = new CachedQueryResult(
                queryResult, now, now.plus(ttl), affectedTables);
        
        // Store in cache
        QueryResultCacheRegistry registry = QueryResultCacheRegistry.getInstance();
        QueryResultCache cache = registry.getOrCreate(datasourceName);
        
        cache.put(cacheKey, cachedResult);
        
        log.debug("Stored in cache: datasource={}, rows={}, size={}KB", 
                datasourceName, queryResult.getRowsCount(), serializedSize / 1024);
    }
}
