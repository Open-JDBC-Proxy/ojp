package org.openjproxy.grpc.server.sql;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class PostgreSQLILikeTest {
    @Test
    void testPostgreSQLILikeOperator_WithConversion() {
        // Test with conversion enabled - ILIKE should be recognized
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "POSTGRESQL", "", true, false, null);
        
        String sql = "SELECT * FROM users WHERE name ILIKE '%john%'";
        SqlEnhancementResult result = engine.enhance(sql);
        
        // Should parse successfully without errors
        assertNotNull(result.getEnhancedSql(), "SQL should be enhanced");
        assertFalse(result.isHasErrors(), "Should not have errors - ILIKE is valid PostgreSQL operator");
        
        System.out.println("Original SQL: " + sql);
        System.out.println("Enhanced SQL: " + result.getEnhancedSql());
    }
    
    @Test
    void testPostgreSQLILikeOperator_WithoutConversion() {
        // Test without conversion - ILIKE should still be recognized during parsing
        SqlEnhancerEngine engine = new SqlEnhancerEngine(true, "POSTGRESQL", "", false, false, null);
        
        String sql = "SELECT * FROM users WHERE name ILIKE '%test%'";
        SqlEnhancementResult result = engine.enhance(sql);
        
        // Should parse successfully
        assertNotNull(result.getEnhancedSql(), "SQL should be parsed");
        assertFalse(result.isHasErrors(), "Should not have errors");
    }
}
