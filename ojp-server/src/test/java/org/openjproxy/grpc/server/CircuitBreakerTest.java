package org.openjproxy.grpc.server;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CircuitBreakerTest {
    private static final int THOUSAND = 1000;
    private static final int FAILURE_THRESHOLD = 3;
    private static final int THREE_HUNDRED = 300;
    private static final int FOUR_HUNDRED = 400;
    private static final int FIVE_HUNDRED = 500;

    @Test
    void testAllowsWhenNoFailures() {
        CircuitBreaker breaker = new CircuitBreaker(THOUSAND, FAILURE_THRESHOLD);
        assertDoesNotThrow(() -> breaker.preCheck("SELECT 1"));
    }

    @Test
    void testBlocksAfterThreeFailures() {
        CircuitBreaker breaker = new CircuitBreaker(5000, 3);
        String sql = "SELECT * FROM test";
        SQLException ex = new SQLException("fail");
        // Fail three times
        breaker.onFailure(sql, ex);
        breaker.onFailure(sql, ex);
        breaker.onFailure(sql, ex);

        SQLException thrown = assertThrows(SQLException.class, () -> breaker.preCheck(sql));
        assertEquals("fail", thrown.getMessage());
    }

    @Test
    void testAllowsAgainAfterOpenTimeoutAndSuccessResets() throws InterruptedException, SQLException {
        CircuitBreaker breaker = new CircuitBreaker(THREE_HUNDRED, FAILURE_THRESHOLD);
        String sql = "UPDATE X SET Y=1";
        SQLException ex = new SQLException("fail");

        // Trip breaker
        breaker.onFailure(sql, ex);
        breaker.onFailure(sql, ex);
        breaker.onFailure(sql, ex);
        assertThrows(SQLException.class, () -> breaker.preCheck(sql));

        // Wait for open period to pass
        Thread.sleep(FOUR_HUNDRED);
        // Should allow one through (half-open)
        assertDoesNotThrow(() -> breaker.preCheck(sql));
        // Success should reset
        breaker.onSuccess(sql);
        assertDoesNotThrow(() -> breaker.preCheck(sql));
    }

    @Test
    void testResetsOnSuccess() throws SQLException {
        CircuitBreaker breaker = new CircuitBreaker(THOUSAND, FAILURE_THRESHOLD);
        String sql = "INSERT X";
        SQLException ex = new SQLException("fail2");
        breaker.onFailure(sql, ex);
        breaker.onFailure(sql, ex);
        breaker.onFailure(sql, ex);
        assertThrows(SQLException.class, () -> breaker.preCheck(sql));
        breaker.onSuccess(sql);
        assertDoesNotThrow(() -> breaker.preCheck(sql));
    }

    @Test
    void testOnFailureIsNoOpWhenAlreadyOpen() {
        CircuitBreaker breaker = new CircuitBreaker(FIVE_HUNDRED, FAILURE_THRESHOLD);
        String sql = "SELECT fail";
        SQLException ex1 = new SQLException("fail1");
        SQLException ex2 = new SQLException("fail2");
        // Trip breaker
        breaker.onFailure(sql, ex1);
        breaker.onFailure(sql, ex1);
        breaker.onFailure(sql, ex1);

        // Now breaker is open, further failures should not change lastError
        breaker.onFailure(sql, ex2);

        SQLException thrown = assertThrows(SQLException.class, () -> breaker.preCheck(sql));
        assertEquals("fail1", thrown.getMessage());
    }
}