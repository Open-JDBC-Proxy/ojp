package org.openjproxy.grpc.server;

import com.openjproxy.grpc.SessionInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Background task that periodically cleans up abandoned sessions.
 * A session is considered abandoned if it has been inactive (no operations)
 * for longer than the configured timeout period.
 *
 * <p>This task helps prevent memory leaks and resource exhaustion when clients
 * disconnect without properly calling terminateSession().</p>
 *
 * <p>The cleanup process:
 * <ol>
 *   <li>Identifies sessions inactive beyond timeout threshold</li>
 *   <li>Terminates each abandoned session (releasing connections and resources)</li>
 *   <li>Logs cleanup actions for monitoring</li>
 * </ol>
 */
@Slf4j
public class SessionCleanupTask implements Runnable {

    private final SessionManager sessionManager;
    private final long sessionTimeoutMillis;

    /**
     * Creates a new session cleanup task.
     *
     * @param sessionManager the session manager to clean up
     * @param sessionTimeoutMillis the inactivity timeout in milliseconds
     */
    public SessionCleanupTask(SessionManager sessionManager, long sessionTimeoutMillis) {
        this.sessionManager = sessionManager;
        this.sessionTimeoutMillis = sessionTimeoutMillis;
    }

    @Override
    public void run() {
        try {
            log.debug("Starting session cleanup task (timeout: {}ms)", sessionTimeoutMillis);

            Collection<Session> allSessions = sessionManager.getAllSessions();
            List<Session> inactiveSessions = new ArrayList<>();

            // Identify inactive sessions
            for (Session session : allSessions) {
                if (session.isInactive(sessionTimeoutMillis)) {
                    inactiveSessions.add(session);
                }
            }

            if (inactiveSessions.isEmpty()) {
                log.debug("No inactive sessions found (total sessions: {})", allSessions.size());
                return;
            }

            log.info("Found {} inactive sessions out of {} total sessions",
                    inactiveSessions.size(), allSessions.size());

            // Terminate each inactive session
            for (Session session : inactiveSessions) {
                try {
                    long inactiveDuration = session.getInactiveDuration();
                    log.info("Cleaning up abandoned session: sessionUUID={}, clientUUID={}, " +
                            "inactiveDuration={}ms, threshold={}ms, isXA={}",
                            session.getSessionUUID(),
                            session.getClientUUID(),
                            inactiveDuration,
                            sessionTimeoutMillis,
                            session.isXA());

                    SessionInfo sessionInfo = session.getSessionInfo();
                    if (sessionInfo != null) {
                        sessionManager.terminateSession(sessionInfo);
                        log.info("Successfully terminated abandoned session: {}", session.getSessionUUID());
                    } else {
                        log.warn("Could not terminate session {} - sessionInfo is null", session.getSessionUUID());
                    }
                } catch (Exception e) {
                    log.error("Error terminating abandoned session: {}", session.getSessionUUID(), e);
                }
            }

            log.info("Session cleanup completed: {} sessions terminated", inactiveSessions.size());

        } catch (Exception e) {
            log.error("Unexpected error during session cleanup", e);
        }
    }
}
