package org.openjproxy.grpc.server.action.session;

import com.openjproxy.grpc.DbName;
import com.openjproxy.grpc.OpResult;
import com.openjproxy.grpc.SessionInfo;
import io.grpc.stub.StreamObserver;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.database.DatabaseUtils;
import org.openjproxy.grpc.dto.OpQueryResult;
import org.openjproxy.grpc.server.HydratedResultSetMetadata;
import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.action.ActionContext;
import org.openjproxy.grpc.server.lob.LobProcessor;
import org.openjproxy.grpc.server.resultset.ResultSetWrapper;
import org.openjproxy.grpc.server.utils.DateTimeUtils;

import java.sql.Clob;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Utility class for processing JDBC {@link ResultSet}s and streaming query
 * results to gRPC clients.
 * <p>
 * Handles conversion of JDBC result set rows into the format expected by the
 * OpenJProxy gRPC API,
 * including special handling for LOBs (BLOBs, CLOBs), binary data, date/time
 * types, and database-specific
 * behaviors (e.g., row-by-row mode for SQL Server and DB2 when LOBs are
 * present).
 * </p>
 */
@Slf4j
public class ResultSetHelper {

    private static final String RESULT_SET_METADATA_ATTR_PREFIX = "rsMetadata|";
    private static final String MATERIALIZED_RS_ATTR_PREFIX = "materializedRs|";
    private static final List<String> INPUT_STREAM_TYPES = Arrays.asList("RAW", "BINARY VARYING", "BYTEA");

    /**
     * Private constructor to prevent instantiation.
     * This class provides only static utility methods.
     */
    private ResultSetHelper() {
        // Empty constructor
    }

    /**
     * Processes a JDBC result set and streams its rows to the gRPC response
     * observer.
     * <p>
     * Iterates over all rows in the result set, converting column values according
     * to their SQL types.
     * Results are sent in blocks of
     * {@link org.openjproxy.constants.CommonConstants#ROWS_PER_RESULT_SET_DATA_BLOCK}
     * rows. For SQL Server and DB2, when LOB columns are present, only one row is
     * sent per call to support
     * row-by-row fetching.
     * </p>
     *
     * @param context          the action context providing session and database
     *                         access
     * @param session          the session information for the current client
     * @param resultSetUUID    the unique identifier of the result set to process
     * @param responseObserver the gRPC stream observer to send results to
     * @throws SQLException if a database access error occurs while reading the
     *                      result set
     */
    public static void handleResultSet(ActionContext context, SessionInfo session, String resultSetUUID,
            StreamObserver<OpResult> responseObserver)
            throws SQLException {
        var sessionManager = context.getSessionManager();

        ResultSet rs = sessionManager.getResultSet(session, resultSetUUID);
        OpQueryResult.OpQueryResultBuilder queryResultBuilder = OpQueryResult.builder();
        int columnCount = rs.getMetaData().getColumnCount();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < columnCount; i++) {
            labels.add(rs.getMetaData().getColumnName(i + 1));
        }
        queryResultBuilder.labels(labels);

        List<Object[]> results = new ArrayList<>();
        int row = 0;
        boolean justSent = false;
        DbName dbName = DatabaseUtils.resolveDbName(rs.getStatement().getConnection().getMetaData().getURL());
        // Only used if result set contains LOBs in SQL Server and DB2 (if LOB's
        // present), so cursor is not read in advance,
        // every row has to be requested by the jdbc client.
        String resultSetMode = "";
        boolean resultSetMetadataCollected = false;

        boolean materializedMode = context.getServerConfiguration().isMaterializedModeEnabled();
        if (materializedMode) {
            // Snapshot metadata early so it survives RS closure.
            collectResultSetMetadata(context, session, resultSetUUID, rs);
            resultSetMetadataCollected = true;
        }

        while (rs.next()) {
            if (DbName.DB2.equals(dbName) && !resultSetMetadataCollected) {
                collectResultSetMetadata(context, session, resultSetUUID, rs);
                resultSetMetadataCollected = true;
            }
            justSent = false;
            row++;
            Object[] rowValues = new Object[columnCount];
            for (int i = 0; i < columnCount; i++) {
                int colType = rs.getMetaData().getColumnType(i + 1);
                String colTypeName = rs.getMetaData().getColumnTypeName(i + 1);
                Object currentValue = null;

                boolean isSQLOrDB2 = DbName.SQL_SERVER.equals(dbName) || DbName.DB2.equals(dbName);

                // Postgres uses type BYTEA which translates to type VARBINARY
                switch (colType) {
                    case Types.OTHER: {
                        // PostgreSQL reports JSON and JSONB columns as Types.OTHER.
                        // Use getString() which is supported by all JDBC drivers for JSON columns
                        // and returns the JSON text directly without vendor-specific wrapper objects.
                        if ("json".equalsIgnoreCase(colTypeName) || "jsonb".equalsIgnoreCase(colTypeName)) {
                            currentValue = rs.getString(i + 1);
                        } else {
                            currentValue = rs.getObject(i + 1);
                        }
                        break;
                    }
                    case Types.VARBINARY: {
                        if (isSQLOrDB2) {
                            resultSetMode = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
                        }
                        if ("BLOB".equalsIgnoreCase(colTypeName)) {
                            currentValue = LobProcessor.treatAsBlob(sessionManager, session, rs, i,
                                    context.getDbNameMap());
                        } else {
                            currentValue = LobProcessor.treatAsBinary(sessionManager, session, dbName, rs, i,
                                    INPUT_STREAM_TYPES);
                        }
                        break;
                    }
                    case Types.BLOB, Types.LONGVARBINARY: {
                        if (isSQLOrDB2) {
                            resultSetMode = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
                        }
                        currentValue = LobProcessor.treatAsBlob(sessionManager, session, rs, i, context.getDbNameMap());
                        break;
                    }
                    case Types.CLOB: {
                        if (isSQLOrDB2) {
                            resultSetMode = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
                        }
                        Clob clob = rs.getClob(i + 1);
                        if (clob != null) {
                            String clobUUID = UUID.randomUUID().toString();
                            // CLOB needs to be prefixed as per it can be read in the JDBC driver by
                            // getString method and it would be valid to return just a UUID as string
                            currentValue = CommonConstants.OJP_CLOB_PREFIX + clobUUID;
                            sessionManager.registerLob(session, clob, clobUUID);
                        }
                        break;
                    }
                    case Types.BINARY: {
                        if (isSQLOrDB2) {
                            resultSetMode = CommonConstants.RESULT_SET_ROW_BY_ROW_MODE;
                        }
                        currentValue = LobProcessor.treatAsBinary(sessionManager, session, dbName, rs, i,
                                INPUT_STREAM_TYPES);
                        break;
                    }
                    case Types.DATE: {
                        Date date = rs.getDate(i + 1);
                        if ("YEAR".equalsIgnoreCase(colTypeName)) {
                            currentValue = date.toLocalDate().getYear();
                        } else {
                            currentValue = date;
                        }
                        break;
                    }
                    case Types.TIMESTAMP: {
                        currentValue = rs.getTimestamp(i + 1);
                        break;
                    }
                    default: {
                        // Oracle 21c+ native JSON columns use a vendor-specific type code (not Types.OTHER).
                        // Detect them by column type name and use getString() to return plain JSON text.
                        if ("json".equalsIgnoreCase(colTypeName)) {
                            currentValue = rs.getString(i + 1);
                        } else {
                            currentValue = rs.getObject(i + 1);
                        }
                        // com.microsoft.sqlserver.jdbc.DateTimeOffset special case as per it does not
                        // implement any standar java.sql interface.
                        if ("datetimeoffset".equalsIgnoreCase(colTypeName) && colType == -155) {
                            currentValue = DateTimeUtils.extractOffsetDateTime(currentValue);
                        }
                        break;
                    }
                }
                rowValues[i] = currentValue;

            }
            results.add(rowValues);

            if ((DbName.DB2.equals(dbName) || DbName.SQL_SERVER.equals(dbName))
                    && CommonConstants.RESULT_SET_ROW_BY_ROW_MODE.equalsIgnoreCase(resultSetMode)) {
                break;
            }

            if (row % CommonConstants.ROWS_PER_RESULT_SET_DATA_BLOCK == 0) {
                justSent = true;
                // Send a block of records
                responseObserver.onNext(ResultSetWrapper.wrapResults(session, results, queryResultBuilder,
                        resultSetUUID, resultSetMode));
                queryResultBuilder = OpQueryResult.builder();// Recreate the builder to not send labels in every block.
                results = new ArrayList<>();
            }
        }

        if (!justSent) {
            // Send a block of remaining records
            responseObserver.onNext(
                    ResultSetWrapper.wrapResults(session, results, queryResultBuilder, resultSetUUID, resultSetMode));
        }

        responseObserver.onCompleted();

        // Materialized mode: release physical JDBC resources early when the RS is
        // fully exhausted and no LOB cursor is required.
        if (materializedMode && !CommonConstants.RESULT_SET_ROW_BY_ROW_MODE.equalsIgnoreCase(resultSetMode)) {
            releaseMaterializedResources(context, session, resultSetUUID, rs);
        }
    }

    /**
     * Updates the last activity time for the session to prevent premature cleanup.
     * <p>
     * This should be called at the beginning of any method that operates on a
     * session
     * to ensure the session is not evicted by idle timeout while processing.
     * </p>
     *
     * @param context     the action context providing session manager access
     * @param sessionInfo the session information; no-op if null or has empty
     *                    session UUID
     */
    public static void updateSessionActivity(ActionContext context, SessionInfo sessionInfo) {
        if (sessionInfo != null && !sessionInfo.getSessionUUID().isEmpty()) {
            context.getSessionManager().updateSessionActivity(sessionInfo);
        }
    }

    /**
     * Collects and registers result set metadata in the session for DB2 databases.
     * <p>
     * DB2 requires metadata to be collected before row iteration when LOBs may be
     * present,
     * since the cursor is not read in advance. The metadata is stored under a
     * session attribute
     * keyed by {@link #RESULT_SET_METADATA_ATTR_PREFIX} plus the result set UUID.
     * </p>
     *
     * @param context       the action context providing session manager access
     * @param session       the session to register the metadata in
     * @param resultSetUUID the unique identifier of the result set
     * @param rs            the JDBC result set whose metadata to collect
     */
    @SneakyThrows
    private static void collectResultSetMetadata(ActionContext context, SessionInfo session, String resultSetUUID,
            ResultSet rs) {
        context.getSessionManager().registerAttr(session, RESULT_SET_METADATA_ATTR_PREFIX +
                resultSetUUID, new HydratedResultSetMetadata(rs.getMetaData()));
    }

    /**
     * Releases physical JDBC resources after a fully-consumed ResultSet in materialized mode.
     * <p>
     * Closes the ResultSet and its owning Statement. If the session is a non-XA session
     * and the connection is currently in auto-commit mode (i.e., not inside an active
     * transaction), the JDBC connection is also returned to the pool. A lightweight marker
     * is stored in the session so that subsequent {@code close()} calls from the client
     * remain idempotent.
     * </p>
     *
     * @param context       the action context
     * @param sessionInfo   the session that owns the resources
     * @param resultSetUUID the UUID of the exhausted result set
     * @param rs            the exhausted JDBC ResultSet
     */
    private static void releaseMaterializedResources(ActionContext context, SessionInfo sessionInfo,
            String resultSetUUID, ResultSet rs) {
        // Mark as materialized so CallResourceAction can handle close() gracefully.
        context.getSessionManager().registerAttr(sessionInfo,
                MATERIALIZED_RS_ATTR_PREFIX + resultSetUUID, Boolean.TRUE);

        Statement stmt = null;
        try {
            stmt = rs.getStatement();
        } catch (SQLException e) {
            log.debug("Could not retrieve Statement from exhausted ResultSet {}: {}", resultSetUUID, e.getMessage());
        }

        try {
            rs.close();
            log.debug("Materialized mode: closed ResultSet {}", resultSetUUID);
        } catch (SQLException e) {
            log.debug("Materialized mode: error closing ResultSet {}: {}", resultSetUUID, e.getMessage());
        }

        if (stmt != null) {
            try {
                stmt.close();
                log.debug("Materialized mode: closed Statement for ResultSet {}", resultSetUUID);
            } catch (SQLException e) {
                log.debug("Materialized mode: error closing Statement for ResultSet {}: {}", resultSetUUID, e.getMessage());
            }
        }

        // Release the connection if outside a transaction and non-XA.
        Session session = context.getSessionManager().getSession(sessionInfo);
        if (session == null || session.isXA()) {
            return;
        }
        try {
            java.sql.Connection conn = session.getConnection();
            if (conn != null && conn.getAutoCommit()) {
                session.releaseConnection();
                log.debug("Materialized mode: returned connection to pool for session {}", sessionInfo.getSessionUUID());
            }
        } catch (SQLException e) {
            log.debug("Materialized mode: error releasing connection for session {}: {}",
                    sessionInfo.getSessionUUID(), e.getMessage());
        }
    }
}
