package org.openjproxy.grpc.server.statement;

import com.openjproxy.grpc.SessionInfo;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.server.SessionManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.List;

/**
 * Handles parameter setting for prepared statements.
 * Extracted from StatementServiceImpl to improve modularity.
 */
@Slf4j
public class ParameterHandler {

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
        switch (param.getType()) {
            case INT:
                // Use Number to handle JSON deserialization (Long) or direct Integer
                ps.setInt(idx, ((Number) param.getValues().get(0)).intValue());
                break;
            case DOUBLE:
                // Use Number to handle JSON deserialization
                ps.setDouble(idx, ((Number) param.getValues().get(0)).doubleValue());
                break;
            case STRING:
                ps.setString(idx, (String) param.getValues().get(0));
                break;
            case LONG:
                // Use Number to handle JSON deserialization
                ps.setLong(idx, ((Number) param.getValues().get(0)).longValue());
                break;
            case BOOLEAN:
                ps.setBoolean(idx, (boolean) param.getValues().get(0));
                break;
            case BIG_DECIMAL:
                ps.setBigDecimal(idx, (BigDecimal) param.getValues().get(0));
                break;
            case FLOAT:
                // Use Number to handle JSON deserialization
                ps.setFloat(idx, ((Number) param.getValues().get(0)).floatValue());
                break;
            case BYTES:
                ps.setBytes(idx, (byte[]) param.getValues().get(0));
                break;
            case BYTE:
                ps.setByte(idx, ((byte[]) param.getValues().get(0))[0]);//Comes as an array of bytes with one element.
                break;
            case SHORT:
                // Use Number to handle JSON deserialization
                ps.setShort(idx, ((Number) param.getValues().get(0)).shortValue());
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
            //LOB types
            case BLOB:
                Object blobUUID = param.getValues().get(0);
                if (blobUUID == null) {
                    ps.setBlob(idx, (Blob) null);
                } else {
                    ps.setBlob(idx, sessionManager.<Blob>getLob(session, (String) blobUUID));
                }
                break;
            case CLOB: {
                Object clobUUID = param.getValues().get(0);
                if (clobUUID == null) {
                    ps.setBlob(idx, (Blob) null);
                } else {
                    ps.setBlob(idx, sessionManager.<Blob>getLob(session, (String) clobUUID));
                }
                Clob clob = sessionManager.getLob(session, (String) param.getValues().get(0));
                ps.setClob(idx, clob.getCharacterStream());
                break;
            }
            case BINARY_STREAM: {
                Object inputStreamValue = param.getValues().get(0);
                if (inputStreamValue == null) {
                    ps.setBinaryStream(idx, null);
                } else if (inputStreamValue instanceof byte[]) {
                    //DB2 require the full binary stream to be sent at once.
                    ps.setBinaryStream(idx, new ByteArrayInputStream((byte[]) inputStreamValue));
                } else {
                    InputStream is = (InputStream) inputStreamValue;
                    if (param.getValues().size() > 1) {
                        // Use Number to handle JSON deserialization (Long stays Long)
                        Long size = ((Number) param.getValues().get(1)).longValue();
                        ps.setBinaryStream(idx, is, size);
                    } else {
                        ps.setBinaryStream(idx, is);
                    }
                }
                break;
            }
            case NULL: {
                // Use Number to handle JSON deserialization (int stored as Long)
                int sqlType = ((Number) param.getValues().get(0)).intValue();
                ps.setNull(idx, sqlType);
                break;
            }
            default:
                ps.setObject(idx, param.getValues().get(0));
                break;
        }
    }
}