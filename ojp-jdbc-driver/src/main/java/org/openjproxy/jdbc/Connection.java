package org.openjproxy.jdbc;

import com.google.protobuf.ByteString;
import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.CallType;
import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.ParameterValue;
import com.openjproxy.grpc.ResourceType;
import com.openjproxy.grpc.SessionInfo;
import com.openjproxy.grpc.TargetCall;
import com.openjproxy.grpc.TransactionStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.client.StatementService;

import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

@Slf4j
public class Connection implements java.sql.Connection {

    @Getter
    @Setter
    private SessionInfo session;
    private final StatementService statementService;
    @Getter
    private final DbName dbName;
    private boolean autoCommit = true;
    private boolean readOnly = false;
    private boolean closed;
    
    // Lock for thread-safe session creation
    private final Object sessionCreationLock = new Object();
    
    // Store connection details for session recreation
    private volatile com.openjproxy.grpc.ConnectionDetails connectionDetails;
    
    // Track the session UUID to detect when it's been recreated
    private volatile String lastRecreatedSessionUUID = null;

    public Connection(SessionInfo session, StatementService statementService, DbName dbName) {
        this.session = session;
        this.statementService = statementService;
        this.closed = false;
        this.dbName = dbName;
    }
    
    /**
     * Set connection details for potential session recreation.
     * This should be called by the Driver after creating the Connection.
     */
    public void setConnectionDetails(com.openjproxy.grpc.ConnectionDetails connectionDetails) {
        this.connectionDetails = connectionDetails;
    }

    @Override
    public java.sql.Statement createStatement() throws SQLException {
        log.debug("createStatement called");
        return new Statement(this, statementService);
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(String sql) throws SQLException {
        log.debug("prepareStatement: {}", sql);
        return new PreparedStatement(this, sql, this.statementService);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        log.debug("prepareCall: {}", sql);
        String remoteCallableStatementUUID = this.callProxy(CallType.CALL_PREPARE, "Call", String.class, Arrays.asList(sql));
        return new org.openjproxy.jdbc.CallableStatement(this, this.statementService, remoteCallableStatementUUID);
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        log.debug("nativeSQL: {}", sql);
        return this.callProxy(CallType.CALL_NATIVE, "SQL", String.class, Arrays.asList(sql));
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        log.debug("setAutoCommit: {}", autoCommit);
        //if switching on autocommit with active transaction, commit current transaction.
        if (!this.autoCommit && autoCommit &&
                TransactionStatus.TRX_ACTIVE.equals(session.getTransactionInfo().getTransactionStatus())) {
            this.session = this.statementService.commitTransaction(this.session);
            //If switching autocommit off, start a new transaction
        } else if (this.autoCommit && !autoCommit) {
            this.session = this.statementService.startTransaction(this.session);
        }
        this.autoCommit = autoCommit;
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        log.debug("getAutoCommit called");
        return this.autoCommit;
    }

    @Override
    public void commit() throws SQLException {
        log.debug("commit called");
        if (!this.autoCommit) {
            this.session = this.statementService.commitTransaction(this.session);
        }
    }

    @Override
    public void rollback() throws SQLException {
        log.debug("rollback called");
        if (!this.autoCommit) {
            this.session = this.statementService.rollbackTransaction(this.session);
        }
    }

    /**
     * Sends a signal to terminate the current session if one exist. It DOES NOT close a connection!
     * It is important to notice that if the system is using a connection pool, this method will not be actually called
     * very often and the termination of the session will relly on the SessionTerminationTrigger logic instead.
     *
     * @throws SQLException
     */
    @Override
    public void close() throws SQLException {
        log.debug("close called");
        // Always call terminateSession to ensure server-side resources are released
        // This is critical for multinode scenarios where connect() may have been called on multiple servers
        if (this.session != null) {
            this.statementService.terminateSession(this.session);
            this.session = null;
        }
        this.closed = true;
    }

    @Override
    public boolean isClosed() throws SQLException {
        log.debug("isClosed called");
        return this.closed;
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        log.debug("getMetaData called");
        return new org.openjproxy.jdbc.DatabaseMetaData(this.session, this.statementService, this, null);
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        log.debug("setReadOnly: {}", readOnly);
        if (!DbName.H2.equals(this.dbName)) {
            this.readOnly = readOnly;
        }
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        log.debug("isReadOnly called");
        return this.readOnly;
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        log.debug("setCatalog: {}", catalog);
        this.callProxy(CallType.CALL_SET, "Catalog", Void.class, Arrays.asList(catalog));
    }

    @Override
    public String getCatalog() throws SQLException {
        log.debug("getCatalog called");
        return this.callProxy(CallType.CALL_GET, "Catalog", String.class);
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        log.debug("setTransactionIsolation: {}", level);
        this.callProxy(CallType.CALL_SET, "TransactionIsolation", Void.class, Arrays.asList(level));
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        log.debug("getTransactionIsolation called");
        return this.callProxy(CallType.CALL_GET, "TransactionIsolation", Integer.class);
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        log.debug("getWarnings called");
        return this.callProxy(CallType.CALL_GET, "Warnings", SQLWarning.class);
    }

    @Override
    public void clearWarnings() throws SQLException {
        log.debug("clearWarnings called");
    }

    @Override
    public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        log.debug("createStatement: {}, {}", resultSetType, resultSetConcurrency);
        return new Statement(this, statementService, this.hashMapOf(
                List.of(
                        CommonConstants.STATEMENT_RESULT_SET_TYPE_KEY,
                        CommonConstants.STATEMENT_RESULT_SET_CONCURRENCY_KEY
                ), List.of(resultSetType, resultSetConcurrency)
        ));
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        log.debug("prepareStatement: {}, {}, {}", sql, resultSetType, resultSetConcurrency);
        return new PreparedStatement(this, sql, statementService, this.hashMapOf(
                List.of(
                        CommonConstants.STATEMENT_RESULT_SET_TYPE_KEY,
                        CommonConstants.STATEMENT_RESULT_SET_CONCURRENCY_KEY
                ), List.of(resultSetType, resultSetConcurrency))
        );
    }

    private Map<String, Object> hashMapOf(List<String> keys, List<Object> values) {
        log.debug("hashMapOf called");
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            map.put(keys.get(i), values.get(i));
        }
        return map;
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        log.debug("prepareCall: {}, {}, {}", sql, resultSetType, resultSetConcurrency);
        String remoteCallableStatementUUID = this.callProxy(CallType.CALL_PREPARE, "Call", String.class,
                Arrays.asList(sql, resultSetType, resultSetConcurrency));
        return new org.openjproxy.jdbc.CallableStatement(this, this.statementService, remoteCallableStatementUUID);
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        log.debug("getTypeMap called");
        return this.callProxy(CallType.CALL_GET, "TypeMap", Map.class);
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        log.debug("setTypeMap: <Map>");
        this.callProxy(CallType.CALL_SET, "TypeMap", Void.class, Arrays.asList(map));
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        log.debug("setHoldability: {}", holdability);
        this.callProxy(CallType.CALL_SET, "Holdability", Void.class, Arrays.asList(holdability));
    }

    @Override
    public int getHoldability() throws SQLException {
        log.debug("getHoldability called");
        return this.callProxy(CallType.CALL_GET, "Holdability", Integer.class);
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        log.debug("setSavepoint called");
        String uuid = this.callProxy(CallType.CALL_SET, "Savepoint", String.class);
        return new org.openjproxy.jdbc.Savepoint(uuid, this.statementService, this);
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        log.debug("setSavepoint: {}", name);
        String uuid = this.callProxy(CallType.CALL_SET, "Savepoint", String.class, Arrays.asList(name));
        return new org.openjproxy.jdbc.Savepoint(uuid, this.statementService, this);
    }

    @Override
    public void rollback(java.sql.Savepoint savepoint) throws SQLException {
        log.debug("rollback: <Savepoint>");
        this.callProxy(CallType.CALL_ROLLBACK, "", Void.class,
                Arrays.asList(((Savepoint)savepoint).getSavepointUUID()));
    }

    @Override
    public void releaseSavepoint(java.sql.Savepoint savepoint) throws SQLException {
        log.debug("releaseSavepoint: <Savepoint>");
        org.openjproxy.jdbc.Savepoint ojpSavepoint = (org.openjproxy.jdbc.Savepoint) savepoint;
        this.callProxy(CallType.CALL_RELEASE, "Savepoint", Void.class,
                Arrays.asList(ojpSavepoint.getSavepointUUID()));
    }

    @Override
    public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        log.debug("createStatement: {}, {}, {}", resultSetType, resultSetConcurrency, resultSetHoldability);
        return new Statement(this, statementService, this.hashMapOf(
                List.of(CommonConstants.STATEMENT_RESULT_SET_TYPE_KEY,
                        CommonConstants.STATEMENT_RESULT_SET_CONCURRENCY_KEY,
                        CommonConstants.STATEMENT_RESULT_SET_HOLDABILITY_KEY)
                , List.of(resultSetType, resultSetConcurrency, resultSetHoldability)));
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
                                                       int resultSetHoldability) throws SQLException {
        log.debug("prepareStatement: {}, {}, {}, {}", sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        return new PreparedStatement(this, sql, this.statementService, this.hashMapOf(
                List.of(CommonConstants.STATEMENT_RESULT_SET_TYPE_KEY,
                        CommonConstants.STATEMENT_RESULT_SET_CONCURRENCY_KEY,
                        CommonConstants.STATEMENT_RESULT_SET_HOLDABILITY_KEY)
                , List.of(resultSetType, resultSetConcurrency, resultSetHoldability)));
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        log.debug("prepareCall: {}, {}, {}, {}", sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        String remoteCallableStatementUUID = this.callProxy(CallType.CALL_PREPARE, "Call", String.class,
                Arrays.asList(sql, resultSetType, resultSetConcurrency, resultSetHoldability));
        return new org.openjproxy.jdbc.CallableStatement(this, this.statementService, remoteCallableStatementUUID);
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        log.debug("prepareStatement: {}, {}", sql, autoGeneratedKeys);
        return new PreparedStatement(this, sql, this.statementService, this.hashMapOf(
                List.of(CommonConstants.STATEMENT_AUTO_GENERATED_KEYS_KEY)
                , List.of(autoGeneratedKeys)));
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        log.debug("prepareStatement: {}, <int[]>", sql);
        return new PreparedStatement(this, sql, this.statementService, this.hashMapOf(
                List.of(CommonConstants.STATEMENT_COLUMN_INDEXES_KEY)
                , List.of(columnIndexes)));
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        log.debug("prepareStatement: {}, <String[]>", sql);
        List<Object> values = new ArrayList<>();
        values.add(columnNames);
        return new PreparedStatement(this, sql, this.statementService, this.hashMapOf(
                List.of(CommonConstants.STATEMENT_COLUMN_NAMES_KEY)
                , values));
    }

    @Override
    public Clob createClob() throws SQLException {
        log.debug("createClob called");
        return new org.openjproxy.jdbc.Clob(this, new LobServiceImpl(this, this.statementService),
                this.statementService,
                null
        );
    }

    @Override
    public Blob createBlob() throws SQLException {
        log.debug("createBlob called");
        return new org.openjproxy.jdbc.Blob(this, new LobServiceImpl(this, this.statementService),
                this.statementService,
                null
        );
    }

    @Override
    public NClob createNClob() throws SQLException {
        log.debug("createNClob called");
        return new org.openjproxy.jdbc.NClob();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        log.debug("createSQLXML called");
        return new org.openjproxy.jdbc.SQLXML();
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        log.debug("isValid: {}", timeout);
        if (this.closed) {
            return false;
        }
        return this.callProxy(CallType.CALL_IS, "Valid", Boolean.class, Arrays.asList(timeout));
    }

    @SneakyThrows //TODO revisit, maybe can be transferred from server and parsed in the client
    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        log.debug("setClientInfo: {}, {}", name, value);
        this.callProxy(CallType.CALL_SET, "ClientInfo", Void.class, Arrays.asList(name, value));
    }

    @SneakyThrows //TODO revisit, maybe can be transferred from server and parsed in the client
    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        log.debug("setClientInfo: <Properties>");
        this.callProxy(CallType.CALL_SET, "ClientInfo", Void.class, Arrays.asList(properties));
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        log.debug("getClientInfo: {}", name);
        return this.callProxy(CallType.CALL_GET, "ClientInfo", String.class, Arrays.asList(name));
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        log.debug("getClientInfo called");
        return this.callProxy(CallType.CALL_GET, "ClientInfo", Properties.class);
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        log.debug("createArrayOf: {}, <Object[]>", typeName);
        if (DbName.MYSQL.equals(this.dbName)) {
            throw new SQLFeatureNotSupportedException("MySql does not support creating array of.");
        }
        if (DbName.MARIADB.equals(this.dbName)) {
            throw new SQLFeatureNotSupportedException("MariaDB does not support creating array of.");
        }
        return new org.openjproxy.jdbc.Array();
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        log.debug("createStruct: {}, <Object[]>", typeName);
        throw new SQLFeatureNotSupportedException("Not supported.");
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        log.debug("setSchema: {}", schema);
        this.callProxy(CallType.CALL_SET, "Schema", Void.class, Arrays.asList(schema));
    }

    @Override
    public String getSchema() throws SQLException {
        log.debug("getSchema called");
        return this.callProxy(CallType.CALL_GET, "Schema", String.class);
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        log.debug("abort called");
        throw new SQLFeatureNotSupportedException("Not supported.");
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        log.debug("setNetworkTimeout: <Executor>, {}", milliseconds);
        this.callProxy(CallType.CALL_SET, "NetworkTimeout", Void.class,
                Arrays.asList(executor, milliseconds));
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        log.debug("getNetworkTimeout called");
        return this.callProxy(CallType.CALL_GET, "NetworkTimeout", Integer.class);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        log.debug("unwrap: {}", iface);
        throw new SQLFeatureNotSupportedException("Cannot unwrap remote proxy object.");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        log.debug("isWrapperFor: {}", iface);
        return false;
    }

    private CallResourceRequest.Builder newCallBuilder() {
        log.debug("newCallBuilder called");
        return CallResourceRequest.newBuilder()
                .setSession(this.session)
                .setResourceType(ResourceType.RES_CONNECTION);
    }

    private <T> T callProxy(CallType callType, String targetName, Class returnType) throws SQLException {
        log.debug("callProxy: {}, {}, {}", callType, targetName, returnType);
        return this.callProxy(callType, targetName, returnType, Constants.EMPTY_OBJECT_LIST);
    }

    private <T> T callProxy(CallType callType, String targetName, Class returnType, List<Object> params) throws SQLException {
        log.debug("callProxy: {}, {}, {}, <params>", callType, targetName, returnType);
        
        try {
            return executeCallProxy(callType, targetName, returnType, params);
        } catch (SQLException e) {
            // Check if this is a "no session" or "session not found" error
            if (isSessionNotFoundError(e)) {
                log.info("Session not found on server, attempting to create new session and retry call");
                
                // Ensure session exists (thread-safe)
                ensureSessionExists();
                
                // Retry the call with the new session
                log.debug("Retrying callProxy after session creation");
                return executeCallProxy(callType, targetName, returnType, params);
            }
            // Re-throw other SQL exceptions
            throw e;
        }
    }
    
    /**
     * Execute the actual callProxy request.
     */
    private <T> T executeCallProxy(CallType callType, String targetName, Class returnType, List<Object> params) throws SQLException {
        CallResourceRequest.Builder reqBuilder = this.newCallBuilder();
        reqBuilder.setTarget(
                TargetCall.newBuilder()
                        .setCallType(callType)
                        .setResourceName(targetName)
                        .addAllParams(ProtoConverter.objectListToParameterValues(params))
                        .build()
        );
        
        CallResourceResponse response = this.statementService.callResource(reqBuilder.build());
        this.session = response.getSession();
        this.setSession(response.getSession());
        
        if (Void.class.equals(returnType)) {
            return null;
        }
        
        List<ParameterValue> values = response.getValuesList();
        if (values.isEmpty()) {
            return null;
        }
        
        Object result = ProtoConverter.fromParameterValue(values.get(0));
        return (T) result;
    }
    
    /**
     * Check if the exception indicates a missing session.
     * This checks for common error messages that indicate session not found.
     */
    private boolean isSessionNotFoundError(SQLException e) {
        if (e == null) {
            return false;
        }
        
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        
        // Check for various "session not found" patterns
        String lowerMsg = message.toLowerCase();
        return lowerMsg.contains("session not found") ||
               lowerMsg.contains("no active session") ||
               lowerMsg.contains("connection not found for this sessioninfo") ||
               (lowerMsg.contains("session") && (lowerMsg.contains("not found") || lowerMsg.contains("missing")));
    }
    
    /**
     * Ensures a session exists on the server. Creates one if missing.
     * Thread-safe: only one thread will create the session if multiple threads detect missing session concurrently.
     */
    private void ensureSessionExists() throws SQLException {
        String currentSessionUUID = this.session != null ? this.session.getSessionUUID() : null;
        
        synchronized (sessionCreationLock) {
            // Check if another thread already recreated the session while we were waiting
            String newSessionUUID = this.session != null ? this.session.getSessionUUID() : null;
            
            // If session UUID changed, another thread already recreated it
            if (currentSessionUUID != null && newSessionUUID != null && !currentSessionUUID.equals(newSessionUUID)) {
                log.debug("Session was already recreated by another thread. Current: {}, New: {}", 
                        currentSessionUUID, newSessionUUID);
                return;
            }
            
            // Also check if we recently recreated this exact session UUID
            if (newSessionUUID != null && newSessionUUID.equals(lastRecreatedSessionUUID)) {
                log.debug("Session {} was recently recreated, skipping duplicate recreation", newSessionUUID);
                return;
            }
            
            // Create new session
            createNewSession();
            lastRecreatedSessionUUID = this.session.getSessionUUID();
        }
    }
    
    /**
     * Creates a new session on the server by calling connect.
     * Must be called within synchronized block.
     */
    private void createNewSession() throws SQLException {
        if (connectionDetails == null) {
            throw new SQLException("Cannot recreate session: connection details not available. " +
                    "This connection was not properly initialized by the driver.");
        }
        
        log.info("Creating new session on server due to missing session. URL: {}, ClientUUID: {}",
                connectionDetails.getUrl(), connectionDetails.getClientUUID());
        
        try {
            SessionInfo newSession = this.statementService.connect(connectionDetails);
            this.session = newSession;
            
            log.info("Successfully created new session. SessionUUID: {}, ConnHash: {}",
                    newSession.getSessionUUID(), newSession.getConnHash());
        } catch (SQLException e) {
            log.error("Failed to create new session: {}", e.getMessage(), e);
            throw new SQLException("Failed to recreate session on server: " + e.getMessage(), e);
        }
    }
}