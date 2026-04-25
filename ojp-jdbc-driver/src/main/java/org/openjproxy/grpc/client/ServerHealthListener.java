package org.openjproxy.grpc.client;

/**
 * Listener interface for server health changes in multinode deployments.
 * Implementers can be notified when servers become unhealthy or recover.
 */
public interface ServerHealthListener {

    /**
     * Called when a server is marked as unhealthy.
     *
     * @param endpoint The server endpoint that became unhealthy
     * @param exception The exception that caused the server to be marked unhealthy
     */
    void onServerUnhealthy(ServerEndpoint endpoint, Exception exception);

    /**
     * Called when a server recovers and becomes healthy again.
     *
     * @param endpoint The server endpoint that recovered
     */
    void onServerRecovered(ServerEndpoint endpoint);
}
