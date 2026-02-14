package org.openjproxy.grpc.server.action.statement;

import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.StatementRequest;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.server.ConnectionSessionDTO;
import org.openjproxy.grpc.server.action.ValueAction;
import org.openjproxy.grpc.server.action.session.SessionConnectionAction;
import org.openjproxy.grpc.server.action.session.SessionConnectionRequest;
import org.openjproxy.grpc.server.resultset.ResultSetHandler;
import org.openjproxy.grpc.server.sql.SqlSessionAffinityDetector;
import org.openjproxy.grpc.server.statement.StatementFactory;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.sql.SqlEnhancerEngine;
import org.openjproxy.grpc.server.sql.SqlEnhancementResult;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import lombok.Builder;
import lombok.Value;

@Slf4j
public class ExecuteQueryInternalAction
        implements ValueAction<ExecuteQueryInternalAction.ExecuteQueryInternalRequest, OpResult> {

    private static final ExecuteQueryInternalAction INSTANCE = new ExecuteQueryInternalAction();

    private ExecuteQueryInternalAction() {
    }

    public static ExecuteQueryInternalAction getInstance() {
        return INSTANCE;
    }

    @Value
    @Builder
    public static class ExecuteQueryInternalRequest {
        ActionContext context;
        StatementRequest statementRequest;
        StreamObserver<OpResult> responseObserver;
    }

    @Override
    public OpResult execute(ExecuteQueryInternalRequest internalRequest) throws SQLException {
        StatementRequest request = internalRequest.getStatementRequest();
        ActionContext context = internalRequest.getContext();
        StreamObserver<OpResult> responseObserver = internalRequest.getResponseObserver();

        ConnectionSessionDTO dto;
        // Check if SQL requires session affinity (temporary tables, session variables,
        // etc.)
        // Note: All queries already create sessions (for result set handling), but this
        // ensures session affinity is properly enforced even for queries that don't
        // return results
        boolean requiresSessionAffinity = SqlSessionAffinityDetector.requiresSessionAffinity(request.getSql());

        SessionConnectionRequest sessionConnRequest = SessionConnectionRequest.builder()
                .context(context)
                .sessionInfo(request.getSession())
                .startSessionIfNone(true || requiresSessionAffinity)
                .build();
        dto = SessionConnectionAction.getInstance().execute(sessionConnRequest);

        long enhancementStartTime = System.currentTimeMillis();

        String sql = request.getSql();
        if (context.getSqlEnhancerEngine().isEnabled()) {
            // Ensure schema is loaded before enhancement (on-demand, only once)
            try {
                // Get the DataSource for this connection
                String dsKey = dto.getSession().getConnHash();
                javax.sql.DataSource dataSource = context.getDatasourceMap().get(dsKey);

                if (dataSource != null) {
                    // Get catalog and schema from the connection
                    java.sql.Connection connection = dto.getConnection();
                    String catalogName = connection.getCatalog();
                    String schemaName = connection.getSchema();

                    // PostgreSQL: Use "public" schema if schema name is null or empty
                    // This ensures tables created in the default schema are visible to Calcite
                    if ((schemaName == null || schemaName.isEmpty()) &&
                            connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL")) {
                        schemaName = "public";
                        log.debug("Using default PostgreSQL 'public' schema for schema loading");
                    }

                    // Ensure schema is loaded (thread-safe, idempotent)
                    context.getSqlEnhancerEngine().ensureSchemaLoaded(dataSource, catalogName, schemaName);
                } else {
                    log.debug("No DataSource found for connection hash: {}", dsKey);
                }
            } catch (Exception e) {
                // Log but don't fail - enhancement can proceed without schema
                log.warn("Failed to ensure schema loaded: {}", e.getMessage());
            }

            SqlEnhancementResult result = context.getSqlEnhancerEngine().enhance(request.getSql());
            sql = result.getEnhancedSql();

            long enhancementDuration = System.currentTimeMillis() - enhancementStartTime;

            if (result.isModified()) {
                log.debug("SQL was enhanced in {}ms: {} -> {}", enhancementDuration,
                        request.getSql().substring(0, Math.min(request.getSql().length(), 50)),
                        sql.substring(0, Math.min(sql.length(), 50)));
            } else if (enhancementDuration > 10) {
                log.debug("SQL enhancement took {}ms (no modifications)", enhancementDuration);
            }
        }

        List<Parameter> params = ProtoConverter.fromProtoList(request.getParametersList());
        if (CollectionUtils.isNotEmpty(params)) {
            PreparedStatement ps = StatementFactory.createPreparedStatement(context.getSessionManager(), dto, sql,
                    params, request);
            String resultSetUUID = context.getSessionManager().registerResultSet(dto.getSession(), ps.executeQuery());
            ResultSetHandler.getInstance().handleResultSet(context, dto.getSession(), resultSetUUID, responseObserver);
        } else {
            Statement stmt = StatementFactory.createStatement(context.getSessionManager(), dto.getConnection(),
                    request);
            String resultSetUUID = context.getSessionManager().registerResultSet(dto.getSession(),
                    stmt.executeQuery(sql));
            ResultSetHandler.getInstance().handleResultSet(context, dto.getSession(), resultSetUUID, responseObserver);
        }

        return null; // The result is returned via StreamObserver
    }
}
