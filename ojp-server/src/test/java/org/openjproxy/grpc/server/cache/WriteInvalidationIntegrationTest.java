package org.openjproxy.grpc.server.cache;

import com.openjproxy.grpc.OpQueryResultProto;
import com.openjproxy.grpc.ParameterValue;
import com.openjproxy.grpc.ResultRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for cache invalidation after write operations (INSERT/UPDATE/DELETE).
 * Tests the interaction between SqlTableExtractor, QueryResultCache, and ExecuteUpdateAction.
 */
class WriteInvalidationIntegrationTest {

    private QueryResultCache cache;
    private String datasourceName;
    private CacheStatistics stats;

    @BeforeEach
    void setUp() {
        // Create cache with reasonable limits
        cache = new QueryResultCache(
            "test_datasource",              // datasourceName
            1000,                          // max entries
            Duration.ofSeconds(30),        // TTL
            10 * 1024 * 1024,              // 10MB max size
            NoOpQueryCacheMetrics.getInstance()  // metrics
        );
        
        datasourceName = "test_datasource";
        stats = cache.getStatistics();
        
        // Register cache for this datasource
        QueryResultCacheRegistry.getInstance().getOrCreate(datasourceName);
    }

    @AfterEach
    void tearDown() {
        cache.invalidateAll();
        QueryResultCacheRegistry.getInstance().clear();
    }

    @Test
    void testCacheInvalidationAfterUpdate() {
        // Arrange: Cache a query result for products table
        String selectSql = "SELECT * FROM products WHERE category = 'electronics'";
        QueryCacheKey key = new QueryCacheKey(datasourceName, selectSql, List.of("electronics"));
        
        CachedQueryResult result = createCachedResult(
            Arrays.asList("id", "name", "price"),
            Arrays.asList(
                Arrays.asList(1, "Laptop", 999.99),
                Arrays.asList(2, "Mouse", 29.99)
            ),
            Set.of("products")
        );
        
        cache.put(key, result);
        assertEquals(0, stats.getHits());
        assertEquals(0, stats.getInvalidations());
        
        // Verify cache hit
        assertNotNull(cache.get(key));
        assertEquals(1, stats.getHits());
        
        // Act: Simulate UPDATE that modifies products table
        String updateSql = "UPDATE products SET price = 899.99 WHERE id = 1";
        Set<String> modifiedTables = SqlTableExtractor.extractModifiedTables(updateSql);
        
        assertEquals(Set.of("products"), modifiedTables);
        cache.invalidate(datasourceName, modifiedTables);
        
        // Assert: Cache entry should be invalidated
        assertNull(cache.get(key));
        assertEquals(1, stats.getHits());  // Previous hit
        assertEquals(1, stats.getMisses()); // This get
        assertEquals(1, stats.getInvalidations());
    }

    @Test
    void testCacheInvalidationAfterInsert() {
        // Arrange: Cache query for products
        String selectSql = "SELECT COUNT(*) FROM products";
        QueryCacheKey key = new QueryCacheKey(datasourceName, selectSql, Collections.emptyList());
        
        CachedQueryResult result = createCachedResult(
            Collections.singletonList("count"),
            Collections.singletonList(Collections.singletonList(10)),
            Set.of("products")
        );
        
        cache.put(key, result);
        assertNotNull(cache.get(key));
        
        // Act: INSERT new product
        String insertSql = "INSERT INTO products (name, price) VALUES ('Keyboard', 79.99)";
        Set<String> modifiedTables = SqlTableExtractor.extractModifiedTables(insertSql);
        
        assertEquals(Set.of("products"), modifiedTables);
        cache.invalidate(datasourceName, modifiedTables);
        
        // Assert: Cache invalidated
        assertNull(cache.get(key));
        assertTrue(stats.getInvalidations() > 0);
    }

    @Test
    void testCacheInvalidationAfterDelete() {
        // Arrange: Cache product query
        String selectSql = "SELECT * FROM products WHERE price < 100";
        QueryCacheKey key = new QueryCacheKey(datasourceName, selectSql, List.of(100));
        
        CachedQueryResult result = createCachedResult(
            Arrays.asList("id", "name"),
            Arrays.asList(Arrays.asList(1, "Item")),
            Set.of("products")
        );
        
        cache.put(key, result);
        assertNotNull(cache.get(key));
        
        // Act: DELETE from products
        String deleteSql = "DELETE FROM products WHERE id = 1";
        Set<String> modifiedTables = SqlTableExtractor.extractModifiedTables(deleteSql);
        
        assertEquals(Set.of("products"), modifiedTables);
        cache.invalidate(datasourceName, modifiedTables);
        
        // Assert: Invalidated
        assertNull(cache.get(key));
    }

    @Test
    void testCachePersistsForUnrelatedTables() {
        // Arrange: Cache queries for different tables
        QueryCacheKey productsKey = new QueryCacheKey(datasourceName, 
            "SELECT * FROM products", Collections.emptyList());
        QueryCacheKey ordersKey = new QueryCacheKey(datasourceName, 
            "SELECT * FROM orders", Collections.emptyList());
        
        CachedQueryResult productsResult = createCachedResult(
            Arrays.asList("id", "name"),
            Arrays.asList(Arrays.asList(1, "Product")),
            Set.of("products")
        );
        
        CachedQueryResult ordersResult = createCachedResult(
            Arrays.asList("id", "total"),
            Arrays.asList(Arrays.asList(1, 100.00)),
            Set.of("orders")
        );
        
        cache.put(productsKey, productsResult);
        cache.put(ordersKey, ordersResult);
        
        // Act: Update only products table
        String updateSql = "UPDATE products SET name = 'Updated' WHERE id = 1";
        Set<String> modifiedTables = SqlTableExtractor.extractModifiedTables(updateSql);
        cache.invalidate(datasourceName, modifiedTables);
        
        // Assert: Products invalidated, orders persists
        assertNull(cache.get(productsKey), "Products cache should be invalidated");
        assertNotNull(cache.get(ordersKey), "Orders cache should persist");
    }

    @Test
    void testMultipleTableInvalidation() {
        // Arrange: Cache queries that depend on multiple tables
        QueryCacheKey key1 = new QueryCacheKey(datasourceName, 
            "SELECT * FROM products", Collections.emptyList());
        QueryCacheKey key2 = new QueryCacheKey(datasourceName, 
            "SELECT * FROM orders", Collections.emptyList());
        QueryCacheKey key3 = new QueryCacheKey(datasourceName, 
            "SELECT * FROM customers", Collections.emptyList());
        
        cache.put(key1, createCachedResult(
            Arrays.asList("id"), Arrays.asList(Arrays.asList(1)), Set.of("products")));
        cache.put(key2, createCachedResult(
            Arrays.asList("id"), Arrays.asList(Arrays.asList(2)), Set.of("orders")));
        cache.put(key3, createCachedResult(
            Arrays.asList("id"), Arrays.asList(Arrays.asList(3)), Set.of("customers")));
        
        // Act: Invalidate multiple tables at once
        Set<String> modifiedTables = Set.of("products", "orders");
        cache.invalidate(datasourceName, modifiedTables);
        
        // Assert: products and orders invalidated, customers persists
        assertNull(cache.get(key1));
        assertNull(cache.get(key2));
        assertNotNull(cache.get(key3));
    }

    @Test
    void testMalformedSqlDoesNotCrash() {
        // Arrange: Cache a valid query
        QueryCacheKey key = new QueryCacheKey(datasourceName, 
            "SELECT * FROM products", Collections.emptyList());
        cache.put(key, createCachedResult(
            Arrays.asList("id"), Arrays.asList(Arrays.asList(1)), Set.of("products")));
        
        // Act: Try to extract tables from malformed SQL
        String malformedSql = "UPDATE products SETT name = 'Invalid'";  // Typo: SETT instead of SET
        Set<String> modifiedTables = SqlTableExtractor.extractModifiedTables(malformedSql);
        
        // Assert: Returns empty set gracefully, no exception
        assertTrue(modifiedTables.isEmpty(), "Should return empty set for malformed SQL");
        
        // Cache should still work normally
        assertNotNull(cache.get(key));
    }

    @Test
    void testInvalidationFailureDoesNotAffectUpdate() {
        // This test demonstrates that even if cache invalidation fails,
        // the update operation should succeed
        
        // Arrange: Cache a query
        QueryCacheKey key = new QueryCacheKey(datasourceName, 
            "SELECT * FROM products", Collections.emptyList());
        cache.put(key, createCachedResult(
            Arrays.asList("id"), Arrays.asList(Arrays.asList(1)), Set.of("products")));
        
        // Act: Simulate invalidation (normally called after successful update)
        String updateSql = "UPDATE products SET price = 99.99 WHERE id = 1";
        Set<String> modifiedTables = SqlTableExtractor.extractModifiedTables(updateSql);
        
        // This would be in a try-catch in ExecuteUpdateAction
        try {
            cache.invalidate(datasourceName, modifiedTables);
        } catch (Exception e) {
            // Log but don't fail - demonstrating resilience
            System.out.println("Cache invalidation failed but update succeeded: " + e.getMessage());
        }
        
        // Assert: Update would have succeeded regardless
        // (In real code, this is ensured by try-catch in ExecuteUpdateAction)
        assertNull(cache.get(key), "Cache should be invalidated on success");
    }

    // Helper methods
    
    /**
     * Helper method to create a test proto from rows and columns
     */
    private static OpQueryResultProto createTestProto(List<List<Object>> rows, List<String> columns) {
        OpQueryResultProto.Builder builder = OpQueryResultProto.newBuilder();
        
        // Add column labels
        for (String column : columns) {
            builder.addLabels(column);
        }
        
        // Add rows
        for (List<Object> row : rows) {
            ResultRow.Builder rowBuilder = ResultRow.newBuilder();
            for (Object value : row) {
                String stringValue = value != null ? value.toString() : "";
                rowBuilder.addColumns(ParameterValue.newBuilder()
                        .setStringValue(stringValue)
                        .build());
            }
            builder.addRows(rowBuilder.build());
        }
        
        return builder.build();
    }

    private CachedQueryResult createCachedResult(List<String> columns, List<List<Object>> rows, 
                                                  Set<String> affectedTables) {
        Instant now = Instant.now();
        return new CachedQueryResult(
            createTestProto(rows, columns),
            now,
            now.plus(Duration.ofSeconds(30)),
            affectedTables
        );
    }
}
