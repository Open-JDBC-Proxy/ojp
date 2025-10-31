package org.openjproxy.grpc;

import com.google.gson.*;
import com.google.gson.internal.Streams;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
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
import java.util.*;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Handles serialization of Java objects to and from JSON byte arrays.
 * Protobuf Messages are NOT handled by this class - they use their native binary serialization.
 * Regular Java objects (Properties, Lists, primitives, temporal types) are serialized to JSON.
 * Temporal types (DATE, TIME, TIMESTAMP) are serialized using ISO-8601 format with metadata.
 * Byte arrays are serialized as Base64 strings for efficiency.
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

        // Use BIG_DECIMAL globally; we will coerce widths ourselves (Integer/Long/BigInteger).
        builder.setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL);

        // --- byte[] <-> Base64 (declared byte[] fields) ---
        builder.registerTypeAdapter(byte[].class, (JsonSerializer<byte[]>) (src, typeOfSrc, context) -> {
            if (src == null) return null;
            return new JsonPrimitive(Base64.getEncoder().encodeToString(src));
        });
        builder.registerTypeAdapter(byte[].class, (JsonDeserializer<byte[]>) (json, typeOfT, context) -> {
            if (json == null || json.isJsonNull()) return null;
            return Base64.getDecoder().decode(json.getAsString());
        });

        // --- java.sql.Date ---
        builder.registerTypeAdapter(Date.class, (JsonSerializer<Date>) (src, typeOfSrc, context) -> {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "DATE");
            obj.addProperty("value", src.toLocalDate().format(DATE_FMT));
            return obj;
        });
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

        // --- java.sql.Time ---
        builder.registerTypeAdapter(Time.class, (JsonSerializer<Time>) (src, typeOfSrc, context) -> {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "TIME");
            obj.addProperty("value", src.toLocalTime().format(TIME_FMT));
            return obj;
        });
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

        // --- java.sql.Timestamp ---
        builder.registerTypeAdapter(Timestamp.class, (JsonSerializer<Timestamp>) (src, typeOfSrc, context) -> {
            JsonObject obj = new JsonObject();
            // Prefer instant format (with timezone)
            obj.addProperty("type", "TIMESTAMP_INSTANT");
            Instant instant = src.toInstant();
            String value = TS_INSTANT_FMT.format(OffsetDateTime.ofInstant(instant, ZoneId.of("UTC")));
            obj.addProperty("value", value);
            return obj;
        });
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

        // --- UUID ---
        builder.registerTypeAdapter(UUID.class, (JsonSerializer<UUID>) (src, typeOfSrc, context) -> {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "UUID");
            obj.addProperty("value", src.toString());
            return obj;
        });
        builder.registerTypeAdapter(UUID.class, (JsonDeserializer<UUID>) (json, typeOfT, context) -> {
            if (json == null || json.isJsonNull()) return null;
            if (json.isJsonObject()) {
                JsonObject obj = json.getAsJsonObject();
                String type = obj.get("type").getAsString();
                if ("UUID".equals(type)) {
                    String value = obj.get("value").getAsString();
                    return UUID.fromString(value);
                }
            }
            // Fallback: try parsing as plain string (backward compatibility)
            return UUID.fromString(json.getAsString());
        });

        // Supply a custom adapter for Object via a Factory (Gson forbids direct registration for Object.class).
        builder.registerTypeAdapterFactory(new TaggedObjectTypeAdapterFactory());

        return builder.create();
    }

    /**
     * Serializes a Java object to JSON bytes (UTF-8).
     */
    public static byte[] serialize(Object t) {
        try {
            // If the root is an untyped container, explicitly tag it so elements retain types.
            if (t == null || t instanceof List || t instanceof Map || t.getClass() == Object.class) {
                String json = gson.toJson(tagTree(t));
                return json.getBytes(StandardCharsets.UTF_8);
            }
            // For POJOs and typed models, normal path (Object fields inside will still be tagged by our factory).
            String json = gson.toJson(t);
            return json.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize object: " + e.getMessage(), e);
        }
    }

    /**
     * Deserializes JSON bytes (UTF-8) back into a Java object of the specified type.
     */
    public static <T> T deserialize(byte[] byteArray, Class<T> type) {
        try {
            String json = new String(byteArray, StandardCharsets.UTF_8);

            // Route raw containers through untagTree so elements get correct widths & native arrays
            if (type == List.class || type == Map.class || type == Object.class) {
                JsonElement tree = JsonParser.parseString(json);
                @SuppressWarnings("unchecked")
                T value = (T) untagTree(tree);
                return value;
            }

            return gson.fromJson(json, type);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to deserialize object of type " + type.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Deserializes JSON bytes (UTF-8) back into a Java object of the specified generic type.
     */
    public static <T> T deserialize(byte[] byteArray, Type type) {
        try {
            String json = new String(byteArray, StandardCharsets.UTF_8);

            // If caller explicitly requests List<Object> or Map<String,Object>, do width-aware untagging.
            if (isListOfObject(type) || isMapOfStringObject(type) || type == Object.class) {
                JsonElement tree = JsonParser.parseString(json);
                @SuppressWarnings("unchecked")
                T value = (T) untagTree(tree);
                return value;
            }

            return gson.fromJson(json, type);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to deserialize object of type " + type.getTypeName() + ": " + e.getMessage(), e);
        }
    }

    private static boolean isListOfObject(Type t) {
        if (!(t instanceof ParameterizedType)) return false;
        ParameterizedType pt = (ParameterizedType) t;
        if (pt.getRawType() != List.class) return false;
        Type arg = pt.getActualTypeArguments()[0];
        return arg == Object.class;
    }

    private static boolean isMapOfStringObject(Type t) {
        if (!(t instanceof ParameterizedType)) return false;
        ParameterizedType pt = (ParameterizedType) t;
        if (pt.getRawType() != Map.class) return false;
        Type k = pt.getActualTypeArguments()[0];
        Type v = pt.getActualTypeArguments()[1];
        return k == String.class && v == Object.class;
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

    // --------------------------------------------------------------------------------------------
    // Object tagging: factory + adapter so POJO fields declared as Object (or List<Object>) round-trip.
    // --------------------------------------------------------------------------------------------

    private static final class TaggedObjectTypeAdapterFactory implements TypeAdapterFactory {
        @Override
        @SuppressWarnings("unchecked")
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            if (type.getRawType() != Object.class) return null;
            final TypeAdapter<Object> delegate = gson.getDelegateAdapter(this, TypeToken.get(Object.class));
            return (TypeAdapter<T>) new TaggedObjectTypeAdapter(gson, delegate);
        }
    }

    private static final class TaggedObjectTypeAdapter extends TypeAdapter<Object> {
        private final Gson gson;
        private final TypeAdapter<Object> delegate;

        TaggedObjectTypeAdapter(Gson gson, TypeAdapter<Object> delegate) {
            this.gson = gson;
            this.delegate = delegate;
        }

        @Override
        public void write(JsonWriter out, Object value) throws IOException {
            JsonElement tree = tagTree(value);
            Streams.write(tree, out);
        }

        @Override
        public Object read(JsonReader in) throws IOException {
            JsonElement tree = Streams.parse(in);
            return untagTree(tree);
        }
    }

    // --------------------------------------------------------------------------------------------
    // Helpers to tag/untag trees without relying on adapter dispatch for raw List/Map/Object roots.
    // --------------------------------------------------------------------------------------------

    private static JsonElement tagTree(Object src) {
        if (src == null) return JsonNull.INSTANCE;

        // BYTES
        if (src instanceof byte[]) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "BYTES");
            o.addProperty("value", Base64.getEncoder().encodeToString((byte[]) src));
            return o;
        }

        // Primitive arrays → tag with explicit array type + JSON array payload
        if (src.getClass().isArray()) {
            if (src instanceof int[]) {
                int[] a = (int[]) src;
                JsonArray arr = new JsonArray();
                for (int v : a) arr.add(new JsonPrimitive(v));
                JsonObject o = new JsonObject();
                o.addProperty("type", "INT_ARRAY");
                o.add("value", arr);
                return o;
            }
            if (src instanceof long[]) {
                long[] a = (long[]) src;
                JsonArray arr = new JsonArray();
                for (long v : a) arr.add(new JsonPrimitive(v));
                JsonObject o = new JsonObject();
                o.addProperty("type", "LONG_ARRAY");
                o.add("value", arr);
                return o;
            }
            if (src instanceof double[]) {
                double[] a = (double[]) src;
                JsonArray arr = new JsonArray();
                for (double v : a) arr.add(new JsonPrimitive(v));
                JsonObject o = new JsonObject();
                o.addProperty("type", "DOUBLE_ARRAY");
                o.add("value", arr);
                return o;
            }
            if (src instanceof short[]) {
                short[] a = (short[]) src;
                JsonArray arr = new JsonArray();
                for (short v : a) arr.add(new JsonPrimitive(v));
                JsonObject o = new JsonObject();
                o.addProperty("type", "SHORT_ARRAY");
                o.add("value", arr);
                return o;
            }
            if (src instanceof float[]) {
                float[] a = (float[]) src;
                JsonArray arr = new JsonArray();
                for (float v : a) arr.add(new JsonPrimitive(v));
                JsonObject o = new JsonObject();
                o.addProperty("type", "FLOAT_ARRAY");
                o.add("value", arr);
                return o;
            }
            if (src instanceof boolean[]) {
                boolean[] a = (boolean[]) src;
                JsonArray arr = new JsonArray();
                for (boolean v : a) arr.add(new JsonPrimitive(v));
                JsonObject o = new JsonObject();
                o.addProperty("type", "BOOLEAN_ARRAY");
                o.add("value", arr);
                return o;
            }
            if (src instanceof char[]) {
                char[] a = (char[]) src;
                JsonArray arr = new JsonArray();
                for (char v : a) arr.add(new JsonPrimitive(String.valueOf(v)));
                JsonObject o = new JsonObject();
                o.addProperty("type", "CHAR_ARRAY");
                o.add("value", arr);
                return o;
            }
            // NOTE: Object[] will fall through to POJO/default handling below.
        }

        // Temporal / UUID (reuse your existing adapters)
        if (src instanceof Date)      return gson.toJsonTree(src, Date.class);
        if (src instanceof Time)      return gson.toJsonTree(src, Time.class);
        if (src instanceof Timestamp) return gson.toJsonTree(src, Timestamp.class);
        if (src instanceof UUID)      return gson.toJsonTree(src, UUID.class);

        // Numbers → tagged scalar
        if (src instanceof Number) {
            String kind =
                    (src instanceof Integer)     ? "INT" :
                            (src instanceof Long)        ? "LONG" :
                                    (src instanceof Short)       ? "SHORT" :
                                            (src instanceof Byte)        ? "BYTE" :
                                                    (src instanceof Float)       ? "FLOAT" :
                                                            (src instanceof Double)      ? "DOUBLE" :
                                                                    (src instanceof BigInteger)  ? "BIG_INTEGER" :
                                                                            (src instanceof BigDecimal)  ? "BIG_DECIMAL" : "NUMBER";
            JsonObject o = new JsonObject();
            o.addProperty("type", kind);
            o.addProperty("value", src.toString());
            return o;
        }

        // Plain primitives
        if (src instanceof String)  return new JsonPrimitive((String) src);
        if (src instanceof Boolean) return new JsonPrimitive((Boolean) src);

        // Collections
        if (src instanceof Iterable<?>) {
            JsonArray a = new JsonArray();
            for (Object v : (Iterable<?>) src) a.add(tagTree(v));
            return a;
        }
        if (src instanceof Map<?, ?>) {
            JsonObject o = new JsonObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) src).entrySet()) {
                o.add(String.valueOf(e.getKey()), tagTree(e.getValue()));
            }
            return o;
        }

        // POJOs → default
        return gson.toJsonTree(src);
    }

    private static Object untagTree(JsonElement json) {
        if (json == null || json.isJsonNull()) return null;

        if (json.isJsonPrimitive()) {
            JsonPrimitive p = json.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isString())  return p.getAsString();
            if (p.isNumber()) {
                String s = p.getAsString();
                boolean fractional = s.indexOf('.') >= 0 || s.indexOf('e') >= 0 || s.indexOf('E') >= 0;
                if (fractional) {
                    return Double.valueOf(s);
                } else {
                    BigInteger bi = new BigInteger(s);
                    if (bi.bitLength() <= 31) return bi.intValue();   // Integer
                    if (bi.bitLength() <= 63) return bi.longValue();   // Long
                    return bi;                                         // BigInteger
                }
            }
        }

        if (json.isJsonArray()) {
            List<Object> out = new ArrayList<>();
            for (JsonElement e : json.getAsJsonArray()) out.add(untagTree(e));
            return out;
        }

        if (json.isJsonObject()) {
            JsonObject o = json.getAsJsonObject();
            if (o.size() >= 2 && o.has("type") && o.has("value")) {
                String type = o.get("type").getAsString();

                // Primitive array reconstructions
                switch (type) {
                    case "INT_ARRAY": {
                        JsonArray arr = o.getAsJsonArray("value");
                        int[] out = new int[arr.size()];
                        for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).getAsInt();
                        return out;
                    }
                    case "LONG_ARRAY": {
                        JsonArray arr = o.getAsJsonArray("value");
                        long[] out = new long[arr.size()];
                        for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).getAsLong();
                        return out;
                    }
                    case "DOUBLE_ARRAY": {
                        JsonArray arr = o.getAsJsonArray("value");
                        double[] out = new double[arr.size()];
                        for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).getAsDouble();
                        return out;
                    }
                    case "SHORT_ARRAY": {
                        JsonArray arr = o.getAsJsonArray("value");
                        short[] out = new short[arr.size()];
                        for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).getAsShort();
                        return out;
                    }
                    case "FLOAT_ARRAY": {
                        JsonArray arr = o.getAsJsonArray("value");
                        float[] out = new float[arr.size()];
                        for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).getAsFloat();
                        return out;
                    }
                    case "BOOLEAN_ARRAY": {
                        JsonArray arr = o.getAsJsonArray("value");
                        boolean[] out = new boolean[arr.size()];
                        for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).getAsBoolean();
                        return out;
                    }
                    case "CHAR_ARRAY": {
                        JsonArray arr = o.getAsJsonArray("value");
                        char[] out = new char[arr.size()];
                        for (int i = 0; i < arr.size(); i++) {
                            String s = arr.get(i).getAsString();
                            out[i] = (s != null && !s.isEmpty()) ? s.charAt(0) : '\0';
                        }
                        return out;
                    }
                }

                // Scalar/tagged values
                String value = o.get("value").getAsString();
                switch (type) {
                    case "BYTES":        return value; // return Base64 string, not decoded bytes
                    case "INT":          return Integer.valueOf(value);
                    case "LONG":         return Long.valueOf(value);
                    case "SHORT":        return Short.valueOf(value);
                    case "BYTE":         return Byte.valueOf(value);
                    case "FLOAT":        return Float.valueOf(value);
                    case "DOUBLE":       return Double.valueOf(value);
                    case "BIG_INTEGER":  return new BigInteger(value);
                    case "BIG_DECIMAL":  return new BigDecimal(value);
                    case "DATE": {
                        LocalDate ld = LocalDate.parse(value, DATE_FMT);
                        return Date.valueOf(ld);
                    }
                    case "TIME": {
                        LocalTime lt;
                        if (value.endsWith("Z")
                                || (value.contains("+") && value.length() > 8)
                                || (value.contains("-") && value.lastIndexOf('-') > 2)) {
                            lt = OffsetTime.parse(value).toLocalTime();
                        } else {
                            lt = LocalTime.parse(value, TIME_FMT);
                        }
                        return Time.valueOf(lt);
                    }
                    case "TIMESTAMP_INSTANT": {
                        Instant inst = OffsetDateTime.parse(value, TS_INSTANT_FMT).toInstant();
                        return Timestamp.from(inst);
                    }
                    case "TIMESTAMP": {
                        LocalDateTime ldt = LocalDateTime.parse(value, TS_LOCAL_FMT);
                        Timestamp ts = Timestamp.valueOf(ldt);
                        ts.setNanos(ldt.getNano());
                        return ts;
                    }
                    case "UUID": {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("type", "UUID");
                        m.put("value", value);
                        return m;
                    }
                    default: {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("type", type);
                        m.put("value", value);
                        return m;
                    }
                }
            }
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                map.put(e.getKey(), untagTree(e.getValue()));
            }
            return map;
        }

        throw new JsonParseException("Unsupported JSON: " + json);
    }
}
