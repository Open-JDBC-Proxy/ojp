package org.openjproxy.grpc.client;

import javax.sql.XAConnection;

/**
 * Metadata for tracking XA connections in multinode deployments.
 * Used by ConnectionTracker to identify idle XA connections for redistribution.
 */
public class XAConnectionInfo {
    
    private final String connectionUuid;
    private final XAConnection xaConnection;
    private final ServerEndpoint boundServer;
    private final long lastUsedTime;
    private volatile boolean hasActiveTransaction;
    
    public XAConnectionInfo(String connectionUuid, XAConnection xaConnection, 
                           ServerEndpoint boundServer, long lastUsedTime) {
        this.connectionUuid = connectionUuid;
        this.xaConnection = xaConnection;
        this.boundServer = boundServer;
        this.lastUsedTime = lastUsedTime;
        this.hasActiveTransaction = false;
    }
    
    public String getConnectionUuid() {
        return connectionUuid;
    }
    
    public XAConnection getXaConnection() {
        return xaConnection;
    }
    
    public ServerEndpoint getBoundServer() {
        return boundServer;
    }
    
    public long getLastUsedTime() {
        return lastUsedTime;
    }
    
    public boolean hasActiveTransaction() {
        return hasActiveTransaction;
    }
    
    public void setActiveTransaction(boolean active) {
        this.hasActiveTransaction = active;
    }
    
    @Override
    public String toString() {
        return "XAConnectionInfo{" +
                "uuid='" + connectionUuid + '\'' +
                ", server=" + (boundServer != null ? boundServer.getAddress() : "null") +
                ", lastUsed=" + lastUsedTime +
                ", activeTransaction=" + hasActiveTransaction +
                '}';
    }
}
