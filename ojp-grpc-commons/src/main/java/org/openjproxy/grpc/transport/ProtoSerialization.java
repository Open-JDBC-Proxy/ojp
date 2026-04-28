package org.openjproxy.grpc.transport;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.NullValue;
import ojp.transport.v1.Array;
import ojp.transport.v1.Container;
import ojp.transport.v1.Object;
import ojp.transport.v1.Properties;
import ojp.transport.v1.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for serializing and deserializing Java objects (Maps, Lists, Properties)
 * to and from Protocol Buffer format.
 *
 * This replaces the legacy SerializationHandler which used Java native serialization.
 * Only supports simple data structures: Maps, Lists, Properties, and primitive types.
 * Does NOT support arbitrary Java objects/POJOs.
 */
public class ProtoSerialization {

    /**
     * Exception thrown when serialization or deserialization fails.
     */
    public static class SerializationException extends Exception {
        public SerializationException(String message) {
            super(message);
        }

        public SerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Serialize a Java object to Protocol Buffer bytes.
     *
     * Supported types:
     * - java.util.Map (with String keys and supported value types)
     * - java.util.List (with supported element types)
     * - java.util.Properties (String -> String map)
     * - String, Number (Integer, Long, Float, Double), Boolean
     * - null
     *
     * @param value The object to serialize
     * @return byte array containing the protobuf-encoded data
     * @throws SerializationException if the object type is not supported or serialization fails
     */
    public static byte[] serializeToTransport(java.lang.Object value) throws SerializationException {
        Container.Builder containerBuilder = Container.newBuilder();

        if (value == null) {
            // Serialize as Value with null_value set
            Value nullValue = Value.newBuilder()
                    .setNullValue(NullValue.NULL_VALUE)
                    .build();
            containerBuilder.setValue(nullValue);
        } else if (value instanceof java.util.Properties) {
            // Special case: Properties are String -> String maps
            Properties proto = propertiesToProto((java.util.Properties) value);
            containerBuilder.setProperties(proto);
        } else if (value instanceof Map) {
            // Generic Map<String, Object>
            Object proto = mapToProto((Map<?, ?>) value);
            containerBuilder.setObject(proto);
        } else if (value instanceof List) {
            // List<Object>
            Array proto = listToProto((List<?>) value);
            containerBuilder.setArray(proto);
        } else if (isPrimitiveType(value)) {
            // Primitive types wrapped in Value
            Value proto = primitiveToValue(value);
            containerBuilder.setValue(proto);
        } else {
            throw new SerializationException(
                    "Unsupported type for serialization: " + value.getClass().getName() +
                            ". Only Map, List, Properties, String, Number, Boolean, and null are supported.");
        }

        return containerBuilder.build().toByteArray();
    }

    /**
     * Deserialize Protocol Buffer bytes to a Java object.
     *
     * @param payload The protobuf-encoded bytes
     * @return The deserialized Java object (Map, List, Properties, or primitive)
     * @throws SerializationException if deserialization fails
     */
    public static java.lang.Object deserializeFromTransport(byte[] payload) throws SerializationException {
        if (payload == null) {
            throw new SerializationException("Cannot deserialize null payload");
        }

        if (payload.length == 0) {
            throw new SerializationException("Cannot deserialize empty payload");
        }

        try {
            Container container = Container.parseFrom(payload);

            switch (container.getContentCase()) {
                case VALUE:
                    return valueToJava(container.getValue());
                case OBJECT:
                    return protoToMap(container.getObject());
                case ARRAY:
                    return protoToList(container.getArray());
                case PROPERTIES:
                    return protoToProperties(container.getProperties());
                case CONTENT_NOT_SET:
                default:
                    throw new SerializationException("Container has no content set");
            }
        } catch (InvalidProtocolBufferException e) {
            throw new SerializationException("Unable to deserialize payload: not a valid protobuf message", e);
        }
    }

    /**
     * Deserialize Protocol Buffer bytes to a Java object with expected type.
     *
     * @param payload The protobuf-encoded bytes
     * @param expectedType The expected Java type
     * @return The deserialized Java object cast to the expected type
     * @throws SerializationException if deserialization fails or type doesn't match
     */
    @SuppressWarnings("unchecked")
    public static <T> T deserializeFromTransport(byte[] payload, Class<T> expectedType) throws SerializationException {
        java.lang.Object result = deserializeFromTransport(payload);

        if (result == null) {
            if (expectedType.isPrimitive()) {
                throw new SerializationException("Cannot deserialize null to primitive type " + expectedType.getName());
            }
            return null;
        }

        if (!expectedType.isInstance(result)) {
            throw new SerializationException(
                    "Deserialized object type " + result.getClass().getName() +
                            " does not match expected type " + expectedType.getName());
        }

        return (T) result;
    }

    // ==================== Helper Methods ====================

    private static boolean isPrimitiveType(java.lang.Object value) {
        return value instanceof String ||
               value instanceof Number ||
               value instanceof Boolean;
    }

    private static Value primitiveToValue(java.lang.Object value) {
        Value.Builder builder = Value.newBuilder();

        if (value instanceof String) {
            builder.setS((String) value);
        } else if (value instanceof Number) {
            // Convert all numbers to double
            builder.setN(((Number) value).doubleValue());
        } else if (value instanceof Boolean) {
            builder.setB((Boolean) value);
        }

        return builder.build();
    }

    private static Object mapToProto(Map<?, ?> map) throws SerializationException {
        Object.Builder builder = Object.newBuilder();

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new SerializationException(
                        "Map keys must be Strings, found: " +
                        (entry.getKey() != null ? entry.getKey().getClass().getName() : "null"));
            }

            String key = (String) entry.getKey();
            java.lang.Object value = entry.getValue();

            Value protoValue = javaToValue(value);
            builder.putEntries(key, protoValue);
        }

        return builder.build();
    }

    private static Array listToProto(List<?> list) throws SerializationException {
        Array.Builder builder = Array.newBuilder();

        for (java.lang.Object item : list) {
            Value protoValue = javaToValue(item);
            builder.addValues(protoValue);
        }

        return builder.build();
    }

    private static Properties propertiesToProto(java.util.Properties properties) {
        Properties.Builder builder = Properties.newBuilder();

        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key);
            if (value != null) {
                builder.putEntries(key, value);
            }
        }

        return builder.build();
    }

    private static Value javaToValue(java.lang.Object value) throws SerializationException {
        if (value == null) {
            return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        }

        if (value instanceof String) {
            return Value.newBuilder().setS((String) value).build();
        } else if (value instanceof Number) {
            return Value.newBuilder().setN(((Number) value).doubleValue()).build();
        } else if (value instanceof Boolean) {
            return Value.newBuilder().setB((Boolean) value).build();
        } else if (value instanceof Map) {
            Object obj = mapToProto((Map<?, ?>) value);
            return Value.newBuilder().setO(obj).build();
        } else if (value instanceof List) {
            Array arr = listToProto((List<?>) value);
            return Value.newBuilder().setA(arr).build();
        } else {
            throw new SerializationException(
                    "Unsupported type in nested structure: " + value.getClass().getName() +
                            ". Only Map, List, String, Number, Boolean, and null are supported.");
        }
    }

    private static java.lang.Object valueToJava(Value value) throws SerializationException {
        switch (value.getKindCase()) {
            case S:
                return value.getS();
            case N:
                return value.getN();
            case B:
                return value.getB();
            case O:
                return protoToMap(value.getO());
            case A:
                return protoToList(value.getA());
            case NULL_VALUE:
                return null;
            case KIND_NOT_SET:
            default:
                return null;
        }
    }

    private static Map<String, java.lang.Object> protoToMap(Object proto) throws SerializationException {
        Map<String, java.lang.Object> map = new LinkedHashMap<>();

        for (Map.Entry<String, Value> entry : proto.getEntriesMap().entrySet()) {
            java.lang.Object value = valueToJava(entry.getValue());
            map.put(entry.getKey(), value);
        }

        return map;
    }

    private static List<java.lang.Object> protoToList(Array proto) throws SerializationException {
        List<java.lang.Object> list = new ArrayList<>();

        for (Value value : proto.getValuesList()) {
            java.lang.Object item = valueToJava(value);
            list.add(item);
        }

        return list;
    }

    private static java.util.Properties protoToProperties(Properties proto) {
        java.util.Properties properties = new java.util.Properties();

        for (Map.Entry<String, String> entry : proto.getEntriesMap().entrySet()) {
            properties.setProperty(entry.getKey(), entry.getValue());
        }

        return properties;
    }
}
