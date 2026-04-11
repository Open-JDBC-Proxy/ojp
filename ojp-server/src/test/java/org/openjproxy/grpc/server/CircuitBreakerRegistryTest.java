package org.openjproxy.grpc.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

public class CircuitBreakerRegistryTest {
    private CircuitBreakerRegistry registry;
    private int openMs = 1000;
    private int failureThreshold = 3;

    @BeforeEach
    void setUp(){
        this.registry = new CircuitBreakerRegistry(openMs, failureThreshold);
    }

    @Test
    void testIdentityPersistence(){
        CircuitBreaker cb1_first = registry.get("DB-1");
        CircuitBreaker cb1_second = registry.get("DB-1");
        CircuitBreaker cb2 = registry.get("DB-2");

        assertSame(cb1_first, cb1_second, "Registry should return the same instance for the same key");
        assertNotEquals(cb1_first, cb2, "Registry must return different instances for different keys");
    }

    @Test
    void testCrossTenantIsolation(){
        CircuitBreaker db1 = registry.get("DB-1");
        CircuitBreaker db2 = registry.get("DB-2");

        String sql = "SELECT 1";
        SQLException sqlException = new SQLException("Database down");

        // Step 1: Trip the breaker for DB-1
        for (int i = 0; i < failureThreshold; i++) {
            db1.onFailure(sql, sqlException);
        }

        // Step 2: Verify DB-1 is OPEN (blocked)
        assertThrows(SQLException.class, () -> db1.preCheck(sql),
                "DB-1 should be tripped and blocking queries"
        );

        // Step 3: Verify DB-2 is still CLOSED (healthy)
        assertDoesNotThrow(() -> db2.preCheck(sql),
                "DB-2 should NOT be affected by DB-1's failure"
        );

        // Step 4: Verify DB-2 can succeed independently
        db2.onSuccess(sql);
        assertDoesNotThrow(() -> db2.preCheck(sql));
    }
}
