package org.openjproxy.grpc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializer;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Handles serialization of Java objects to and from JSON byte arrays.
 * Protobuf Messages are NOT handled by this class - they use their native binary serialization.
 * Regular Java objects (Properties, Lists, primitives, temporal types) are serialized to JSON.
 * Temporal types (DATE, TIME, TIMESTAMP) are serialized using ISO-8601 format with metadata.
 */
public class SerializationHandler {
    
    private static final Gson gson = createGson();
    
    // ISO-8601 formatters
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ISO_LOCAL_TIME;
    private static final DateTimeFormatter TS_LOCAL_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter TS_INSTANT_FMT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    
    private static Gson createGson() {
        GsonBuilder builder = new GsonBuilder();
        
        // Custom serializer for java.sql.Date
        builder.registerTypeAdapter(Date.class, (JsonSerializer<Date>) (src, typeOfSrc, context) -> {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "DATE");
            obj.addProperty("value", src.toLocalDate().format(DATE_FMT));
            return obj;
        });
        
        // Custom deserializer for java.sql.Date
        builder.registerTypeAdapter(Date.class, (JsonDeserializer<Date>) (json, typeOfT, context) -> {
            if (json.isJsonObject()) {
                JsonObject obj = json.getAsJsonObject();
                String type = obj.get("type").getAsString();
                String value = obj.get("value").getAsString();
                
                if ("DATE".equals(type)) {
                    LocalDate ld = LocalDate.parse(value, DATE_FMT);
                    return Date.valueOf(ld);
                }
            }
            throw new IllegalArgumentException("Invalid DATE JSON format");
        });
        
        // Custom serializer for java.sql.Time
        builder.registerTypeAdapter(Time.class, (JsonSerializer<Time>) (src, typeOfSrc, context) -> {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "TIME");
            obj.addProperty("value", src.toLocalTime().format(TIME_FMT));
            return obj;
        });
        
        // Custom deserializer for java.sql.Time
        builder.registerTypeAdapter(Time.class, (JsonDeserializer<Time>) (json, typeOfT, context) -> {
            if (json.isJsonObject()) {
                JsonObject obj = json.getAsJsonObject();
                String type = obj.get("type").getAsString();
                String value = obj.get("value").getAsString();
                
                if ("TIME".equals(type)) {
                    LocalTime lt;
                    // Handle TIME with offset (extract local time only)
                    if (value.endsWith("Z") || (value.contains("+") && value.length() > 8) || 
                        (value.contains("-") && value.lastIndexOf('-') > 2)) {
                        OffsetTime ot = OffsetTime.parse(value);
                        lt = ot.toLocalTime();
                    } else {
                        lt = LocalTime.parse(value, TIME_FMT);
                    }
                    return Time.valueOf(lt);
                }
            }
            throw new IllegalArgumentException("Invalid TIME JSON format");
        });
        
        // Custom serializer for java.sql.Timestamp
        builder.registerTypeAdapter(Timestamp.class, (JsonSerializer<Timestamp>) (src, typeOfSrc, context) -> {
            JsonObject obj = new JsonObject();
            // Prefer instant format (with timezone)
            obj.addProperty("type", "TIMESTAMP_INSTANT");
            Instant instant = src.toInstant();
            String value = TS_INSTANT_FMT.format(OffsetDateTime.ofInstant(instant, ZoneId.of("UTC")));
            obj.addProperty("value", value);
            return obj;
        });
        
        // Custom deserializer for java.sql.Timestamp
        builder.registerTypeAdapter(Timestamp.class, (JsonDeserializer<Timestamp>) (json, typeOfT, context) -> {
            if (json.isJsonObject()) {
                JsonObject obj = json.getAsJsonObject();
                String type = obj.get("type").getAsString();
                String value = obj.get("value").getAsString();
                
                if ("TIMESTAMP_INSTANT".equals(type)) {
                    // Parse as instant
                    Instant inst = OffsetDateTime.parse(value, TS_INSTANT_FMT).toInstant();
                    return Timestamp.from(inst);
                } else if ("TIMESTAMP".equals(type)) {
                    // Parse as local date-time
                    LocalDateTime ldt = LocalDateTime.parse(value, TS_LOCAL_FMT);
                    Timestamp ts = Timestamp.valueOf(ldt);
                    // Preserve nanos if present
                    ts.setNanos(ldt.getNano());
                    return ts;
                }
            }
            throw new IllegalArgumentException("Invalid TIMESTAMP JSON format");
        });
        
        return builder.create();
    }
    
    /**
     * Serializes a Java object to JSON bytes (UTF-8).
     * Note: Protobuf Messages should NOT be passed to this method - they use their own binary serialization.
     * 
     * @param t the Java object to serialize (Properties, List, primitives, temporal types, etc.)
     * @return JSON bytes in UTF-8 encoding
     * @throws RuntimeException if serialization fails
     */
    public static byte[] serialize(Object t) {
        try {
            String json = gson.toJson(t);
            return json.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize object: " + e.getMessage(), e);
        }
    }
    
    /**
     * Deserializes JSON bytes (UTF-8) back into a Java object of the specified type.
     * Note: Protobuf Messages should NOT be deserialized with this method - they use their own binary deserialization.
     * 
     * @param byteArray the JSON bytes to deserialize
     * @param type the class type to deserialize into
     * @param <T> the type parameter
     * @return the deserialized object
     * @throws RuntimeException if deserialization fails
     */
    public static <T> T deserialize(byte[] byteArray, Class<T> type) {
        try {
            String json = new String(byteArray, StandardCharsets.UTF_8);
            return gson.fromJson(json, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize object of type " + type.getName() + ": " + e.getMessage(), e);
        }
    }
    
    /**
     * Creates a Calendar from a zone ID string, or returns null if zone ID is null/blank.
     * Used for JDBC temporal operations that require a Calendar parameter.
     * 
     * @param zoneId the IANA zone ID (e.g., "Asia/Tokyo")
     * @return Calendar instance or null
     */
    public static Calendar calendarOrNull(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return null;
        }
        return Calendar.getInstance(TimeZone.getTimeZone(zoneId));
    }
}
