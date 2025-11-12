package org.openjproxy.grpc.server.xa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DecoratingDataSource and XAResourceConnection.
 * Tests the wrapper that enables HikariCP pooling of XA connections.
 */
class DecoratingDataSourceTest {
    
    private XADataSource mockXADataSource;
    private XAConnection mockXAConnection;
    private Connection mockConnection;
    private XAResource mockXAResource;
    private DataSource mockDataSource;
    
    @BeforeEach
    void setUp() throws SQLException {
        // Create mocks
        mockXADataSource = mock(XADataSource.class);
        mockXAConnection = mock(XAConnection.class);
        mockConnection = mock(Connection.class);
        mockXAResource = mock(XAResource.class);
        mockDataSource = mock(DataSource.class);
        
        // Configure mock behavior
        when(mockXADataSource.getXAConnection()).thenReturn(mockXAConnection);
        when(mockXADataSource.getXAConnection(anyString(), anyString())).thenReturn(mockXAConnection);
        when(mockXAConnection.getConnection()).thenReturn(mockConnection);
        when(mockXAConnection.getXAResource()).thenReturn(mockXAResource);
        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockDataSource.getConnection(anyString(), anyString())).thenReturn(mockConnection);
    }
    
    @Test
    void testNonXADataSource() throws SQLException {
        // Create DecoratingDataSource without XADataSource
        DecoratingDataSource ds = new DecoratingDataSource(mockDataSource);
        
        // Get connection - should delegate to mockDataSource
        Connection conn = ds.getConnection();
        
        assertNotNull(conn);
        verify(mockDataSource).getConnection();
        verifyNoInteractions(mockXADataSource);
    }
    
    @Test
    void testNonXADataSourceWithCredentials() throws SQLException {
        // Create DecoratingDataSource without XADataSource
        DecoratingDataSource ds = new DecoratingDataSource(mockDataSource);
        
        // Get connection with credentials - should delegate to mockDataSource
        Connection conn = ds.getConnection("user", "password");
        
        assertNotNull(conn);
        verify(mockDataSource).getConnection("user", "password");
        verifyNoInteractions(mockXADataSource);
    }
    
    @Test
    void testXADataSourceGetConnection() throws SQLException {
        // Create DecoratingDataSource with XADataSource
        DecoratingDataSource ds = new DecoratingDataSource(mockDataSource, mockXADataSource);
        
        // Get connection - should use XADataSource and wrap as XAResourceConnection
        Connection conn = ds.getConnection();
        
        assertNotNull(conn);
        verify(mockXADataSource).getXAConnection();
        verify(mockXAConnection).getConnection();
        verify(mockXAConnection).getXAResource();
        
        // Verify the connection is a proxy that implements multiple interfaces
        assertTrue(conn instanceof Connection, "Should be a Connection");
        assertTrue(conn instanceof XAConnection, "Should be an XAConnection");
        assertTrue(conn instanceof XAResource, "Should be an XAResource");
    }
    
    @Test
    void testXADataSourceGetConnectionWithCredentials() throws SQLException {
        // Create DecoratingDataSource with XADataSource
        DecoratingDataSource ds = new DecoratingDataSource(mockDataSource, mockXADataSource);
        
        // Get connection with credentials
        Connection conn = ds.getConnection("user", "password");
        
        assertNotNull(conn);
        verify(mockXADataSource).getXAConnection("user", "password");
        verify(mockXAConnection).getConnection();
        verify(mockXAConnection).getXAResource();
        
        // Verify the connection is a proxy that implements multiple interfaces
        assertTrue(conn instanceof Connection, "Should be a Connection");
        assertTrue(conn instanceof XAConnection, "Should be an XAConnection");
        assertTrue(conn instanceof XAResource, "Should be an XAResource");
    }
    
    @Test
    void testXAResourceConnectionProxyBehavior() throws Exception {
        // Create DecoratingDataSource with XADataSource
        DecoratingDataSource ds = new DecoratingDataSource(mockDataSource, mockXADataSource);
        
        // Get connection (which is actually an XAResourceConnection proxy)
        Connection conn = ds.getConnection();
        
        assertNotNull(conn);
        
        // Test Connection methods - should delegate to underlying connection
        conn.setAutoCommit(false);
        verify(mockConnection).setAutoCommit(false);
        
        // Test XAConnection methods - should delegate to underlying XAConnection
        XAConnection xaConn = (XAConnection) conn;
        Connection innerConn = xaConn.getConnection();
        assertNotNull(innerConn);
        // getConnection() should return the proxy itself
        assertSame(conn, innerConn, "getConnection() should return the proxy itself");
        
        // Test XAResource methods - should delegate to underlying XAResource
        XAResource xaRes = (XAResource) conn;
        
        // Mock a Xid for testing
        Xid mockXid = mock(Xid.class);
        xaRes.start(mockXid, XAResource.TMNOFLAGS);
        verify(mockXAResource).start(mockXid, XAResource.TMNOFLAGS);
        
        xaRes.end(mockXid, XAResource.TMSUCCESS);
        verify(mockXAResource).end(mockXid, XAResource.TMSUCCESS);
        
        when(mockXAResource.prepare(mockXid)).thenReturn(XAResource.XA_OK);
        int result = xaRes.prepare(mockXid);
        assertEquals(XAResource.XA_OK, result);
        verify(mockXAResource).prepare(mockXid);
        
        xaRes.commit(mockXid, false);
        verify(mockXAResource).commit(mockXid, false);
    }
    
    @Test
    void testXAResourceConnectionGetXAResource() throws SQLException {
        // Create DecoratingDataSource with XADataSource
        DecoratingDataSource ds = new DecoratingDataSource(mockDataSource, mockXADataSource);
        
        // Get connection
        Connection conn = ds.getConnection();
        XAConnection xaConn = (XAConnection) conn;
        
        // Call getXAResource() - should return the proxy itself (which implements XAResource)
        XAResource xaRes = xaConn.getXAResource();
        
        assertNotNull(xaRes);
        assertSame(conn, xaRes, "getXAResource() should return the proxy itself");
    }
    
    @Test
    void testDecoratingDataSourceDelegatesMethods() throws SQLException {
        DecoratingDataSource ds = new DecoratingDataSource(mockDataSource);
        
        // Test delegate methods
        when(mockDataSource.getLoginTimeout()).thenReturn(30);
        assertEquals(30, ds.getLoginTimeout());
        verify(mockDataSource).getLoginTimeout();
        
        ds.setLoginTimeout(60);
        verify(mockDataSource).setLoginTimeout(60);
    }
    
    @Test
    void testDecoratingDataSourceUnwrap() throws SQLException {
        DecoratingDataSource ds = new DecoratingDataSource(mockDataSource);
        
        // Should be able to unwrap to DecoratingDataSource
        assertTrue(ds.isWrapperFor(DecoratingDataSource.class));
        DecoratingDataSource unwrapped = ds.unwrap(DecoratingDataSource.class);
        assertEquals(ds, unwrapped);
    }
}
