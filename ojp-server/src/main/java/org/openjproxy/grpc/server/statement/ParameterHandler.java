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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
                Object bigDecValue = param.getValues().get(0);
                if (bigDecValue instanceof BigDecimal) {
                    ps.setBigDecimal(idx, (BigDecimal) bigDecValue);
                } else if (bigDecValue instanceof Number) {
                    // JSON deserialization returns Long or Double, convert to BigDecimal
                    ps.setBigDecimal(idx, BigDecimal.valueOf(((Number) bigDecValue).doubleValue()));
                } else {
                    ps.setBigDecimal(idx, (BigDecimal) bigDecValue);
                }
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
                ps.setDate(idx, convertToDate(param.getValues().get(0)));
                break;
            case TIME:
                ps.setTime(idx, convertToTime(param.getValues().get(0)));
                break;
            case TIMESTAMP:
                ps.setTimestamp(idx, convertToTimestamp(param.getValues().get(0)));
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
                } else if (inputStreamValue instanceof String) {
                    // JSON deserialization: byte[] was serialized as Base64 string
                    // Decode it back to byte[] and create ByteArrayInputStream
                    byte[] bytes = Base64.getDecoder().decode((String) inputStreamValue);
                    ps.setBinaryStream(idx, new ByteArrayInputStream(bytes));
                } else {
                    // Assume it's an InputStream (should not happen with JSON serialization)
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
                // Handle UUID conversion from JSON-deserialized Map
                Object value = param.getValues().get(0);
                ps.setObject(idx, convertToUUID(value));
                break;
        }
    }

    /**
     * Helper method to convert JSON-deserialized temporal objects (LinkedTreeMap) to java.sql.Date
     */
    @SuppressWarnings("unchecked")
    private static Date convertToDate(Object value) {
        if (value instanceof Date) {
            return (Date) value;
        }
        if (value instanceof Timestamp) {
            return new Date(((Timestamp) value).getTime());
        }
        if (value instanceof Map) {
            // JSON deserialization: temporal types come as LinkedTreeMap
            Map<String, Object> map = (Map<String, Object>) value;
            String type = (String) map.get("type");
            String valueStr = (String) map.get("value");
            if ("DATE".equals(type)) {
                LocalDate ld = LocalDate.parse(valueStr, DateTimeFormatter.ISO_LOCAL_DATE);
                return Date.valueOf(ld);
            }
        }
        return (Date) value;
    }

    /**
     * Helper method to convert JSON-deserialized temporal objects (LinkedTreeMap) to java.sql.Time
     */
    @SuppressWarnings("unchecked")
    private static Time convertToTime(Object value) {
        if (value instanceof Time) {
            return (Time) value;
        }
        if (value instanceof Map) {
            // JSON deserialization: temporal types come as LinkedTreeMap
            Map<String, Object> map = (Map<String, Object>) value;
            String type = (String) map.get("type");
            String valueStr = (String) map.get("value");
            if ("TIME".equals(type)) {
                LocalTime lt;
                // Handle TIME with offset (extract local time only - wall clock semantics)
                if (valueStr.endsWith("Z") || (valueStr.contains("+") && valueStr.length() > 8) || 
                    (valueStr.contains("-") && valueStr.lastIndexOf('-') > 2)) {
                    OffsetTime ot = OffsetTime.parse(valueStr);
                    lt = ot.toLocalTime();
                } else {
                    lt = LocalTime.parse(valueStr, DateTimeFormatter.ISO_LOCAL_TIME);
                }
                return Time.valueOf(lt);
            }
        }
        return (Time) value;
    }

    /**
     * Helper method to convert JSON-deserialized temporal objects (LinkedTreeMap) to java.sql.Timestamp
     */
    @SuppressWarnings("unchecked")
    private static Timestamp convertToTimestamp(Object value) {
        if (value instanceof Timestamp) {
            return (Timestamp) value;
        }
        if (value instanceof Map) {
            // JSON deserialization: temporal types come as LinkedTreeMap
            Map<String, Object> map = (Map<String, Object>) value;
            String type = (String) map.get("type");
            String valueStr = (String) map.get("value");
            
            if ("TIMESTAMP_INSTANT".equals(type)) {
                // Parse as instant with nanosecond precision
                Instant inst = OffsetDateTime.parse(valueStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
                return Timestamp.from(inst);
            } else if ("TIMESTAMP".equals(type)) {
                // Parse as local date-time
                LocalDateTime ldt = LocalDateTime.parse(valueStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                return Timestamp.valueOf(ldt);
            }
        }
        return (Timestamp) value;
    }
    
    /**
     * Helper method to convert JSON-deserialized UUID objects (LinkedTreeMap) to java.util.UUID
     */
    @SuppressWarnings("unchecked")
    private static Object convertToUUID(Object value) {
        if (value instanceof UUID) {
            return value;
        }
        if (value instanceof String) {
            // Try to parse as UUID (in case it was serialized as plain string)
            try {
                return UUID.fromString((String) value);
            } catch (IllegalArgumentException e) {
                // Not a UUID, return as is
                return value;
            }
        }
        if (value instanceof Map) {
            // JSON deserialization: {"type":"UUID","value":"..."}
            Map<String, Object> map = (Map<String, Object>) value;
            String type = (String) map.get("type");
            if ("UUID".equals(type)) {
                String valueStr = (String) map.get("value");
                return UUID.fromString(valueStr);
            }
        }
        // Return as is if not a UUID
        return value;
    }
}