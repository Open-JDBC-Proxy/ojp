package org.openjproxy.jdbc.xa;

import com.google.protobuf.ByteString;
import com.openjproxy.grpc.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.client.StatementService;
import org.openjproxy.jdbc.ClientUUID;

import javax.sql.ConnectionEvent;
import javax.sql.ConnectionEventListener;
import javax.sql.StatementEventListener;
import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * XAConnection linked to a remote instance of XAConnection in OJP server, it delegates all calls to server instance.
 */
@Slf4j
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class OjpXAConnection implements XAConnection {

    private String resourceUUID;
    private StatementService statementService;
    private org.openjproxy.jdbc.Connection connection;
    private transient XAResource cachedXAResource;
    private transient java.sql.Connection cachedLogicalConnection;
    private transient List<ConnectionEventListener> listeners = new ArrayList<>();

    /**
     * Factory method to create an OjpXAConnection by establishing a new session.
     * This is used by OjpXADataSource when creating a new XA connection.
     */
    public static OjpXAConnection createNewConnection(StatementService statementService, String url, 
                                                       String user, String password, Properties properties) throws SQLException {
        log.debug("Creating new OjpXAConnection for URL: {}", url);
        
        try {
            // Connect to server with XA flag enabled
            ConnectionDetails.Builder connBuilder = ConnectionDetails.newBuilder()
                    .setUrl(url)
                    .setUser(user != null ? user : "")
                    .setPassword(password != null ? password : "")
                    .setClientUUID(ClientUUID.getUUID())
                    .setIsXA(true);  // Mark this as an XA connection
            
            if (properties != null && !properties.isEmpty()) {
                Map<String, Object> propertiesMap = new HashMap<>();
                for (String key : properties.stringPropertyNames()) {
                    propertiesMap.put(key, properties.getProperty(key));
                }
                connBuilder.addAllProperties(ProtoConverter.propertiesToProto(propertiesMap));
            }

            SessionInfo sessionInfo = statementService.connect(connBuilder.build());
            log.debug("XA connection established with session: {}", sessionInfo.getSessionUUID());
            
            // Create an OJP Connection wrapper for the session
            org.openjproxy.jdbc.Connection ojpConnection = new org.openjproxy.jdbc.Connection(
                sessionInfo, statementService, org.openjproxy.database.DatabaseUtils.resolveDbName(url));
            
            // The resourceUUID for the XAConnection is the session UUID
            // The server knows to look up XA resources by session when no specific UUID is provided
            OjpXAConnection xaConn = new OjpXAConnection();
            xaConn.setResourceUUID(sessionInfo.getSessionUUID());
            xaConn.setStatementService(statementService);
            xaConn.setConnection(ojpConnection);
            xaConn.setListeners(new ArrayList<>());
            
            return xaConn;

        } catch (Exception e) {
            log.error("Failed to create XA connection", e);
            throw new SQLException("Failed to create XA connection", e);
        }
    }

    @Override
    public XAResource getXAResource() throws SQLException {
        log.debug("getXAResource called");
        if (cachedXAResource == null) {
            // For XA resources, the server uses the session's XAResource
            // Pass empty UUID so server looks up by session
            cachedXAResource = new OjpXAResource("", statementService, connection);
        }
        return cachedXAResource;
    }

    @Override
    public java.sql.Connection getConnection() throws SQLException {
        log.debug("getConnection called");
        // For XA logical connections, return the main connection
        // In the new proxy model, we don't create separate logical connection proxies
        // The connection itself acts as the logical connection
        return connection;
    }

    @Override
    public void close() throws SQLException {
        log.debug("close called");
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
        cachedXAResource = null;
        cachedLogicalConnection = null;
        
        // Notify listeners
        ConnectionEvent event = new ConnectionEvent(this);
        for (ConnectionEventListener listener : listeners) {
            listener.connectionClosed(event);
        }
    }

    @Override
    public void addConnectionEventListener(ConnectionEventListener listener) {
        log.debug("addConnectionEventListener called");
        listeners.add(listener);
    }

    @Override
    public void removeConnectionEventListener(ConnectionEventListener listener) {
        log.debug("removeConnectionEventListener called");
        listeners.remove(listener);
    }

    @Override
    public void addStatementEventListener(StatementEventListener listener) {
        log.debug("addStatementEventListener called - not supported");
        // Not supported for XA connections
    }

    @Override
    public void removeStatementEventListener(StatementEventListener listener) {
        log.debug("removeStatementEventListener called - not supported");
        // Not supported for XA connections
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OjpXAConnection)) return false;
        OjpXAConnection that = (OjpXAConnection) o;
        return Objects.equals(resourceUUID, that.resourceUUID);
    }

    @Override
    public int hashCode() {
        return Objects.hash(resourceUUID);
    }

    @Override
    public String toString() {
        return "OjpXAConnection{" +
                "resourceUUID='" + resourceUUID + '\'' +
                '}';
    }
}
