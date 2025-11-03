package org.openjproxy.grpc.client;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for selective error handling in MultinodeConnectionManager.
 * Only connection-level errors should mark servers unhealthy.
 */
class MultinodeConnectionManagerErrorHandlingTest {

    @Test
    void testConnectionLevelErrorsMarkUnhealthy() throws Exception {
        // Test that UNAVAILABLE marks server unhealthy
        ServerEndpoint endpoint1 = new ServerEndpoint("localhost", 10591);
        assertTrue(endpoint1.isHealthy());
        
        StatusRuntimeException unavailableEx = 
                new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Connection refused"));
        
        boolean shouldMark = invokeIsConnectionLevelError(unavailableEx);
        assertTrue(shouldMark, "UNAVAILABLE should mark server unhealthy");
    }

    @Test
    void testTimeoutErrorsMarkUnhealthy() throws Exception {
        StatusRuntimeException timeoutEx = 
                new StatusRuntimeException(Status.DEADLINE_EXCEEDED.withDescription("Request timeout"));
        
        boolean shouldMark = invokeIsConnectionLevelError(timeoutEx);
        assertTrue(shouldMark, "DEADLINE_EXCEEDED should mark server unhealthy");
    }

    @Test
    void testCancelledErrorsMarkUnhealthy() throws Exception {
        StatusRuntimeException cancelledEx = 
                new StatusRuntimeException(Status.CANCELLED.withDescription("Connection cancelled"));
        
        boolean shouldMark = invokeIsConnectionLevelError(cancelledEx);
        assertTrue(shouldMark, "CANCELLED should mark server unhealthy");
    }

    @Test
    void testDatabaseErrorsDoNotMarkUnhealthy() throws Exception {
        // INVALID_ARGUMENT typically means SQL syntax error or table not found
        StatusRuntimeException invalidArgEx = 
                new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Table does not exist"));
        
        boolean shouldMark = invokeIsConnectionLevelError(invalidArgEx);
        assertFalse(shouldMark, "INVALID_ARGUMENT (database error) should not mark server unhealthy");
    }

    @Test
    void testPermissionDeniedDoesNotMarkUnhealthy() throws Exception {
        // PERMISSION_DENIED is a database-level error
        StatusRuntimeException permissionEx = 
                new StatusRuntimeException(Status.PERMISSION_DENIED.withDescription("Access denied for table"));
        
        boolean shouldMark = invokeIsConnectionLevelError(permissionEx);
        assertFalse(shouldMark, "PERMISSION_DENIED (database error) should not mark server unhealthy");
    }

    @Test
    void testNotFoundDoesNotMarkUnhealthy() throws Exception {
        // NOT_FOUND typically means resource doesn't exist
        StatusRuntimeException notFoundEx = 
                new StatusRuntimeException(Status.NOT_FOUND.withDescription("Resource not found"));
        
        boolean shouldMark = invokeIsConnectionLevelError(notFoundEx);
        assertFalse(shouldMark, "NOT_FOUND (database error) should not mark server unhealthy");
    }

    @Test
    void testUnknownWithConnectionMessageMarksUnhealthy() throws Exception {
        // UNKNOWN with connection-related message should mark unhealthy
        StatusRuntimeException unknownEx = 
                new StatusRuntimeException(Status.UNKNOWN.withDescription("Connection reset by peer"));
        
        boolean shouldMark = invokeIsConnectionLevelError(unknownEx);
        assertTrue(shouldMark, "UNKNOWN with 'connection' in message should mark server unhealthy");
    }

    @Test
    void testUnknownWithoutConnectionMessageDoesNotMarkUnhealthy() throws Exception {
        // UNKNOWN without connection-related message should not mark unhealthy
        StatusRuntimeException unknownEx = 
                new StatusRuntimeException(Status.UNKNOWN.withDescription("Unknown database error"));
        
        boolean shouldMark = invokeIsConnectionLevelError(unknownEx);
        assertFalse(shouldMark, "UNKNOWN without connection keywords should not mark server unhealthy");
    }

    @Test
    void testNonGrpcExceptionWithConnectionKeyword() throws Exception {
        Exception connectionEx = new Exception("Connection timed out");
        
        boolean shouldMark = invokeIsConnectionLevelError(connectionEx);
        assertTrue(shouldMark, "Exception with 'connection' keyword should mark server unhealthy");
    }

    @Test
    void testNonGrpcExceptionWithTimeoutKeyword() throws Exception {
        Exception timeoutEx = new Exception("Request timeout occurred");
        
        boolean shouldMark = invokeIsConnectionLevelError(timeoutEx);
        assertTrue(shouldMark, "Exception with 'timeout' keyword should mark server unhealthy");
    }

    @Test
    void testNonGrpcExceptionWithUnavailableKeyword() throws Exception {
        Exception unavailableEx = new Exception("Service unavailable");
        
        boolean shouldMark = invokeIsConnectionLevelError(unavailableEx);
        assertTrue(shouldMark, "Exception with 'unavailable' keyword should mark server unhealthy");
    }

    @Test
    void testNonGrpcExceptionWithoutConnectionKeywords() throws Exception {
        Exception dbEx = new Exception("Syntax error in SQL statement");
        
        boolean shouldMark = invokeIsConnectionLevelError(dbEx);
        assertFalse(shouldMark, "Exception without connection keywords should not mark server unhealthy");
    }

    @Test
    void testExceptionWithNullMessage() throws Exception {
        Exception nullEx = new Exception((String) null);
        
        boolean shouldMark = invokeIsConnectionLevelError(nullEx);
        assertFalse(shouldMark, "Exception with null message should not mark server unhealthy");
    }

    /**
     * Helper method to invoke the private isConnectionLevelError method via reflection.
     */
    private boolean invokeIsConnectionLevelError(Exception exception) throws Exception {
        List<ServerEndpoint> endpoints = Arrays.asList(new ServerEndpoint("localhost", 10591));
        MultinodeConnectionManager manager = new MultinodeConnectionManager(endpoints);
        
        Method method = MultinodeConnectionManager.class.getDeclaredMethod("isConnectionLevelError", Exception.class);
        method.setAccessible(true);
        
        return (boolean) method.invoke(manager, exception);
    }
}
