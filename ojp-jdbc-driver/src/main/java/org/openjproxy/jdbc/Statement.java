package org.openjproxy.jdbc;

import com.openjproxy.grpc.CallResourceRequest;
import com.openjproxy.grpc.CallResourceResponse;
import com.openjproxy.grpc.CallType;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.ParameterValue;
import com.openjproxy.grpc.ResourceType;
import com.openjproxy.grpc.TargetCall;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.client.StatementService;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.openjproxy.jdbc.Constants.EMPTY_PARAMETERS_LIST;

@Slf4j
public class Statement implements java.sql.Statement {

    private final Connection connection;
    private final StatementService statementService;
    private final Map<String, Object> properties;
    @Setter
    @Getter
    private String statementUUID;
    private int maxRows;
    private ResourceType resourceType;

    protected boolean closed;
    protected ResultSet lastResultSet;
    protected int lastUpdateCount;

    public Statement(Connection connection, StatementService statementService) {
        this(connection, statementService, null);
    }

    public Statement(Connection connection, StatementService statementService, Map<String, Object> properties) {
        this(connection, statementService, properties, ResourceType.RES_STATEMENT);
    }

    public Statement(Connection connection, StatementService statementService, Map<String, Object> properties,
                     ResourceType resourceType) {
        log.debug("Statement: constructor(connection, statementService, properties, resourceType) called");
        this.connection = connection;
        this.statementService = statementService;
        this.properties = properties;
        this.resourceType = resourceType;
        this.closed = false;
    }

    protected void checkClosed() throws SQLException {
        if (this.closed) {
            throw new SQLException("Statement is closed.");
        }
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        log.debug("executeQuery: {}", sql);
        checkClosed();
        Iterator<OpResult> itResults = this.statementService.executeQuery(this.connection.getSession(), sql,
                EMPTY_PARAMETERS_LIST, this.statementUUID, this.properties);
        return new ResultSet(itResults, this.statementService, this);
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        log.debug("executeUpdate: {}", sql);
        checkClosed();
        OpResult result = this.statementService.executeUpdate(this.connection.getSession(), sql, EMPTY_PARAMETERS_LIST,
                this.statementUUID, this.properties);
        this.connection.setSession(result.getSession());//TODO see if can do this in one place instead of updating session everywhere
        return result.getIntValue();
    }

    @Override
    public void close() throws SQLException {
        log.debug("close called");
        this.closed = true;
        if (this.getStatementUUID() != null) {
            this.callProxy(CallType.CALL_CLOSE, "", Void.class);
        }
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        log.debug("getMaxFieldSize called");
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "MaxFieldSize", Integer.class);
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        log.debug("setMaxFieldSize: {}", max);
        checkClosed();
        this.callProxy(CallType.CALL_SET, "MaxFieldSize", Void.class, Arrays.asList(max));
    }

    @Override
    public int getMaxRows() throws SQLException {
        log.debug("getMaxRows called");
        checkClosed();
        return this.maxRows;
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        log.debug("setMaxRows: {}", max);
        checkClosed();
        this.callProxy(CallType.CALL_SET, "MaxRows", Void.class, Arrays.asList(max));
        this.maxRows = max;
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        log.debug("setEscapeProcessing: {}", enable);
        checkClosed();
        this.callProxy(CallType.CALL_SET, "EscapeProcessing", Void.class, Arrays.asList(enable));
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        log.debug("getQueryTimeout called");
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "QueryTimeout", Integer.class);
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        log.debug("setQueryTimeout: {}", seconds);
        checkClosed();
        this.callProxy(CallType.CALL_SET, "QueryTimeout", Void.class, Arrays.asList(seconds));
    }

    @Override
    public void cancel() throws SQLException {
        log.debug("cancel called");
        checkClosed();
        this.callProxy(CallType.CALL_CANCEL, "", Void.class);
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        log.debug("getWarnings called");
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "Warnings", SQLWarning.class);
    }

    @Override
    public void clearWarnings() throws SQLException {
        log.debug("clearWarnings called");
        checkClosed();
        this.callProxy(CallType.CALL_CLEAR, "Warnings", Void.class);
    }

    @Override
    public void setCursorName(String name) throws SQLException {
        log.debug("setCursorName: {}", name);
        checkClosed();
        this.callProxy(CallType.CALL_SET, "CursorName", Void.class, Arrays.asList(name));
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        log.debug("execute: {}", sql);
        checkClosed();
        String trimmedSql = sql.trim().toUpperCase();
        if (trimmedSql.startsWith("SELECT")) {
            // Delegate to executeQuery
            ResultSet resultSet = this.executeQuery(sql);
            // Store the ResultSet for later retrieval if needed
            this.lastResultSet = resultSet;
            this.lastUpdateCount = -1;
            return true; // Indicates a ResultSet was returned
        } else {
            // Delegate to executeUpdate
            this.lastUpdateCount = this.executeUpdate(sql);
            return false; // Indicates no ResultSet was returned
        }
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        log.debug("getResultSet called");
        checkClosed();
        return this.lastResultSet;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        log.debug("getUpdateCount called");
        checkClosed();
        return this.lastUpdateCount;
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        log.debug("getMoreResults called");
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "MoreResults", Boolean.class);
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        log.debug("setFetchDirection: {}", direction);
        checkClosed();
        this.callProxy(CallType.CALL_SET, "FetchDirection", Void.class, Arrays.asList(direction));
    }

    @Override
    public int getFetchDirection() throws SQLException {
        log.debug("getFetchDirection called");
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "FetchDirection", Integer.class);
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        log.debug("setFetchSize: {}", rows);
        checkClosed();
        this.callProxy(CallType.CALL_SET, "FetchSize", Void.class, Arrays.asList(rows));
    }

    @Override
    public int getFetchSize() throws SQLException {
        log.debug("getFetchSize called");
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "FetchSize", Integer.class);
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        log.debug("getResultSetConcurrency called");
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "ResultSetConcurrency", Integer.class);
    }

    @Override
    public int getResultSetType() throws SQLException {
        log.debug("getResultSetType called");
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "ResultSetType", Integer.class);
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        log.debug("addBatch: {}", sql);
        checkClosed();
        this.callProxy(CallType.CALL_ADD, "Batch", Void.class, Arrays.asList(sql));
    }

    @Override
    public void clearBatch() throws SQLException {
        log.debug("clearBatch called");
        checkClosed();
        this.callProxy(CallType.CALL_CLEAR, "Batch", Void.class);
    }

    @Override
    public int[] executeBatch() throws SQLException {
        log.debug("executeBatch called");
        checkClosed();
        return this.callProxy(CallType.CALL_EXECUTE, "Batch", int[].class);
    }

    @Override
    public Connection getConnection() throws SQLException {
        log.debug("getConnection called");
        checkClosed();
        return this.connection;
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        log.debug("getMoreResults: {}", current);
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "MoreResults", Boolean.class, Arrays.asList(current));
    }

    @Override
    public RemoteProxyResultSet getGeneratedKeys() throws SQLException {
        log.debug("getGeneratedKeys called");
        checkClosed();
        String resultSetUUID = this.callProxy(CallType.CALL_GET, "GeneratedKeys", String.class);
        return new RemoteProxyResultSet(resultSetUUID, this.statementService, this.connection, this);
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        log.debug("executeUpdate: {}, autoGeneratedKeys={}", sql, autoGeneratedKeys);
        checkClosed();
        return this.callProxy(CallType.CALL_EXECUTE, "Update", Integer.class, Arrays.asList(sql, autoGeneratedKeys));
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        log.debug("executeUpdate: {}, columnIndexes.length={}", sql, columnIndexes != null ? columnIndexes.length : 0);
        checkClosed();
        return this.callProxy(CallType.CALL_EXECUTE, "Update", Integer.class, Arrays.asList(sql, columnIndexes));
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        log.debug("executeUpdate: {}, columnNames.length={}", sql, columnNames != null ? columnNames.length : 0);
        checkClosed();
        return this.callProxy(CallType.CALL_EXECUTE, "Update", Integer.class, Arrays.asList(sql, columnNames));
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        log.debug("execute: {}, autoGeneratedKeys={}", sql, autoGeneratedKeys);
        checkClosed();
        return this.callProxy(CallType.CALL_EXECUTE, "", Boolean.class, Arrays.asList(sql, autoGeneratedKeys));
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        log.debug("execute: {}, columnIndexes.length={}", sql, columnIndexes != null ? columnIndexes.length : 0);
        checkClosed();
        return this.callProxy(CallType.CALL_EXECUTE, "", Boolean.class, Arrays.asList(sql, columnIndexes));
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        log.debug("execute: {}, columnNames.length={}", sql, columnNames != null ? columnNames.length : 0);
        checkClosed();
        return this.callProxy(CallType.CALL_EXECUTE, "", Boolean.class, Arrays.asList(sql, columnNames));
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        log.debug("getResultSetHoldability called");
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "ResultSetHoldability", Integer.class);
    }

    @Override
    public boolean isClosed() throws SQLException {
        log.debug("isClosed called");
        return this.closed;
    }

    @Override
    public String enquoteIdentifier(String var1, boolean var2) throws SQLException {
        log.debug("enquoteIdentifier: {}, {}", var1, var2);
        checkClosed();
        return this.callProxy(CallType.CALL_ENQUOTE, "Identifier", String.class, Arrays.asList(var1, var2));
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        log.debug("setPoolable: {}", poolable);
        checkClosed();
        this.callProxy(CallType.CALL_SET, "Poolable", Void.class, Arrays.asList(poolable));
    }

    @Override
    public boolean isPoolable() throws SQLException {
        log.debug("isPoolable called");
        checkClosed();
        return this.callProxy(CallType.CALL_IS, "Poolable", Boolean.class);
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        log.debug("closeOnCompletion called");
        checkClosed();
        this.callProxy(CallType.CALL_CLOSE, "OnCompletion", Void.class);
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        log.debug("isCloseOnCompletion called");
        checkClosed();
        return this.callProxy(CallType.CALL_IS, "CloseOnCompletion", Boolean.class);
    }

    @Override
    public boolean isSimpleIdentifier(String identifier) throws SQLException {
        log.debug("isSimpleIdentifier: {}", identifier);
        checkClosed();
        return this.callProxy(CallType.CALL_IS, "SimpleIdentifier", Boolean.class,
                Arrays.asList(identifier));
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        log.debug("unwrap: {}", iface);
        checkClosed();
        throw new SQLFeatureNotSupportedException("Not supported.");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        log.debug("isWrapperFor: {}", iface);
        checkClosed();
        throw new SQLFeatureNotSupportedException("Not supported.");
    }

    @Override
    public long getLargeUpdateCount() throws SQLException {
        log.debug("getLargeUpdateCount called");
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "LargeUpdateCount", Long.class);
    }

    @Override
    public void setLargeMaxRows(long max) throws SQLException {
        log.debug("setLargeMaxRows: {}", max);
        checkClosed();
        this.callProxy(CallType.CALL_SET, "LargeMaxRows", Void.class, Arrays.asList(max));
    }

    @Override
    public long getLargeMaxRows() throws SQLException {
        log.debug("getLargeMaxRows called");
        checkClosed();
        return this.callProxy(CallType.CALL_GET, "LargeMaxRows", Long.class);
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        log.debug("executeLargeBatch called");
        checkClosed();
        return this.callProxy(CallType.CALL_EXECUTE, "LargeBatch", long[].class);
    }

    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        log.debug("executeLargeUpdate: {}", sql);
        checkClosed();
        return this.callProxy(CallType.CALL_EXECUTE, "LargeUpdate", Long.class, Arrays.asList(sql));
    }

    @Override
    public long executeLargeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        log.debug("executeLargeUpdate: {}, autoGeneratedKeys={}", sql, autoGeneratedKeys);
        checkClosed();
        return this.callProxy(CallType.CALL_EXECUTE, "LargeUpdate", Long.class,
                Arrays.asList(sql, autoGeneratedKeys));
    }

    @Override
    public long executeLargeUpdate(String sql, int columnIndexes[]) throws SQLException {
        log.debug("executeLargeUpdate: {}, columnIndexes.length={}", sql, columnIndexes != null ? columnIndexes.length : 0);
        checkClosed();
        return this.callProxy(CallType.CALL_EXECUTE, "LargeUpdate", Long.class,
                Arrays.asList(sql, columnIndexes));
    }

    @Override
    public long executeLargeUpdate(String sql, String columnNames[]) throws SQLException {
        log.debug("executeLargeUpdate: {}, columnNames.length={}", sql, columnNames != null ? columnNames.length : 0);
        checkClosed();
        return this.callProxy(CallType.CALL_EXECUTE, "LargeUpdate", Long.class,
                Arrays.asList(sql, columnNames));
    }

    private CallResourceRequest.Builder newCallBuilder() {
        log.debug("newCallBuilder called");
        CallResourceRequest.Builder builder = CallResourceRequest.newBuilder()
                .setSession(this.connection.getSession())
                .setResourceType(this.resourceType);
        if (this.statementUUID != null) {
            builder.setResourceUUID(this.statementUUID);
        }
        if (this.properties != null) {
            builder.addAllProperties(ProtoConverter.propertiesToProto(this.properties));
        }
        return builder;
    }

    private <T> T callProxy(CallType callType, String targetName, Class<?> returnType) throws SQLException {
        log.debug("callProxy: {}, {}, {}", callType, targetName, returnType);
        return this.callProxy(callType, targetName, returnType, Constants.EMPTY_OBJECT_LIST);
    }

    private <T> T callProxy(CallType callType, String targetName, Class<?> returnType, List<Object> params) throws SQLException {
        log.debug("callProxy: {}, {}, {}, params.size={}", callType, targetName, returnType, params != null ? params.size() : 0);
        CallResourceRequest.Builder reqBuilder = this.newCallBuilder();
        reqBuilder.setTarget(
                TargetCall.newBuilder()
                        .setCallType(callType)
                        .setResourceName(targetName)
                        .addAllParams(ProtoConverter.objectListToParameterValues(params))
                        .build()
        );
        CallResourceResponse response = this.statementService.callResource(reqBuilder.build());
        this.connection.setSession(response.getSession());
        if (this.statementUUID == null && !response.getResourceUUID().isBlank()) {
            this.statementUUID = response.getResourceUUID();
        }
        if (Void.class.equals(returnType)) {
            return null;
        }

        // Convert ParameterValue list back to the expected type
        List<ParameterValue> values = response.getValuesList();
        if (values.isEmpty()) {
            return null;
        }

        Object result = ProtoConverter.fromParameterValue(values.get(0));
        return (T) result;
    }
}
