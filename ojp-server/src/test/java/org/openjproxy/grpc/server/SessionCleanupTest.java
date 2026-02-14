package org.openjproxy.grpc.server;

import com.openjproxy.grpc.SessionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Tests for session activity tracking and cleanup functionality.
 */
class SessionCleanupTest {

    private static final long TEST_TIMEOUT_MS = 100; // Timeout for test sessions
    private static final long TEST_WAIT_MS = 150; // Wait time to exceed timeout

    private SessionManager sessionManager;
    private Connection mockConnection;

    @BeforeEach
    void setUp() {
        sessionManager = new SessionManagerImpl();
        mockConnection = mock(Connection.class);
    }

    @Test
    void testSessionActivityTracking() throws InterruptedException {
        // Register client UUID first
        sessionManager.registerClientUUID("conn-hash-123", "client-123");
        
        // Create a session
        SessionInfo sessionInfo = sessionManager.createSession("client-123", mockConnection);
        Session session = sessionManager.getSession(sessionInfo);

        // Verify initial timestamps
        assertNotNull(session.getCreationTime());
        assertNotNull(session.getLastActivityTime());
        assertEquals(session.getCreationTime(), session.getLastActivityTime());

        long initialActivityTime = session.getLastActivityTime();

        // Wait a bit and update activity
        Thread.sleep(100); //NOSONAR
        session.updateActivity();

        // Verify activity time was updated
        assertTrue(session.getLastActivityTime() > initialActivityTime);
    }

    @Test
    void testInactivityDetection() throws InterruptedException {
        // Register client UUID first
        sessionManager.registerClientUUID("conn-hash-456", "client-456");
        
        // Create a session
        SessionInfo sessionInfo = sessionManager.createSession("client-456", mockConnection);
        Session session = sessionManager.getSession(sessionInfo);

        // Session should not be inactive immediately
        assertFalse(session.isInactive(1000)); // 1 second timeout

        // Wait and check again
        Thread.sleep(TEST_WAIT_MS); //NOSONAR
        assertTrue(session.isInactive(TEST_TIMEOUT_MS)); // 100ms timeout
        assertFalse(session.isInactive(1000)); // 1 second timeout
    }

    @Test
    void testInactiveDuration() throws InterruptedException {
        // Register client UUID first
        sessionManager.registerClientUUID("conn-hash-789", "client-789");
        
        // Create a session
        SessionInfo sessionInfo = sessionManager.createSession("client-789", mockConnection);
        Session session = sessionManager.getSession(sessionInfo);

        // Check inactive duration increases over time
        long duration1 = session.getInactiveDuration();
        assertTrue(duration1 >= 0);

        Thread.sleep(100); //NOSONAR
        long duration2 = session.getInactiveDuration();
        assertTrue(duration2 > duration1);

        // Update activity and verify duration resets
        session.updateActivity();
        long duration3 = session.getInactiveDuration();
        assertTrue(duration3 < duration2);
    }

    @Test
    void testSessionManagerUpdateActivity() {
        // Register client UUID first
        sessionManager.registerClientUUID("conn-hash-abc", "client-abc");
        
        // Create a session
        SessionInfo sessionInfo = sessionManager.createSession("client-abc", mockConnection);
        Session session = sessionManager.getSession(sessionInfo);

        long initialActivityTime = session.getLastActivityTime();

        // Update activity through SessionManager
        sessionManager.updateSessionActivity(sessionInfo);

        // Verify activity was updated
        assertTrue(session.getLastActivityTime() >= initialActivityTime);
    }

    @Test
    void testSessionManagerGetAllSessions() {
        // Register client UUIDs first
        sessionManager.registerClientUUID("conn-hash-1", "client-1");
        sessionManager.registerClientUUID("conn-hash-2", "client-2");
        sessionManager.registerClientUUID("conn-hash-3", "client-3");
        
        // Create multiple sessions
        SessionInfo session1 = sessionManager.createSession("client-1", mockConnection);
        SessionInfo session2 = sessionManager.createSession("client-2", mockConnection);
        SessionInfo session3 = sessionManager.createSession("client-3", mockConnection);

        // Get all sessions
        Collection<Session> allSessions = sessionManager.getAllSessions();

        // Verify count
        assertEquals(3, allSessions.size());

        // Verify sessions are present
        List<String> sessionUUIDs = new ArrayList<>();
        for (Session session : allSessions) {
            sessionUUIDs.add(session.getSessionUUID());
        }
        assertTrue(sessionUUIDs.contains(session1.getSessionUUID()));
        assertTrue(sessionUUIDs.contains(session2.getSessionUUID()));
        assertTrue(sessionUUIDs.contains(session3.getSessionUUID()));
    }

    @Test
    void testSessionCleanupTaskIdentifiesInactiveSessions() throws Exception {
        // Register client UUIDs first
        sessionManager.registerClientUUID("conn-hash-active", "active-client");
        sessionManager.registerClientUUID("conn-hash-inactive", "inactive-client");
        
        // Create sessions with different activity times
        SessionInfo activeSession = sessionManager.createSession("active-client", mockConnection);
        SessionInfo inactiveSession = sessionManager.createSession("inactive-client", mockConnection);

        // Make one session inactive by not updating its activity
        Thread.sleep(TEST_WAIT_MS); //NOSONAR

        // Update activity on the active session only
        sessionManager.updateSessionActivity(activeSession);

        // Create cleanup task with 100ms timeout
        SessionCleanupTask cleanupTask = new SessionCleanupTask(sessionManager, TEST_TIMEOUT_MS);

        // Run cleanup
        cleanupTask.run();

        // Active session should still exist
        assertNotNull(sessionManager.getSession(activeSession));

        // Inactive session should have been cleaned up
        assertNull(sessionManager.getSession(inactiveSession));
    }

    @Test
    void testSessionCleanupTaskDoesNotCleanupActiveSessions() throws Exception {
        // Register client UUID first
        sessionManager.registerClientUUID("conn-hash-active2", "active-client");
        
        // Create a session
        SessionInfo sessionInfo = sessionManager.createSession("active-client", mockConnection);

        // Keep updating activity
        for (int i = 0; i < 5; i++) {
            Thread.sleep(50); //NOSONAR
            sessionManager.updateSessionActivity(sessionInfo);
        }

        // Create cleanup task with 100ms timeout
        SessionCleanupTask cleanupTask = new SessionCleanupTask(sessionManager, TEST_TIMEOUT_MS);

        // Run cleanup
        cleanupTask.run();

        // Session should still exist since it's been active
        assertNotNull(sessionManager.getSession(sessionInfo));
    }

    @Test
    void testUpdateActivityOnNullSession() {
        // Should not throw exception with null or invalid session info
        SessionInfo nullSession = null;
        assertDoesNotThrow(() -> sessionManager.updateSessionActivity(nullSession));

        SessionInfo invalidSession = SessionInfo.newBuilder()
                .setSessionUUID("nonexistent-uuid")
                .build();
        assertDoesNotThrow(() -> sessionManager.updateSessionActivity(invalidSession));
    }

    @Test
    void testMultipleSessionsCleanup() throws Exception {
        // Create multiple sessions
        List<SessionInfo> sessions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            // Register client UUID first
            sessionManager.registerClientUUID("conn-hash-" + i, "client-" + i);
            SessionInfo session = sessionManager.createSession("client-" + i, mockConnection);
            sessions.add(session);
        }

        // Wait for all to become inactive
        Thread.sleep(TEST_WAIT_MS); //NOSONAR

        // Create cleanup task with 100ms timeout
        SessionCleanupTask cleanupTask = new SessionCleanupTask(sessionManager, TEST_TIMEOUT_MS);

        // Run cleanup
        cleanupTask.run();

        // All sessions should be cleaned up
        for (SessionInfo session : sessions) {
            assertNull(sessionManager.getSession(session));
        }

        // Verify no sessions remain
        assertEquals(0, sessionManager.getAllSessions().size());
    }
}
