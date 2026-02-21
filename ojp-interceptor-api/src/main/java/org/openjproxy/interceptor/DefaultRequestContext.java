package org.openjproxy.interceptor;

import com.openjproxy.grpc.SessionInfo;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Default implementation of {@link RequestContext}.
 * 
 * <p>This class provides a mutable context that flows through the interceptor chain.</p>
 */
public class DefaultRequestContext implements RequestContext {
    
    private final RequestType requestType;
    private final String originalSql;
    private final String sqlHash;
    private final SessionInfo sessionInfo;
    private final String connectionHash;
    private final long startTimeMillis;
    private final DataSourceMetadata dataSourceMetadata;
    private final Map<String, Object> parameters;
    private final Map<String, Object> attributes;
    
    private LifecyclePhase currentPhase;
    private String currentSql;
    private Connection connection;
    private Object result;
    private ResultSet resultSet;
    private Exception exception;
    private Long endTimeMillis;
    private boolean shortCircuited;
    
    private DefaultRequestContext(Builder builder) {
        this.requestType = builder.requestType;
        this.originalSql = builder.originalSql;
        this.currentSql = builder.originalSql;
        this.sqlHash = builder.sqlHash;
        this.sessionInfo = builder.sessionInfo;
        this.connectionHash = builder.connectionHash;
        this.startTimeMillis = builder.startTimeMillis;
        this.dataSourceMetadata = builder.dataSourceMetadata;
        this.parameters = builder.parameters != null ? new HashMap<>(builder.parameters) : null;
        this.attributes = new HashMap<>();
        this.currentPhase = LifecyclePhase.PRE_REQUEST;
        this.shortCircuited = false;
    }
    
    @Override
    public RequestType getRequestType() {
        return requestType;
    }
    
    @Override
    public LifecyclePhase getCurrentPhase() {
        return currentPhase;
    }
    
    @Override
    public void setCurrentPhase(LifecyclePhase phase) {
        this.currentPhase = phase;
    }
    
    @Override
    public String getOriginalSql() {
        return originalSql;
    }
    
    @Override
    public String getCurrentSql() {
        return currentSql;
    }
    
    @Override
    public void setCurrentSql(String sql) {
        this.currentSql = sql;
    }
    
    @Override
    public String getSqlHash() {
        return sqlHash;
    }
    
    @Override
    public SessionInfo getSessionInfo() {
        return sessionInfo;
    }
    
    @Override
    public String getConnectionHash() {
        return connectionHash;
    }
    
    @Override
    public Optional<Map<String, Object>> getParameters() {
        return Optional.ofNullable(parameters);
    }
    
    @Override
    public Optional<Connection> getConnection() {
        return Optional.ofNullable(connection);
    }
    
    @Override
    public void setConnection(Connection connection) {
        this.connection = connection;
    }
    
    @Override
    public Optional<Object> getResult() {
        return Optional.ofNullable(result);
    }
    
    @Override
    public void setResult(Object result) {
        this.result = result;
    }
    
    @Override
    public Optional<ResultSet> getResultSet() {
        return Optional.ofNullable(resultSet);
    }
    
    @Override
    public Optional<Exception> getException() {
        return Optional.ofNullable(exception);
    }
    
    @Override
    public void setException(Exception exception) {
        this.exception = exception;
    }
    
    @Override
    public long getStartTimeMillis() {
        return startTimeMillis;
    }
    
    @Override
    public Optional<Long> getEndTimeMillis() {
        return Optional.ofNullable(endTimeMillis);
    }
    
    @Override
    public void setEndTimeMillis(long endTimeMillis) {
        this.endTimeMillis = endTimeMillis;
    }
    
    @Override
    public Object getAttribute(String key) {
        return attributes.get(key);
    }
    
    @Override
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }
    
    @Override
    public boolean isShortCircuited() {
        return shortCircuited;
    }
    
    @Override
    public void setShortCircuited(boolean shortCircuited) {
        this.shortCircuited = shortCircuited;
    }
    
    @Override
    public DataSourceMetadata getDataSourceMetadata() {
        return dataSourceMetadata;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private RequestType requestType;
        private String originalSql;
        private String sqlHash;
        private SessionInfo sessionInfo;
        private String connectionHash;
        private long startTimeMillis = System.currentTimeMillis();
        private DataSourceMetadata dataSourceMetadata;
        private Map<String, Object> parameters;
        
        public Builder requestType(RequestType requestType) {
            this.requestType = requestType;
            return this;
        }
        
        public Builder originalSql(String originalSql) {
            this.originalSql = originalSql;
            return this;
        }
        
        public Builder sqlHash(String sqlHash) {
            this.sqlHash = sqlHash;
            return this;
        }
        
        public Builder sessionInfo(SessionInfo sessionInfo) {
            this.sessionInfo = sessionInfo;
            return this;
        }
        
        public Builder connectionHash(String connectionHash) {
            this.connectionHash = connectionHash;
            return this;
        }
        
        public Builder startTimeMillis(long startTimeMillis) {
            this.startTimeMillis = startTimeMillis;
            return this;
        }
        
        public Builder dataSourceMetadata(DataSourceMetadata dataSourceMetadata) {
            this.dataSourceMetadata = dataSourceMetadata;
            return this;
        }
        
        public Builder parameters(Map<String, Object> parameters) {
            this.parameters = parameters;
            return this;
        }
        
        public DefaultRequestContext build() {
            return new DefaultRequestContext(this);
        }
    }
}
