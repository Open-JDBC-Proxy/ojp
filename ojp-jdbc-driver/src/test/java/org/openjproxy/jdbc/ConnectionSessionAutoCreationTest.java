package org.openjproxy.jdbc;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.ParameterValue;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.TransactionInfo;
import com.openjproxy.grpc.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openjproxy.grpc.client.StatementService;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Test session auto-creation when callProxy detects missing session on server.
 * Tests cover:
 * 1. isValid() with missing session creates session and returns boolean
 * 2. getTransactionIsolation() with missing session creates session and returns value
 * 3. Concurrent requests hitting no-session path only create one session
 */
public class ConnectionSessionAutoCreationTest {

    private StatementService mockStatementService;
    private ConnectionDetails testConnectionDetails;
    private SessionInfo initialSession;
    private SessionInfo recreatedSession;

    @BeforeEach
    public void setUp() {
        mockStatementService = mock(StatementService.class);
        
        // Create test connection details
        testConnectionDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:ojp:postgresql://localhost:50051/testdb")
                .setUser("testuser")
                .setPassword("testpass")
                .setClientUUID("test-client-uuid-123")
                .build();
        
        // Create initial session info
        initialSession = SessionInfo.newBuilder()
                .setSessionUUID("initial-session-uuid")
                .setConnHash("test-conn-hash")
                .setClientUUID("test-client-uuid-123")
                .setTransactionInfo(TransactionInfo.newBuilder()
                        .setTransactionStatus(TransactionStatus.TRX_COMMITED)
                        .build())
                .build();
        
        // Create recreated session info (different UUID to verify recreation)
        recreatedSession = SessionInfo.newBuilder()
                .setSessionUUID("recreated-session-uuid")
                .setConnHash("test-conn-hash")
                .setClientUUID("test-client-uuid-123")
                .setTransactionInfo(TransactionInfo.newBuilder()
                        .setTransactionStatus(TransactionStatus.TRX_COMMITED)
                        .build())
                .build();
    }

    @Test
    public void testIsValidWithMissingSession_CreatesSessionAndReturnsBoolean() throws SQLException {
        // Setup: First call fails with "session not found", second call succeeds
        when(mockStatementService.callResource(any(CallResourceRequest.class)))
                .thenThrow(new SQLException("Session not found: initial-session-uuid"))
                .thenReturn(createCallResourceResponse(true));
        
        // Setup: Session creation call
        when(mockStatementService.connect(testConnectionDetails))
                .thenReturn(recreatedSession);
        
        // Create connection and set connection details
        Connection connection = new Connection(initialSession, mockStatementService, DbName.POSTGRES);
        connection.setConnectionDetails(testConnectionDetails);
        
        // Execute: Call isValid (should detect missing session, create new one, and retry)
        boolean result = connection.isValid(5);
        
        // Verify: Result is true
        assertTrue(result, "isValid should return true after session recreation");
        
        // Verify: connect was called once to create new session
        verify(mockStatementService, times(1)).connect(testConnectionDetails);
        
        // Verify: callResource was called twice (once failed, once succeeded)
        verify(mockStatementService, times(2)).callResource(any(CallResourceRequest.class));
        
        // Verify: Connection now has the new session
        assertEquals("recreated-session-uuid", connection.getSession().getSessionUUID());
    }

    @Test
    public void testGetTransactionIsolationWithMissingSession_CreatesSessionAndReturnsValue() throws SQLException {
        int expectedIsolationLevel = java.sql.Connection.TRANSACTION_READ_COMMITTED;
        
        // Setup: First call fails with "Connection not found for this sessionInfo", second call succeeds
        when(mockStatementService.callResource(any(CallResourceRequest.class)))
                .thenThrow(new SQLException("Connection not found for this sessionInfo"))
                .thenReturn(createCallResourceResponse(expectedIsolationLevel));
        
        // Setup: Session creation call
        when(mockStatementService.connect(testConnectionDetails))
                .thenReturn(recreatedSession);
        
        // Create connection and set connection details
        Connection connection = new Connection(initialSession, mockStatementService, DbName.POSTGRES);
        connection.setConnectionDetails(testConnectionDetails);
        
        // Execute: Call getTransactionIsolation (should detect missing session, create new one, and retry)
        int result = connection.getTransactionIsolation();
        
        // Verify: Result is the expected isolation level
        assertEquals(expectedIsolationLevel, result, "getTransactionIsolation should return correct value after session recreation");
        
        // Verify: connect was called once to create new session
        verify(mockStatementService, times(1)).connect(testConnectionDetails);
        
        // Verify: callResource was called twice (once failed, once succeeded)
        verify(mockStatementService, times(2)).callResource(any(CallResourceRequest.class));
        
        // Verify: Connection now has the new session
        assertEquals("recreated-session-uuid", connection.getSession().getSessionUUID());
    }

    @Test
    public void testConcurrentRequestsWithMissingSession_OnlyCreatesOneSession() throws Exception {
        int numThreads = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(numThreads);
        ExecutorService executorService = Executors.newFixedThreadPool(numThreads);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<Exception> firstException = new AtomicReference<>();
        
        // Setup: All initial calls fail with session not found
        when(mockStatementService.callResource(any(CallResourceRequest.class)))
                .thenThrow(new SQLException("Session not found: initial-session-uuid"))
                .thenReturn(createCallResourceResponse(true));
        
        // Setup: Session creation call (add small delay to increase chance of concurrent access)
        when(mockStatementService.connect(testConnectionDetails))
                .thenAnswer(invocation -> {
                    Thread.sleep(10); // Small delay to simulate network call
                    return recreatedSession;
                });
        
        // Create connection and set connection details
        Connection connection = new Connection(initialSession, mockStatementService, DbName.POSTGRES);
        connection.setConnectionDetails(testConnectionDetails);
        
        // Execute: Launch multiple threads that all call isValid concurrently
        for (int i = 0; i < numThreads; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    boolean result = connection.isValid(5);
                    if (result) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    firstException.compareAndSet(null, e);
                } finally {
                    finishLatch.countDown();
                }
            });
        }
        
        // Start all threads at the same time
        startLatch.countDown();
        
        // Wait for all threads to complete
        finishLatch.await();
        executorService.shutdown();
        
        // Verify: No exceptions occurred
        assertNull(firstException.get(), "No exceptions should occur during concurrent access");
        
        // Verify: All threads succeeded
        assertEquals(numThreads, successCount.get(), "All threads should successfully call isValid");
        
        // Verify: connect was called only once despite concurrent access
        verify(mockStatementService, times(1)).connect(testConnectionDetails);
        
        // Verify: Connection has the recreated session
        assertEquals("recreated-session-uuid", connection.getSession().getSessionUUID());
    }

    @Test
    public void testNonSessionErrorsArePropagated() throws SQLException {
        // Setup: Call fails with a different error (not session-related)
        when(mockStatementService.callResource(any(CallResourceRequest.class)))
                .thenThrow(new SQLException("Database connection timeout", "08001", 1234));
        
        // Create connection and set connection details
        Connection connection = new Connection(initialSession, mockStatementService, DbName.POSTGRES);
        connection.setConnectionDetails(testConnectionDetails);
        
        // Execute & Verify: Exception should be propagated without retry
        SQLException exception = assertThrows(SQLException.class, () -> connection.isValid(5));
        assertEquals("Database connection timeout", exception.getMessage());
        
        // Verify: connect was never called (no retry for non-session errors)
        verify(mockStatementService, never()).connect(any());
        
        // Verify: callResource was called only once (no retry)
        verify(mockStatementService, times(1)).callResource(any(CallResourceRequest.class));
    }

    @Test
    public void testMissingConnectionDetails_ThrowsException() throws SQLException {
        // Setup: Call fails with session not found
        when(mockStatementService.callResource(any(CallResourceRequest.class)))
                .thenThrow(new SQLException("Session not found: initial-session-uuid"));
        
        // Create connection WITHOUT setting connection details
        Connection connection = new Connection(initialSession, mockStatementService, DbName.POSTGRES);
        // Note: NOT calling connection.setConnectionDetails()
        
        // Execute & Verify: Should throw exception about missing connection details
        SQLException exception = assertThrows(SQLException.class, () -> connection.isValid(5));
        assertTrue(exception.getMessage().contains("Cannot recreate session: connection details not available"),
                "Exception should mention missing connection details");
        
        // Verify: connect was never called
        verify(mockStatementService, never()).connect(any());
    }

    @Test
    public void testSessionRecreationFailure_ThrowsException() throws SQLException {
        // Setup: callResource fails, then connect fails
        when(mockStatementService.callResource(any(CallResourceRequest.class)))
                .thenThrow(new SQLException("Session not found: initial-session-uuid"));
        
        when(mockStatementService.connect(testConnectionDetails))
                .thenThrow(new SQLException("Authentication failed", "28000", 5678));
        
        // Create connection and set connection details
        Connection connection = new Connection(initialSession, mockStatementService, DbName.POSTGRES);
        connection.setConnectionDetails(testConnectionDetails);
        
        // Execute & Verify: Should throw exception about failed session recreation
        SQLException exception = assertThrows(SQLException.class, () -> connection.isValid(5));
        assertTrue(exception.getMessage().contains("Failed to recreate session on server"),
                "Exception should mention failed session recreation");
        
        // Verify: connect was called
        verify(mockStatementService, times(1)).connect(testConnectionDetails);
    }

    @Test
    public void testGetCatalogWithMissingSession_CreatesSessionAndReturnsValue() throws SQLException {
        String expectedCatalog = "testdb";
        
        // Setup: First call fails with "no active session", second call succeeds
        when(mockStatementService.callResource(any(CallResourceRequest.class)))
                .thenThrow(new SQLException("No active session"))
                .thenReturn(createCallResourceResponse(expectedCatalog));
        
        // Setup: Session creation call
        when(mockStatementService.connect(testConnectionDetails))
                .thenReturn(recreatedSession);
        
        // Create connection and set connection details
        Connection connection = new Connection(initialSession, mockStatementService, DbName.POSTGRES);
        connection.setConnectionDetails(testConnectionDetails);
        
        // Execute: Call getCatalog (should detect missing session, create new one, and retry)
        String result = connection.getCatalog();
        
        // Verify: Result is the expected catalog
        assertEquals(expectedCatalog, result, "getCatalog should return correct value after session recreation");
        
        // Verify: connect was called once to create new session
        verify(mockStatementService, times(1)).connect(testConnectionDetails);
        
        // Verify: Connection now has the new session
        assertEquals("recreated-session-uuid", connection.getSession().getSessionUUID());
    }

    /**
     * Helper method to create a CallResourceResponse with a boolean value
     */
    private CallResourceResponse createCallResourceResponse(boolean value) {
        return CallResourceResponse.newBuilder()
                .setSession(recreatedSession)
                .addValues(ParameterValue.newBuilder().setBoolValue(value).build())
                .build();
    }

    /**
     * Helper method to create a CallResourceResponse with an integer value
     */
    private CallResourceResponse createCallResourceResponse(int value) {
        return CallResourceResponse.newBuilder()
                .setSession(recreatedSession)
                .addValues(ParameterValue.newBuilder().setIntValue(value).build())
                .build();
    }

    /**
     * Helper method to create a CallResourceResponse with a string value
     */
    private CallResourceResponse createCallResourceResponse(String value) {
        return CallResourceResponse.newBuilder()
                .setSession(recreatedSession)
                .addValues(ParameterValue.newBuilder().setStringValue(value).build())
                .build();
    }
}
