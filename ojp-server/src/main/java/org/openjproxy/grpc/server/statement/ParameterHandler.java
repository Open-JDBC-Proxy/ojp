package org.openjproxy.grpc.server.statement;

import com.openjproxy.grpc.SessionInfo;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.server.SessionManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;



/**
 * Handles parameter setting for prepared statements.
 * Extracted from StatementServiceImpl to improve modularity.
 *
 * Note: TIMEOUT_EXECUTOR is intentionally not shut down as it's a shared resource
 * for the lifetime of the application. Daemon threads will be terminated on JVM shutdown.
 */
@Slf4j
@SuppressWarnings("java:S2142") // InterruptedException handling is appropriate for timeout scenarios
public class ParameterHandler {

    // Timeout for parameter setting operations (5 seconds)
    private static final int PARAMETER_TIMEOUT_SECONDS = 5;

    // SQL error code for timeout errors
    private static final int SQL_ERROR_CODE_TIMEOUT = 1234;

    // Thread counter for unique thread names
    private static final AtomicLong THREAD_COUNTER = new AtomicLong(0);

    // Executor for timeout operations (intentionally not shut down - application-lifetime resource)
    private static final ExecutorService TIMEOUT_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r);
        thread.setDaemon(true);
        thread.setName("ParameterSetter-Timeout-" + THREAD_COUNTER.incrementAndGet());
        return thread;
    });

    /**
     * Adds parameters to a prepared statement.
     *
     * @param sessionManager The session manager for LOB retrieval
     * @param session        The current session
     * @param ps             The prepared statement
     * @param params         The parameters to add
     * @throws SQLException if parameter setting fails
     */
    public static void addParametersPreparedStatement(SessionManager sessionManager, SessionInfo session,
                                                     PreparedStatement ps, List<Parameter> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Parameter parameter = params.get(i);
            addParam(sessionManager, session, parameter.getIndex(), ps, parameter);
        }
    }

    /**
     * Adds a single parameter to a prepared statement.
     *
     * @param sessionManager The session manager
     * @param session        The current session
     * @param idx            The parameter index
     * @param ps             The prepared statement
     * @param param          The parameter to add
     * @throws SQLException if parameter setting fails
     */
    public static void addParam(SessionManager sessionManager, SessionInfo session, int idx,
                               PreparedStatement ps, Parameter param) throws SQLException {
        log.info("Adding parameter idx {} type {}", idx, param.getType().toString());

        // Execute parameter setting with timeout to prevent JDBC driver hangs
        Future<Void> future = TIMEOUT_EXECUTOR.submit(() -> {
            setParameterInternal(sessionManager, session, idx, ps, param);
            return null;
        });

        try {
            future.get(PARAMETER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            String errorMsg = String.format(
                "OJP timeout (%d seconds) while trying to set parameter at index %d with type %s and value %s. " +
                "This might be caused because the JDBC driver does not support this parameter type. " +
                "Consider using a different data type that is natively supported by your database.",
                PARAMETER_TIMEOUT_SECONDS, idx, param.getType(),
                param.getValues().isEmpty() ? "null" : param.getValues().get(0));
            log.error(errorMsg);
            throw new SQLException(errorMsg, "HY000", SQL_ERROR_CODE_TIMEOUT);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLException) {
                throw (SQLException) cause;
            } else {
                throw new SQLException("Error setting parameter: " + cause.getMessage(), cause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Parameter setting was interrupted", e);
        }
    }

    /**
     * Internal method that actually sets the parameter without timeout.
     * This is called by addParam within a timeout wrapper.
     */
    private static void setParameterInternal(SessionManager sessionManager, SessionInfo session, int idx,
                                            PreparedStatement ps, Parameter param) throws SQLException {
        switch (param.getType()) {
            case INT:
                ps.setInt(idx, (int) param.getValues().get(0));
                break;
            case SHORT:
                ps.setShort(idx, ((Integer) param.getValues().get(0)).shortValue());
                break;
            case DOUBLE:
                ps.setDouble(idx, (double) param.getValues().get(0));
                break;
            case STRING:
                ps.setString(idx, (String) param.getValues().get(0));
                break;
            case LONG:
                ps.setLong(idx, (long) param.getValues().get(0));
                break;
            case BOOLEAN:
                ps.setBoolean(idx, (boolean) param.getValues().get(0));
                break;
            case BIG_DECIMAL:
                ps.setBigDecimal(idx, (BigDecimal) param.getValues().get(0));
                break;
            case FLOAT:
                ps.setFloat(idx, (float) param.getValues().get(0));
                break;
            case BYTES:
                ps.setBytes(idx, (byte[]) param.getValues().get(0));
                break;
            case BYTE:
                setByteParameter(ps, idx, param);
                break;
            case DATE:
                ps.setDate(idx, (Date) param.getValues().get(0));
                break;
            case TIME:
                ps.setTime(idx, (Time) param.getValues().get(0));
                break;
            case TIMESTAMP:
                ps.setTimestamp(idx, (Timestamp) param.getValues().get(0));
                break;
            case BLOB:
                setBlobParameter(sessionManager, session, ps, idx, param);
                break;
            case CLOB:
                setClobParameter(sessionManager, session, ps, idx, param);
                break;
            case BINARY_STREAM:
                setBinaryStreamParameter(ps, idx, param);
                break;
            case NULL:
                ps.setNull(idx, (int) param.getValues().get(0));
                break;
            case URL:
                setUrlParameter(ps, idx, param);
                break;
            case ROW_ID:
                setRowIdParameter(ps, idx, param);
                break;
            default:
                ps.setObject(idx, param.getValues().get(0));
                break;
        }
    }

    /**
     * Handles BYTE parameter setting with proper type conversion.
     */
    private static void setByteParameter(PreparedStatement ps, int idx, Parameter param) throws SQLException {
        Object value = param.getValues().get(0);
        if (value instanceof byte[]) {
            byte[] byteArray = (byte[]) value;
            ps.setByte(idx, byteArray.length > 0 ? byteArray[0] : (byte) 0);
        } else {
            ps.setByte(idx, ((Integer) value).byteValue());
        }
    }

    /**
     * Handles BLOB parameter setting with session management.
     */
    private static void setBlobParameter(SessionManager sessionManager, SessionInfo session,
                                        PreparedStatement ps, int idx, Parameter param) throws SQLException {
        Object blobUUID = param.getValues().get(0);
        if (blobUUID == null) {
            ps.setBlob(idx, (Blob) null);
        } else {
            ps.setBlob(idx, sessionManager.<Blob>getLob(session, (String) blobUUID));
        }
    }

    /**
     * Handles CLOB parameter setting with session management.
     */
    private static void setClobParameter(SessionManager sessionManager, SessionInfo session,
                                        PreparedStatement ps, int idx, Parameter param) throws SQLException {
        Object clobUUID = param.getValues().get(0);
        if (clobUUID == null) {
            ps.setClob(idx, (Clob) null);
        } else {
            Clob clob = sessionManager.getLob(session, (String) clobUUID);
            ps.setClob(idx, clob.getCharacterStream());
        }
    }

    /**
     * Handles BINARY_STREAM parameter setting with appropriate stream handling.
     */
    private static void setBinaryStreamParameter(PreparedStatement ps, int idx, Parameter param) throws SQLException {
        Object inputStreamValue = param.getValues().get(0);
        if (inputStreamValue == null) {
            ps.setBinaryStream(idx, null);
        } else if (inputStreamValue instanceof byte[]) {
            // DB2 requires the full binary stream to be sent at once
            ps.setBinaryStream(idx, new ByteArrayInputStream((byte[]) inputStreamValue));
        } else {
            InputStream is = (InputStream) inputStreamValue;
            if (param.getValues().size() > 1) {
                Long size = (Long) param.getValues().get(1);
                ps.setBinaryStream(idx, is, size);
            } else {
                ps.setBinaryStream(idx, is);
            }
        }
    }

    /**
     * Handles URL parameter setting with null safety.
     */
    private static void setUrlParameter(PreparedStatement ps, int idx, Parameter param) throws SQLException {
        Object urlValue = param.getValues().get(0);
        if (urlValue == null) {
            ps.setURL(idx, null);
        } else {
            ps.setURL(idx, (URL) urlValue);
        }
    }

    /**
     * Handles ROW_ID parameter setting as byte array.
     * RowId is transmitted as opaque byte array - cannot reconstruct java.sql.RowId from bytes.
     */
    private static void setRowIdParameter(PreparedStatement ps, int idx, Parameter param) throws SQLException {
        Object rowIdBytes = param.getValues().get(0);
        if (rowIdBytes == null) {
            ps.setBytes(idx, null);
        } else {
            ps.setBytes(idx, (byte[]) rowIdBytes);
        }
    }
}
