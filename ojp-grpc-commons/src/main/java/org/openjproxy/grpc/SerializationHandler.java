package org.openjproxy.grpc;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.Type;
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
import java.util.Base64;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Handles serialization of Java objects to and from JSON byte arrays.
 * Protobuf Messages are NOT handled by this class - they use their native binary serialization.
 * Regular Java objects (Properties, Lists, primitives, temporal types) are serialized to JSON.
 * Temporal types (DATE, TIME, TIMESTAMP) are serialized using ISO-8601 format with metadata.
 * Byte arrays are serialized as Base64 strings for efficiency.
 * Number types (Integer, Long, etc.) are serialized with type metadata to preserve exact types.
 */
public class SerializationHandler {
    
    private static final Gson gson = createGson();
    
    // ISO-8601 formatters
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ISO_LOCAL_TIME;
    private static final DateTimeFormatter TS_LOCAL_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter TS_INSTANT_FMT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    
    /**
     * Custom TypeAdapterFactory that wraps primitive wrapper types with type information.
     */
    private static class TypePreservingAdapterFactory implements TypeAdapterFactory {
        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            Class<? super T> rawType = type.getRawType();
            
            // Only handle boxed primitive types
            if (rawType == Integer.class || rawType == Long.class || rawType == Short.class ||
                rawType == Byte.class || rawType == Float.class || rawType == Double.class) {
                
                @SuppressWarnings("unchecked")
                TypeAdapter<T> adapter = (TypeAdapter<T>) new TypeAdapter<Object>() {
                    @Override
                    public void write(JsonWriter out, Object value) throws IOException {
                        if (value == null) {
                            out.nullValue();
                            return;
                        }
                        out.beginObject();
                        out.name("@type").value(value.getClass().getSimpleName());
                        out.name("value");
                        if (value instanceof Integer) {
                            out.value((Integer) value);
                        } else if (value instanceof Long) {
                            out.value((Long) value);
                        } else if (value instanceof Short) {
                            out.value((Short) value);
                        } else if (value instanceof Byte) {
                            out.value((Byte) value);
                        } else if (value instanceof Float) {
                            out.value((Float) value);
                        } else if (value instanceof Double) {
                            out.value((Double) value);
                        }
                        out.endObject();
                    }
                    
                    @Override
                    public Object read(JsonReader in) throws IOException {
                        if (in.peek() == com.google.gson.stream.JsonToken.NULL) {
                            in.nextNull();
                            return null;
                        }
                        
                        // Handle both wrapped and unwrapped formats for backward compatibility
                        if (in.peek() == com.google.gson.stream.JsonToken.NUMBER) {
                            // Unwrapped number - determine type based on TypeToken
                            if (rawType == Integer.class) {
                                return in.nextInt();
                            } else if (rawType == Long.class) {
                                return in.nextLong();
                            } else if (rawType == Short.class) {
                                return (short) in.nextInt();
                            } else if (rawType == Byte.class) {
                                return (byte) in.nextInt();
                            } else if (rawType == Float.class) {
                                return (float) in.nextDouble();
                            } else if (rawType == Double.class) {
                                return in.nextDouble();
                            }
                        }
                        
                        // Wrapped format with type information
                        in.beginObject();
                        String typeName = null;
                        Number value = null;
                        
                        while (in.hasNext()) {
                            String name = in.nextName();
                            if ("@type".equals(name)) {
                                typeName = in.nextString();
                            } else if ("value".equals(name)) {
                                // Read as double first, we'll convert based on type
                                value = in.nextDouble();
                            }
                        }
                        in.endObject();
                        
                        if (typeName != null && value != null) {
                            switch (typeName) {
                                case "Integer":
                                    return value.intValue();
                                case "Long":
                                    return value.longValue();
                                case "Short":
                                    return value.shortValue();
                                case "Byte":
                                    return value.byteValue();
                                case "Float":
                                    return value.floatValue();
                                case "Double":
                                    return value.doubleValue();
                            }
                        }
                        
                        return value;
                    }
                };
                return adapter;
            }
            
            return null; // Let Gson handle other types normally
        }
    }
    
    /**
     * Custom TypeAdapterFactory that handles type-tagged values in Object fields.
     */
    private static class ObjectTypeAdapterFactory implements TypeAdapterFactory {
        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            if (type.getRawType() != Object.class) {
                return null;
            }
            
            final TypeAdapter<Object> delegate = gson.getDelegateAdapter(this, TypeToken.get(Object.class));
            final TypeAdapter<JsonElement> elementAdapter = gson.getAdapter(JsonElement.class);
            
            @SuppressWarnings("unchecked")
            TypeAdapter<T> result = (TypeAdapter<T>) new TypeAdapter<Object>() {
                @Override
                public void write(JsonWriter out, Object value) throws IOException {
                    delegate.write(out, value);
                }
                
                @Override
                public Object read(JsonReader in) throws IOException {
                    JsonElement element = elementAdapter.read(in);
                    
                    if (element.isJsonNull()) {
                        return null;
                    }
                    
                    if (element.isJsonPrimitive()) {
                        JsonPrimitive primitive = element.getAsJsonPrimitive();
                        if (primitive.isBoolean()) {
                            return primitive.getAsBoolean();
                        }
                        if (primitive.isNumber()) {
                            Number num = primitive.getAsNumber();
                            // Return Long for integers, Double for decimals
                            if (num.doubleValue() == num.longValue()) {
                                return num.longValue();
                            }
                            return num.doubleValue();
                        }
                        if (primitive.isString()) {
                            return primitive.getAsString();
                        }
                    }
                    
                    if (element.isJsonArray()) {
                        // Manually deserialize array elements to use our Object handler
                        com.google.gson.JsonArray array = element.getAsJsonArray();
                        java.util.List<Object> list = new java.util.ArrayList<>();
                        for (JsonElement item : array) {
                            // Recursively deserialize each element
                            Object itemValue;
                            if (item.isJsonPrimitive()) {
                                JsonPrimitive prim = item.getAsJsonPrimitive();
                                if (prim.isBoolean()) {
                                    itemValue = prim.getAsBoolean();
                                } else if (prim.isNumber()) {
                                    Number num = prim.getAsNumber();
                                    itemValue = (num.doubleValue() == num.longValue()) ? num.longValue() : num.doubleValue();
                                } else {
                                    itemValue = prim.getAsString();
                                }
                            } else if (item.isJsonObject()) {
                                // Recursively handle objects
                                JsonObject itemObj = item.getAsJsonObject();
                                if (itemObj.has("@type") && itemObj.has("value")) {
                                    String typeName = itemObj.get("@type").getAsString();
                                    JsonElement value = itemObj.get("value");
                                    switch (typeName) {
                                        case "Integer":
                                            itemValue = value.getAsInt();
                                            break;
                                        case "Long":
                                            itemValue = value.getAsLong();
                                            break;
                                        case "Short":
                                            itemValue = value.getAsShort();
                                            break;
                                        case "Byte":
                                            itemValue = value.getAsByte();
                                            break;
                                        case "Float":
                                            itemValue = value.getAsFloat();
                                            break;
                                        case "Double":
                                            itemValue = value.getAsDouble();
                                            break;
                                        default:
                                            itemValue = gson.fromJson(item, java.util.Map.class);
                                    }
                                } else {
                                    itemValue = gson.fromJson(item, java.util.Map.class);
                                }
                            } else if (item.isJsonArray()) {
                                itemValue = gson.fromJson(item, java.util.List.class);
                            } else {
                                itemValue = null;
                            }
                            list.add(itemValue);
                        }
                        return list;
                    }
                    
                    if (element.isJsonObject()) {
                        JsonObject obj = element.getAsJsonObject();
                        
                        // Check for type-tagged values
                        if (obj.has("@type") && obj.has("value")) {
                            String typeName = obj.get("@type").getAsString();
                            JsonElement value = obj.get("value");
                            
                            switch (typeName) {
                                case "Integer":
                                    return value.getAsInt();
                                case "Long":
                                    return value.getAsLong();
                                case "Short":
                                    return value.getAsShort();
                                case "Byte":
                                    return value.getAsByte();
                                case "Float":
                                    return value.getAsFloat();
                                case "Double":
                                    return value.getAsDouble();
                            }
                        }
                        
                        // Check for temporal types
                        if (obj.has("type")) {
                            String typeName = obj.get("type").getAsString();
                            switch (typeName) {
                                case "DATE":
                                    return Date.valueOf(LocalDate.parse(obj.get("value").getAsString(), DATE_FMT));
                                case "TIME":
                                    String timeValue = obj.get("value").getAsString();
                                    if (timeValue.contains("+") || timeValue.contains("Z") || 
                                        (timeValue.contains("-") && timeValue.length() > 8)) {
                                        OffsetTime ot = OffsetTime.parse(timeValue);
                                        return Time.valueOf(ot.toLocalTime());
                                    } else {
                                        LocalTime lt = LocalTime.parse(timeValue, TIME_FMT);
                                        return Time.valueOf(lt);
                                    }
                                case "TIMESTAMP":
                                    LocalDateTime ldt = LocalDateTime.parse(obj.get("value").getAsString(), TS_LOCAL_FMT);
                                    Timestamp ts = Timestamp.valueOf(ldt);
                                    ts.setNanos(ldt.getNano());
                                    return ts;
                                case "TIMESTAMP_INSTANT":
                                    Instant instant = OffsetDateTime.parse(obj.get("value").getAsString(), TS_INSTANT_FMT).toInstant();
                                    return Timestamp.from(instant);
                            }
                        }
                        
                        // Default: return as Map
                        return gson.fromJson(element, java.util.Map.class);
                    }
                    
                    return null;
                }
            };
            
            return result;
        }
    }
    
    private static Gson createGson() {
        GsonBuilder builder = new GsonBuilder();
        
        // Register custom Object adapter factory to handle type-tagged values
        builder.registerTypeAdapterFactory(new ObjectTypeAdapterFactory());
        
        // Register our custom factory for type-preserving number serialization
        builder.registerTypeAdapterFactory(new TypePreservingAdapterFactory());
        
        // Use LONG_OR_DOUBLE as fallback for untyped numbers
        builder.setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE);
        
        // Custom serializer for byte arrays - use Base64 to avoid huge JSON arrays
        builder.registerTypeAdapter(byte[].class, (JsonSerializer<byte[]>) (src, typeOfSrc, context) -> {
            if (src == null) {
                return null;
            }
            // Encode as Base64 string for efficient serialization of large binary data
            return new JsonPrimitive(Base64.getEncoder().encodeToString(src));
        });
        
        // Custom deserializer for byte arrays
        builder.registerTypeAdapter(byte[].class, (JsonDeserializer<byte[]>) (json, typeOfT, context) -> {
            if (json == null || json.isJsonNull()) {
                return null;
            }
            // Decode from Base64 string
            return Base64.getDecoder().decode(json.getAsString());
        });
        
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
     * Deserializes JSON bytes (UTF-8) back into a Java object of the specified type.
     * This method supports generic types like List&lt;Parameter&gt; using Java's Type system.
     * 
     * Example usage:
     * <pre>
     * Type listType = new TypeToken&lt;List&lt;Parameter&gt;&gt;(){}.getType();
     * List&lt;Parameter&gt; params = deserialize(bytes, listType);
     * </pre>
     * 
     * @param byteArray the JSON bytes to deserialize
     * @param type the Type representing the target type (use TypeToken for generics)
     * @param <T> the type parameter
     * @return the deserialized object
     * @throws RuntimeException if deserialization fails
     */
    public static <T> T deserialize(byte[] byteArray, Type type) {
        try {
            String json = new String(byteArray, StandardCharsets.UTF_8);
            return gson.fromJson(json, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize object of type " + type.getTypeName() + ": " + e.getMessage(), e);
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
