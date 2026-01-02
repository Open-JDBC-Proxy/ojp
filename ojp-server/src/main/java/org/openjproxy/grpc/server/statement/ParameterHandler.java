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
                ps.setInt(idx, (int) param.getValues().get(0));
                break;
            case SHORT:
                // Short comes as Integer from proto, needs to be cast
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
                if (param.getValues().get(0) instanceof byte[]) {
                    byte[] byteArray = (byte[]) param.getValues().get(0);
                    if (byteArray.length > 0) {
                        ps.setByte(idx, byteArray[0]);
                    } else {
                        ps.setByte(idx, (byte) 0);
                    }
                } else {
                    // Byte comes as Integer from proto, needs to be cast
                    ps.setByte(idx, ((Integer) param.getValues().get(0)).byteValue());
                }
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
                    ps.setClob(idx, (Clob) null);
                } else {
                    Clob clob = sessionManager.getLob(session, (String) clobUUID);
                    // H2 database has strict stream lifecycle management. Read the entire CLOB content
                    // into a String and set it using setString() to avoid "Stream setter is not yet closed" error
                    if (clob != null) {
                        try {
                            // Read the CLOB content using a Reader to ensure proper stream closure
                            long length = clob.length();
                            if (length > Integer.MAX_VALUE) {
                                throw new SQLException("CLOB too large: " + length + " characters");
                            }
                            char[] buffer = new char[(int) length];
                            try (java.io.Reader reader = clob.getCharacterStream()) {
                                int totalRead = 0;
                                int read;
                                while ((read = reader.read(buffer, totalRead, (int) length - totalRead)) != -1 && totalRead < length) {
                                    totalRead += read;
                                }
                                String clobContent = new String(buffer, 0, totalRead);
                                ps.setString(idx, clobContent);
                            }
                        } catch (java.io.IOException e) {
                            throw new SQLException("Failed to read CLOB content", e);
                        }
                    } else {
                        ps.setClob(idx, (Clob) null);
                    }
                }
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
                        Long size = (Long) param.getValues().get(1);
                        ps.setBinaryStream(idx, is, size);
                    } else {
                        ps.setBinaryStream(idx, is);
                    }
                }
                break;
            }
            case NULL: {
                int sqlType = (int) param.getValues().get(0);
                ps.setNull(idx, sqlType);
                break;
            }
            case URL: {
                // URL is now transmitted as java.net.URL object (string-based encoding in proto)
                Object urlValue = param.getValues().get(0);
                if (urlValue == null) {
                    ps.setURL(idx, null);
                } else {
                    ps.setURL(idx, (URL) urlValue);
                }
                break;
            }
            case ROW_ID: {
                // RowId is transmitted as opaque byte array (base64 in proto)
                // Cannot reconstruct java.sql.RowId from bytes - use setBytes instead
                // This matches JDBC behavior where RowId bytes are vendor-specific
                Object rowIdBytes = param.getValues().get(0);
                if (rowIdBytes == null) {
                    ps.setBytes(idx, null);
                } else {
                    ps.setBytes(idx, (byte[]) rowIdBytes);
                }
                break;
            }
            default:
                ps.setObject(idx, param.getValues().get(0));
                break;
        }
    }
}