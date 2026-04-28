package org.openjproxy.grpc.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks active sessions per server for load-aware routing.
 * Replaces ConnectionTracker with a simpler session-based approach.
 *
 * This tracker maintains two mappings:
 * 1. sessionUUID -> ServerEndpoint (for looking up which server a session is bound to)
 * 2. ServerEndpoint -> session count (for load-aware server selection)
 *
 * Thread-safe implementation using ConcurrentHashMap and AtomicInteger.
 */
public class SessionTracker {

    private static final Logger log = LoggerFactory.getLogger(SessionTracker.class);

    private final Map<String, ServerEndpoint> sessionToServerMap; // sessionUUID -> server
    private final Map<ServerEndpoint, AtomicInteger> serverSessionCounts; // server -> count

    public SessionTracker() {
        this.sessionToServerMap = new ConcurrentHashMap<>();
        this.serverSessionCounts = new ConcurrentHashMap<>();
    }

    /**
     * Registers a session binding to a server.
     * Increments the session count for that server.
     *
     * @param sessionUUID The session identifier
     * @param server The server endpoint the session is bound to
     */
    public void registerSession(String sessionUUID, ServerEndpoint server) {
        if (sessionUUID == null || sessionUUID.isEmpty()) {
            log.warn("Attempted to register session with null or empty sessionUUID");
            return;
        }

        if (server == null) {
            log.warn("Attempted to register session {} with null server", sessionUUID);
            return;
        }

        // Check if session is already registered to a different server
        ServerEndpoint existingServer = sessionToServerMap.get(sessionUUID);
        if (existingServer != null && !existingServer.equals(server)) {
            log.warn("Session {} was already registered to server {}, re-registering to {}",
                    sessionUUID, existingServer.getAddress(), server.getAddress());
            // Decrement old server count
            AtomicInteger oldCount = serverSessionCounts.get(existingServer);
            if (oldCount != null) {
                oldCount.decrementAndGet();
            }
        }

        sessionToServerMap.put(sessionUUID, server);
        serverSessionCounts.computeIfAbsent(server, k -> new AtomicInteger(0))
                           .incrementAndGet();

        int currentCount = serverSessionCounts.get(server).get();
        log.debug("Registered session {} to server {}, current count: {}",
                sessionUUID, server.getAddress(), currentCount);
    }

    /**
     * Unregisters a session when it's closed.
     * Decrements the session count for that server.
     *
     * @param sessionUUID The session identifier
     */
    public void unregisterSession(String sessionUUID) {
        if (sessionUUID == null || sessionUUID.isEmpty()) {
            return;
        }

        ServerEndpoint server = sessionToServerMap.remove(sessionUUID);
        if (server != null) {
            AtomicInteger count = serverSessionCounts.get(server);
            if (count != null) {
                int newCount = count.decrementAndGet();
                // Ensure count never goes negative
                if (newCount < 0) {
                    log.warn("Session count for server {} went negative, resetting to 0",
                            server.getAddress());
                    count.set(0);
                }
                log.debug("Unregistered session {} from server {}, current count: {}",
                        sessionUUID, server.getAddress(), Math.max(0, newCount));
            }
        }
    }

    /**
     * Gets the session count per server for load-aware selection.
     * This is used by selectByLeastConnections() to choose the least-loaded server.
     *
     * @return Map of server endpoints to their session counts
     */
    public Map<ServerEndpoint, Integer> getSessionCounts() {
        Map<ServerEndpoint, Integer> counts = new HashMap<>();
        serverSessionCounts.forEach((server, atomicCount) ->
                counts.put(server, atomicCount.get()));
        return counts;
    }

    /**
     * Gets the total number of active sessions across all servers.
     *
     * @return Total session count
     */
    public int getTotalSessions() {
        return serverSessionCounts.values().stream()
                .mapToInt(AtomicInteger::get)
                .sum();
    }

    /**
     * Gets the server a session is bound to.
     *
     * @param sessionUUID The session identifier
     * @return The server endpoint, or null if session is not tracked
     */
    public ServerEndpoint getBoundServer(String sessionUUID) {
        if (sessionUUID == null || sessionUUID.isEmpty()) {
            return null;
        }
        return sessionToServerMap.get(sessionUUID);
    }

    /**
     * Checks if a session is currently tracked.
     *
     * @param sessionUUID The session identifier
     * @return true if tracked, false otherwise
     */
    public boolean isTracked(String sessionUUID) {
        return sessionUUID != null && sessionToServerMap.containsKey(sessionUUID);
    }

    /**
     * Gets the session count for a specific server.
     *
     * @param server The server endpoint
     * @return The session count, or 0 if server has no sessions
     */
    public int getSessionCount(ServerEndpoint server) {
        if (server == null) {
            return 0;
        }
        AtomicInteger count = serverSessionCounts.get(server);
        return count != null ? count.get() : 0;
    }

    /**
     * Clears all tracked sessions (for shutdown or testing).
     * This should only be called during shutdown or in test scenarios.
     */
    public void clear() {
        int totalSessions = getTotalSessions();
        sessionToServerMap.clear();
        serverSessionCounts.clear();
        log.info("Cleared {} tracked sessions", totalSessions);
    }
}
