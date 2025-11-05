package org.openjproxy.grpc.server.utils;

import com.openjproxy.grpc.SessionInfo;

/**
 * Utility class for creating SessionInfo builders.
 * Extracted from StatementServiceImpl to improve modularity.
 */
public class SessionInfoUtils {

    /**
     * Creates a new SessionInfo builder from an existing SessionInfo.
     *
     * @param activeSessionInfo The source session info
     * @return A new builder with copied values
     */
    public static SessionInfo.Builder newBuilderFrom(SessionInfo activeSessionInfo) {
        return SessionInfo.newBuilder()
                .setConnHash(activeSessionInfo.getConnHash())
                .setClientUUID(activeSessionInfo.getClientUUID())
                .setSessionUUID(activeSessionInfo.getSessionUUID())
                .setSessionStatus(activeSessionInfo.getSessionStatus())
                .setTransactionInfo(activeSessionInfo.getTransactionInfo())
                .setIsXA(activeSessionInfo.getIsXA())
                .setTargetServer(activeSessionInfo.getTargetServer());
    }
    
    /**
     * Adds targetServer to an existing SessionInfo.
     * If targetServer is already set, it is preserved. Otherwise, the provided targetServer is set.
     *
     * @param sessionInfo The source session info
     * @param targetServer The target server to set if not already present
     * @return A new SessionInfo with targetServer set
     */
    public static SessionInfo withTargetServer(SessionInfo sessionInfo, String targetServer) {
        if (sessionInfo == null) {
            return null;
        }
        
        // If targetServer is already set, preserve it; otherwise use the provided one
        String effectiveTargetServer = sessionInfo.getTargetServer();
        if (effectiveTargetServer == null || effectiveTargetServer.isEmpty()) {
            effectiveTargetServer = targetServer;
        }
        
        return SessionInfo.newBuilder()
                .setConnHash(sessionInfo.getConnHash())
                .setClientUUID(sessionInfo.getClientUUID())
                .setSessionUUID(sessionInfo.getSessionUUID())
                .setSessionStatus(sessionInfo.getSessionStatus())
                .setTransactionInfo(sessionInfo.getTransactionInfo())
                .setIsXA(sessionInfo.getIsXA())
                .setTargetServer(effectiveTargetServer != null ? effectiveTargetServer : "")
                .build();
    }
}