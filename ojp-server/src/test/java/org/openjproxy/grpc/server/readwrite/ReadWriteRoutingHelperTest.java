package org.openjproxy.grpc.server.readwrite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.server.Session;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ReadWriteRoutingHelper}.
 */
class ReadWriteRoutingHelperTest {

    private ReadWriteDataSourceRegistry registry;
    private SqlClassifier sqlClassifier;
    private ReadWriteRoutingHelper helper;
    private DataSource primaryDataSource;
    private DataSource replica1;
    private DataSource replica2;

    @BeforeEach
    void setUp() {
        registry = mock(ReadWriteDataSourceRegistry.class);
        sqlClassifier = new RegexSqlClassifier();
        helper = new ReadWriteRoutingHelper(registry, sqlClassifier);
        
        primaryDataSource = mock(DataSource.class);
        replica1 = mock(DataSource.class);
        replica2 = mock(DataSource.class);
    }

    @Test
    void testRouteQuery_NoReadWriteConfig_UsesPrimary() throws SQLException {
        // Arrange
        Session session = createTestSession();
        when(registry.getPrimaryDatasourceName(session.getConnHash())).thenReturn(null);
        Connection mockConn = mock(Connection.class);
        when(primaryDataSource.getConnection()).thenReturn(mockConn);

        // Act
        Connection result = helper.routeQuery(primaryDataSource, session, "SELECT * FROM users");

        // Assert
        assertSame(mockConn, result);
        verify(primaryDataSource).getConnection();
    }

    @Test
    void testRouteQuery_SplittingDisabled_UsesPrimary() throws SQLException {
        // Arrange
        Session session = createTestSession();
        ReadWriteConfiguration config = ReadWriteConfiguration.builder()
                .primaryDatasourceName("primary")
                .enabled(false)
                .build();
        
        when(registry.getPrimaryDatasourceName(session.getConnHash())).thenReturn("primary");
        when(registry.getConfiguration("primary")).thenReturn(config);
        Connection mockConn = mock(Connection.class);
        when(primaryDataSource.getConnection()).thenReturn(mockConn);

        // Act
        Connection result = helper.routeQuery(primaryDataSource, session, "SELECT * FROM users");

        // Assert
        assertSame(mockConn, result);
        verify(primaryDataSource).getConnection();
    }

    @Test
    void testRouteQuery_NoReplicas_UsesPrimary() throws SQLException {
        // Arrange
        Session session = createTestSession();
        ReadWriteConfiguration config = ReadWriteConfiguration.builder()
                .primaryDatasourceName("primary")
                .enabled(true)
                .build();
        
        when(registry.getPrimaryDatasourceName(session.getConnHash())).thenReturn("primary");
        when(registry.getConfiguration("primary")).thenReturn(config);
        when(registry.getReplicas("primary")).thenReturn(Arrays.asList()); // Empty list
        Connection mockConn = mock(Connection.class);
        when(primaryDataSource.getConnection()).thenReturn(mockConn);

        // Act
        Connection result = helper.routeQuery(primaryDataSource, session, "SELECT * FROM users");

        // Assert
        assertSame(mockConn, result);
        verify(primaryDataSource).getConnection();
    }

    @Test
    void testIsReadWriteSplittingEnabled_NotConfigured() {
        // Arrange
        Session session = createTestSession();
        when(registry.getPrimaryDatasourceName(session.getConnHash())).thenReturn(null);

        // Act
        boolean result = helper.isReadWriteSplittingEnabled(session);

        // Assert
        assertFalse(result);
    }

    @Test
    void testIsReadWriteSplittingEnabled_ConfiguredAndEnabled() {
        // Arrange
        Session session = createTestSession();
        ReadWriteConfiguration config = ReadWriteConfiguration.builder()
                .primaryDatasourceName("primary")
                .enabled(true)
                .build();
        
        when(registry.getPrimaryDatasourceName(session.getConnHash())).thenReturn("primary");
        when(registry.getConfiguration("primary")).thenReturn(config);

        // Act
        boolean result = helper.isReadWriteSplittingEnabled(session);

        // Assert
        assertTrue(result);
    }

    @Test
    void testIsReadWriteSplittingEnabled_ConfiguredButDisabled() {
        // Arrange
        Session session = createTestSession();
        ReadWriteConfiguration config = ReadWriteConfiguration.builder()
                .primaryDatasourceName("primary")
                .enabled(false)
                .build();
        
        when(registry.getPrimaryDatasourceName(session.getConnHash())).thenReturn("primary");
        when(registry.getConfiguration("primary")).thenReturn(config);

        // Act
        boolean result = helper.isReadWriteSplittingEnabled(session);

        // Assert
        assertFalse(result);
    }

    @Test
    void testRecordWriteOperation() {
        // Arrange
        Session session = createTestSession();
        long beforeTime = System.currentTimeMillis();

        // Act
        helper.recordWriteOperation(session);

        // Assert
        long afterTime = System.currentTimeMillis();
        assertTrue(session.getLastWriteTimestamp() >= beforeTime);
        assertTrue(session.getLastWriteTimestamp() <= afterTime);
    }

    @Test
    void testUpdateTransactionState() {
        // Arrange
        Session session = createTestSession();
        assertFalse(session.isInTransaction());

        // Act
        helper.updateTransactionState(session, true);

        // Assert
        assertTrue(session.isInTransaction());

        // Act again
        helper.updateTransactionState(session, false);

        // Assert
        assertFalse(session.isInTransaction());
    }

    private Session createTestSession() {
        Session session = new Session("test-client-uuid");
        session.setConnHash("test-conn-hash");
        session.setSessionUUID("test-session-uuid");
        return session;
    }
}
