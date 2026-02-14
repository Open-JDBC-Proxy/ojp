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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for diagnostics functionality.
 */
class DiagnosticsTest {

    private XADataSource mockXADataSource;
    private XAConnection mockXAConnection;
    private Connection mockConnection;
    private CommonsPool2XADataSource pooledDataSource;

    @BeforeEach
    void setUp() throws Exception {
        mockXADataSource = mock(XADataSource.class);
        mockXAConnection = mock(XAConnection.class);
        mockConnection = mock(Connection.class);

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
    @DisplayName("Diagnostics is disabled by default")
    void testDiagnosticsDisabledByDefault() {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        HousekeepingConfig housekeepingConfig = pooledDataSource.getHousekeepingConfig();
        assertFalse(housekeepingConfig.isDiagnosticsEnabled(),
                "Diagnostics should be disabled by default");
    }

    @Test
    @DisplayName("Diagnostics can be enabled")
    void testDiagnosticsCanBeEnabled() {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.diagnostics.enabled", "true");

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        HousekeepingConfig housekeepingConfig = pooledDataSource.getHousekeepingConfig();
        assertTrue(housekeepingConfig.isDiagnosticsEnabled(),
                "Diagnostics should be enabled when configured");
    }

    @Test
    @DisplayName("Diagnostics interval is configurable")
    void testDiagnosticsIntervalConfigurable() {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.diagnostics.enabled", "true");
        config.put("xa.diagnostics.intervalMs", "120000");  // 2 minutes

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        HousekeepingConfig housekeepingConfig = pooledDataSource.getHousekeepingConfig();
        assertEquals(120000L, housekeepingConfig.getDiagnosticsIntervalMs(),
                "Diagnostics interval should match configured value");
    }

    @Test
    @DisplayName("Diagnostics interval defaults to 5 minutes")
    void testDiagnosticsIntervalDefault() {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.diagnostics.enabled", "true");

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        HousekeepingConfig housekeepingConfig = pooledDataSource.getHousekeepingConfig();
        assertEquals(300000L, housekeepingConfig.getDiagnosticsIntervalMs(),
                "Diagnostics interval should default to 5 minutes (300000 ms)");
    }

    @Test
    @DisplayName("Pool can be created with diagnostics enabled")
    void testPoolCreationWithDiagnostics() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.diagnostics.enabled", "true");
        config.put("xa.diagnostics.intervalMs", "60000");

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        assertNotNull(pooledDataSource, "Pool should be created successfully");

        // Verify we can borrow and return sessions
        XABackendSession session = pooledDataSource.borrowSession();
        assertNotNull(session, "Session should be borrowed successfully");

        pooledDataSource.returnSession(session);
    }

    @Test
    @DisplayName("Diagnostics works with leak detection enabled")
    void testDiagnosticsWithLeakDetection() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.leakDetection.enabled", "true");
        config.put("xa.diagnostics.enabled", "true");

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        HousekeepingConfig housekeepingConfig = pooledDataSource.getHousekeepingConfig();
        assertTrue(housekeepingConfig.isLeakDetectionEnabled(), "Leak detection should be enabled");
        assertTrue(housekeepingConfig.isDiagnosticsEnabled(), "Diagnostics should be enabled");

        // Verify both features work together
        XABackendSession session = pooledDataSource.borrowSession();
        assertNotNull(session, "Session should be borrowed successfully");

        pooledDataSource.returnSession(session);
    }

    @Test
    @DisplayName("Diagnostics works with max lifetime enabled")
    void testDiagnosticsWithMaxLifetime() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.maxLifetimeMs", "1800000");  // 30 min
        config.put("xa.diagnostics.enabled", "true");

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        HousekeepingConfig housekeepingConfig = pooledDataSource.getHousekeepingConfig();
        assertEquals(1800000L, housekeepingConfig.getMaxLifetimeMs());
        assertTrue(housekeepingConfig.isDiagnosticsEnabled(), "Diagnostics should be enabled");

        // Verify both features work together
        XABackendSession session = pooledDataSource.borrowSession();
        assertNotNull(session, "Session should be borrowed successfully");

        pooledDataSource.returnSession(session);
    }

    @Test
    @DisplayName("All housekeeping features can be enabled together")
    void testAllFeaturesEnabled() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "10");
        config.put("xa.leakDetection.enabled", "true");
        config.put("xa.leakDetection.timeoutMs", "300000");
        config.put("xa.maxLifetimeMs", "1800000");
        config.put("xa.idleBeforeRecycleMs", "300000");
        config.put("xa.diagnostics.enabled", "true");
        config.put("xa.diagnostics.intervalMs", "120000");

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        HousekeepingConfig housekeepingConfig = pooledDataSource.getHousekeepingConfig();
        assertTrue(housekeepingConfig.isLeakDetectionEnabled(), "Leak detection should be enabled");
        assertEquals(300000L, housekeepingConfig.getLeakTimeoutMs());
        assertEquals(1800000L, housekeepingConfig.getMaxLifetimeMs());
        assertEquals(300000L, housekeepingConfig.getIdleBeforeRecycleMs());
        assertTrue(housekeepingConfig.isDiagnosticsEnabled(), "Diagnostics should be enabled");
        assertEquals(120000L, housekeepingConfig.getDiagnosticsIntervalMs());

        // Verify pool works with all features
        XABackendSession session = pooledDataSource.borrowSession();
        assertNotNull(session, "Session should be borrowed successfully");

        pooledDataSource.returnSession(session);
    }

    @Test
    @DisplayName("Pool closes cleanly with diagnostics enabled")
    void testCloseWithDiagnostics() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("xa.maxPoolSize", "5");
        config.put("xa.diagnostics.enabled", "true");
        config.put("xa.diagnostics.intervalMs", "60000");

        pooledDataSource = new CommonsPool2XADataSource(mockXADataSource, config);

        // Borrow and return a session
        XABackendSession session = pooledDataSource.borrowSession();
        pooledDataSource.returnSession(session);

        // Close should work cleanly
        assertDoesNotThrow(() -> pooledDataSource.close(),
                "Pool should close cleanly with diagnostics enabled");

        pooledDataSource = null;  // Already closed
    }
}
