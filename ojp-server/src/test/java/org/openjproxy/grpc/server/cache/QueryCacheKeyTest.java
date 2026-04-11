package org.openjproxy.grpc.server.cache;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryCacheKeyTest {

    @Test
    void testConstructorWithValidParameters() {
        QueryCacheKey key = new QueryCacheKey("postgres_prod", "SELECT * FROM users", List.of(1, "test"));
        
        assertEquals("postgres_prod", key.getDatasourceName());
        assertEquals("SELECT * FROM users", key.getNormalizedSql());
        assertEquals(List.of(1, "test"), key.getParameters());
    }

    @Test
    void testConstructorWithNullDatasource() {
        assertThrows(NullPointerException.class, () ->
            new QueryCacheKey(null, "SELECT *", List.of())
        );
    }

    @Test
    void testConstructorWithNullSql() {
        assertThrows(NullPointerException.class, () ->
            new QueryCacheKey("ds", null, List.of())
        );
    }

    @Test
    void testConstructorWithNullParameters() {
        QueryCacheKey key = new QueryCacheKey("ds", "SELECT *", null);
        assertEquals(List.of(), key.getParameters());
    }

    @Test
    void testSqlNormalization() {
        QueryCacheKey key1 = new QueryCacheKey("ds", "SELECT  *   \n  FROM   users", List.of());
        QueryCacheKey key2 = new QueryCacheKey("ds", "SELECT * FROM users", List.of());
        
        assertEquals(key1.getNormalizedSql(), key2.getNormalizedSql());
        assertEquals(key1, key2);
    }

    @Test
    void testEqualityWithSameValues() {
        QueryCacheKey key1 = new QueryCacheKey("ds", "SELECT * FROM users", List.of(1, 2));
        QueryCacheKey key2 = new QueryCacheKey("ds", "SELECT * FROM users", List.of(1, 2));
        
        assertEquals(key1, key2);
        assertEquals(key1.hashCode(), key2.hashCode());
    }

    @Test
    void testEqualityWithDifferentDatasource() {
        QueryCacheKey key1 = new QueryCacheKey("ds1", "SELECT *", List.of());
        QueryCacheKey key2 = new QueryCacheKey("ds2", "SELECT *", List.of());
        
        assertNotEquals(key1, key2);
    }

    @Test
    void testEqualityWithDifferentSql() {
        QueryCacheKey key1 = new QueryCacheKey("ds", "SELECT * FROM users", List.of());
        QueryCacheKey key2 = new QueryCacheKey("ds", "SELECT * FROM products", List.of());
        
        assertNotEquals(key1, key2);
    }

    @Test
    void testEqualityWithDifferentParameters() {
        QueryCacheKey key1 = new QueryCacheKey("ds", "SELECT *", List.of(1));
        QueryCacheKey key2 = new QueryCacheKey("ds", "SELECT *", List.of(2));
        
        assertNotEquals(key1, key2);
    }

    @Test
    void testEqualityWithSameObject() {
        QueryCacheKey key = new QueryCacheKey("ds", "SELECT *", List.of());
        
        assertEquals(key, key);
    }

    @Test
    void testEqualityWithNull() {
        QueryCacheKey key = new QueryCacheKey("ds", "SELECT *", List.of());
        
        assertNotEquals(null, key);
    }

    @Test
    void testEqualityWithDifferentClass() {
        QueryCacheKey key = new QueryCacheKey("ds", "SELECT *", List.of());
        
        assertNotEquals("not a key", key);
    }

    @Test
    void testHashCodeConsistency() {
        QueryCacheKey key = new QueryCacheKey("ds", "SELECT * FROM users", List.of(1, 2));
        
        int hash1 = key.hashCode();
        int hash2 = key.hashCode();
        
        assertEquals(hash1, hash2);
    }

    @Test
    void testHashCodeWithEqualObjects() {
        QueryCacheKey key1 = new QueryCacheKey("ds", "SELECT * FROM users", List.of(1, 2));
        QueryCacheKey key2 = new QueryCacheKey("ds", "SELECT * FROM users", List.of(1, 2));
        
        assertEquals(key1.hashCode(), key2.hashCode());
    }

    @Test
    void testImmutability() {
        List<Object> params = new java.util.ArrayList<>();
        params.add(1);
        
        QueryCacheKey key = new QueryCacheKey("ds", "SELECT *", params);
        
        // Modify original list
        params.add(2);
        
        // Key should not be affected
        assertEquals(1, key.getParameters().size());
    }

    @Test
    void testParametersAreUnmodifiable() {
        QueryCacheKey key = new QueryCacheKey("ds", "SELECT *", List.of(1, 2));
        
        List<Object> parameters = key.getParameters();
        assertThrows(UnsupportedOperationException.class, () -> parameters.add(3));
    }

    @Test
    void testToString() {
        QueryCacheKey key = new QueryCacheKey("ds", "SELECT * FROM users", List.of(1, 2));
        String result = key.toString();
        
        assertTrue(result.contains("ds"));
        assertTrue(result.contains("SELECT * FROM users"));
        assertTrue(result.contains("paramCount=2"));
    }

    @Test
    void testToStringWithLongSql() {
        String longSql = "SELECT * FROM users WHERE name LIKE '%test%' AND age > 18 AND status = 'active' ORDER BY created_at DESC";
        QueryCacheKey key = new QueryCacheKey("ds", longSql, List.of());
        String result = key.toString();
        
        assertTrue(result.contains("..."));
    }

    @Test
    void testWhitespaceNormalizationPreservesWords() {
        QueryCacheKey key = new QueryCacheKey("ds", "  SELECT   *  \n\t FROM   users  \r\n  WHERE  id = ?  ", List.of());
        
        assertEquals("SELECT * FROM users WHERE id = ?", key.getNormalizedSql());
    }

    @Test
    void testEmptyParametersList() {
        QueryCacheKey key = new QueryCacheKey("ds", "SELECT *", List.of());
        
        assertEquals(0, key.getParameters().size());
        assertNotNull(key.getParameters());
    }
}
