package org.openjproxy.xa.pool.commons.housekeeping;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openjproxy.xa.pool.XABackendSession;
import org.openjproxy.xa.pool.commons.CommonsPool2XADataSource;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for max lifetime functionality.
 */
class MaxLifetimeTest {

    private XADataSource mockXADataSource;
    private CommonsPool2XADataSource pooledDataSource;

    @BeforeEach
    void setUp() throws Exception {
        mockXADataSource = mock(XADataSource.class);
        XAConnection mockXAConnection = mock(XAConnection.class);
        Connection mockConnection = mock(Connection.class);

        when(mockXADataSource.getXAConnection()).thenReturn(mockXAConnection);
        when(mockXAConnection.getConnection()).thenReturn(mockConnection);
        when(mockXAConnection.getXAResource()).thenReturn(mock(javax.transaction.xa.XAResource.class));
        when(mockConnection.isClosed()).thenReturn(false);
        when(mockConnection.getAutoCommit()).thenReturn(true);
        when(mockConnection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(mockConnection.isValid(anyInt())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        if (pooledDataSource != null) {
            pooledDataSource.close();
        }
    }

    @Test
    @DisplayName("Max lifetime is configurable")
    void testMaxLifetimeConfigurable() {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.maxLifetimeMs", "600000");  // 10 minutes

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        HousekeepingConfig housekeepingConfig = pooledDataSource.getHousekeepingConfig();
        assertEquals(600000L, housekeepingConfig.getMaxLifetimeMs(),
                "Max lifetime should match configured value");
    }

    @Test
    @DisplayName("Max lifetime defaults to 30 minutes")
    void testMaxLifetimeDefault() {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        HousekeepingConfig housekeepingConfig = pooledDataSource.getHousekeepingConfig();
        assertEquals(1800000L, housekeepingConfig.getMaxLifetimeMs(),
                "Max lifetime should default to 30 minutes (1800000 ms)");
    }

    @Test
    @DisplayName("Max lifetime can be disabled by setting to 0")
    void testMaxLifetimeDisabled() {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.maxLifetimeMs", "0");  // Disabled

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        HousekeepingConfig housekeepingConfig = pooledDataSource.getHousekeepingConfig();
        assertEquals(0L, housekeepingConfig.getMaxLifetimeMs(),
                "Max lifetime should be disabled (0)");
    }

    @Test
    @DisplayName("Idle before recycle is configurable")
    void testIdleBeforeRecycleConfigurable() {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.idleBeforeRecycleMs", "120000");  // 2 minutes

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        HousekeepingConfig housekeepingConfig = pooledDataSource.getHousekeepingConfig();
        assertEquals(120000L, housekeepingConfig.getIdleBeforeRecycleMs(),
                "Idle before recycle should match configured value");
    }

    @Test
    @DisplayName("Idle before recycle defaults to 5 minutes")
    void testIdleBeforeRecycleDefault() {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        HousekeepingConfig housekeepingConfig = pooledDataSource.getHousekeepingConfig();
        assertEquals(300000L, housekeepingConfig.getIdleBeforeRecycleMs(),
                "Idle before recycle should default to 5 minutes (300000 ms)");
    }

    @Test
    @DisplayName("Session can be borrowed and returned successfully with max lifetime enabled")
    void testSessionBorrowReturnWithMaxLifetime() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.maxLifetimeMs", "1800000");  // 30 min
        config.put("xa.idleBeforeRecycleMs", "300000");  // 5 min

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        // Borrow a session
        XABackendSession session = pooledDataSource.borrowSession();
        assertNotNull(session, "Borrowed session should not be null");

        // Return the session
        pooledDataSource.returnSession(session);

        // Borrow again - should get the same session (recycled)
        XABackendSession session2 = pooledDataSource.borrowSession();
        assertNotNull(session2, "Second borrowed session should not be null");

        pooledDataSource.returnSession(session2);
    }

    @Test
    @DisplayName("Configuration presets work correctly")
    void testConfigurationPresets() {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "10");
        config.put("xa.maxLifetimeMs", "900000");  // 15 min
        config.put("xa.idleBeforeRecycleMs", "180000");  // 3 min
        config.put("xa.leakDetection.timeoutMs", "600000");  // 10 min

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        HousekeepingConfig housekeepingConfig = pooledDataSource.getHousekeepingConfig();
        assertEquals(900000L, housekeepingConfig.getMaxLifetimeMs());
        assertEquals(180000L, housekeepingConfig.getIdleBeforeRecycleMs());
        assertEquals(600000L, housekeepingConfig.getLeakTimeoutMs());
    }

    @Test
    @DisplayName("Validation works with max lifetime disabled")
    void testValidationWithMaxLifetimeDisabled() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.maxLifetimeMs", "0");  // Disabled

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        // Borrow and return session
        XABackendSession session = pooledDataSource.borrowSession();
        assertNotNull(session, "Session should be borrowed successfully");

        pooledDataSource.returnSession(session);

        // Borrow again - should work even though max lifetime is disabled
        XABackendSession session2 = pooledDataSource.borrowSession();
        assertNotNull(session2, "Session should be borrowed again successfully");

        pooledDataSource.returnSession(session2);
    }

    @Test
    @DisplayName("Housekeeping config includes all max lifetime settings")
    void testHousekeepingConfigIncludesMaxLifetimeSettings() {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.maxLifetimeMs", "1200000");
        config.put("xa.idleBeforeRecycleMs", "240000");

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        HousekeepingConfig housekeepingConfig = pooledDataSource.getHousekeepingConfig();
        assertNotNull(housekeepingConfig, "Housekeeping config should not be null");
        assertEquals(1200000L, housekeepingConfig.getMaxLifetimeMs());
        assertEquals(240000L, housekeepingConfig.getIdleBeforeRecycleMs());
        assertTrue(housekeepingConfig.isLeakDetectionEnabled(), "Leak detection should be enabled by default");
    }
}
