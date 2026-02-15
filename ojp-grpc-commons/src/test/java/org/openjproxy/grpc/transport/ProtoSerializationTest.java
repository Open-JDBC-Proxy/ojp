package org.openjproxy.grpc.transport;

import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.transport.ProtoSerialization.SerializationException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ProtoSerialization class.
 * Tests cover round-trip correctness, edge cases, and error handling.
 */
public class ProtoSerializationTest {

    // ==================== Properties Tests ====================

    @Test
    void testPropertiesRoundTrip() throws SerializationException {
        Properties props = new Properties();
        props.setProperty("key1", "value1");
        props.setProperty("key2", "value2");
        props.setProperty("key with spaces", "value with spaces");
        props.setProperty("unicode", "你好世界");

        byte[] bytes = ProtoSerialization.serializeToTransport(props);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        Properties result = ProtoSerialization.deserializeFromTransport(bytes, Properties.class);
        assertEquals("value1", result.getProperty("key1"));
        assertEquals("value2", result.getProperty("key2"));
        assertEquals("value with spaces", result.getProperty("key with spaces"));
        assertEquals("你好世界", result.getProperty("unicode"));
    }

    @Test
    void testEmptyProperties() throws SerializationException {
        Properties props = new Properties();

        byte[] bytes = ProtoSerialization.serializeToTransport(props);
        Properties result = ProtoSerialization.deserializeFromTransport(bytes, Properties.class);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ==================== Map Tests ====================

    @Test
    void testMapRoundTripWithPrimitives() throws SerializationException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("string", "hello");
        map.put("int", 42);
        map.put("double", 3.14);
        map.put("boolean", true);
        map.put("null", null);

        byte[] bytes = ProtoSerialization.serializeToTransport(map);
        Map<?, ?> result = deserializeMap(bytes);

        assertNotNull(result);
        assertEquals("hello", result.get("string"));
        assertEquals(42.0, result.get("int")); // Numbers are stored as doubles
        assertEquals(3.14, result.get("double"));
        assertTrue((Boolean) result.get("boolean"));
        assertNull(result.get("null"));
    }

    @Test
    void testNestedMaps() throws SerializationException {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("innerKey", "innerValue");
        inner.put("innerNumber", 123);

        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("outerKey", "outerValue");
        outer.put("nested", inner);

        byte[] bytes = ProtoSerialization.serializeToTransport(outer);
        Map<?, ?> result = deserializeMap(bytes);

        assertEquals("outerValue", result.get("outerKey"));
        Object nested = result.get("nested");
        assertInstanceOf(Map.class, nested);
        Map<?, ?> resultInner = (Map<?, ?>) nested;
        assertNotNull(resultInner);
        assertEquals("innerValue", resultInner.get("innerKey"));
        assertEquals(123.0, resultInner.get("innerNumber"));
    }

    @Test
    void testEmptyMap() throws SerializationException {
        Map<String, Object> map = new LinkedHashMap<>();

        byte[] bytes = ProtoSerialization.serializeToTransport(map);
        Map<?, ?> result = deserializeMap(bytes);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ==================== List Tests ====================

    @Test
    void testListRoundTripWithPrimitives() throws SerializationException {
        List<Object> list = new ArrayList<>();
        list.add("string");
        list.add(42);
        list.add(3.14);
        list.add(true);
        list.add(null);

        byte[] bytes = ProtoSerialization.serializeToTransport(list);
        List<?> result = deserializeList(bytes);

        assertNotNull(result);
        assertEquals(5, result.size());
        assertEquals("string", result.get(0));
        assertEquals(42.0, result.get(1)); // Numbers are stored as doubles
        assertEquals(3.14, result.get(2));
        assertTrue((Boolean) result.get(3));
        assertNull(result.get(4));
    }

    @Test
    void testNestedLists() throws SerializationException {
        List<Object> inner = new ArrayList<>();
        inner.add("innerValue");
        inner.add(123);

        List<Object> outer = new ArrayList<>();
        outer.add("outerValue");
        outer.add(inner);

        byte[] bytes = ProtoSerialization.serializeToTransport(outer);
        List<?> result = deserializeList(bytes);

        assertEquals(2, result.size());
        assertEquals("outerValue", result.get(0));
        Object nested = result.get(1);
        assertInstanceOf(List.class, nested);
        List<?> resultInner = (List<?>) nested;
        assertNotNull(resultInner);
        assertEquals(2, resultInner.size());
        assertEquals("innerValue", resultInner.get(0));
        assertEquals(123.0, resultInner.get(1));
    }

    @Test
    void testEmptyList() throws SerializationException {
        List<Object> list = new ArrayList<>();

        byte[] bytes = ProtoSerialization.serializeToTransport(list);
        List<?> result = deserializeList(bytes);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ==================== Nested Maps and Lists Tests ====================

    @Test
    void testMapWithNestedListsAndMaps() throws SerializationException {
        List<Object> list = new ArrayList<>();
        list.add("item1");
        list.add("item2");

        Map<String, Object> nestedMap = new LinkedHashMap<>();
        nestedMap.put("nestedKey", "nestedValue");

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("list", list);
        map.put("map", nestedMap);
        map.put("string", "value");

        byte[] bytes = ProtoSerialization.serializeToTransport(map);
        Map<?, ?> result = deserializeMap(bytes);

        Object listValue = result.get("list");
        assertInstanceOf(List.class, listValue);
        List<?> resultList = (List<?>) listValue;
        assertEquals(Arrays.asList("item1", "item2"), resultList);

        Object mapValue = result.get("map");
        assertInstanceOf(Map.class, mapValue);
        Map<?, ?> resultMap = (Map<?, ?>) mapValue;
        assertEquals("nestedValue", resultMap.get("nestedKey"));

        assertEquals("value", result.get("string"));
    }

    @Test
    void testListWithNestedMapsAndLists() throws SerializationException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", "value");

        List<Object> nestedList = new ArrayList<>();
        nestedList.add("nested1");
        nestedList.add("nested2");

        List<Object> list = new ArrayList<>();
        list.add(map);
        list.add(nestedList);
        list.add("simple");

        byte[] bytes = ProtoSerialization.serializeToTransport(list);
        List<?> result = deserializeList(bytes);

        assertEquals(3, result.size());

        Object mapValue = result.get(0);
        assertInstanceOf(Map.class, mapValue);
        Map<?, ?> resultMap = (Map<?, ?>) mapValue;
        assertEquals("value", resultMap.get("key"));

        Object listValue = result.get(1);
        assertInstanceOf(List.class, listValue);
        List<?> resultList = (List<?>) listValue;
        assertEquals(Arrays.asList("nested1", "nested2"), resultList);

        assertEquals("simple", result.get(2));
    }

    // ==================== Primitive Types Tests ====================

    @Test
    void testStringRoundTrip() throws SerializationException {
        String value = "hello world";
        byte[] bytes = ProtoSerialization.serializeToTransport(value);
        String result = ProtoSerialization.deserializeFromTransport(bytes, String.class);
        assertEquals(value, result);
    }

    @Test
    void testNumberRoundTrip() throws SerializationException {
        Integer value = 42;
        byte[] bytes = ProtoSerialization.serializeToTransport(value);
        Object result = ProtoSerialization.deserializeFromTransport(bytes);
        assertEquals(42.0, result); // Numbers are stored as doubles
    }

    @Test
    void testBooleanRoundTrip() throws SerializationException {
        Boolean value = true;
        byte[] bytes = ProtoSerialization.serializeToTransport(value);
        Boolean result = ProtoSerialization.deserializeFromTransport(bytes, Boolean.class);
        assertEquals(value, result);
    }

    @Test
    void testNullRoundTrip() throws SerializationException {
        byte[] bytes = ProtoSerialization.serializeToTransport(null);
        Object result = ProtoSerialization.deserializeFromTransport(bytes);
        assertNull(result);
    }

    // ==================== Error Cases Tests ====================

    @Test
    void testUnsupportedTypeThrowsException() {
        Object unsupportedObject = new Object(); // Plain POJO
        SerializationException exception = assertThrows(SerializationException.class, () -> {
            ProtoSerialization.serializeToTransport(unsupportedObject);
        });
        assertTrue(exception.getMessage().contains("Unsupported type"));
        assertTrue(exception.getMessage().contains("java.lang.Object"));
    }

    @Test
    void testUnsupportedNestedTypeThrowsException() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("valid", "value");
        map.put("invalid", new Object()); // POJO inside map

        SerializationException exception = assertThrows(SerializationException.class, () -> {
            ProtoSerialization.serializeToTransport(map);
        });
        assertTrue(exception.getMessage().contains("Unsupported type"));
    }

    @Test
    void testNonStringMapKeysThrowException() {
        Map<Integer, String> map = new LinkedHashMap<>();
        map.put(1, "value");

        SerializationException exception = assertThrows(SerializationException.class, () -> {
            ProtoSerialization.serializeToTransport(map);
        });
        assertTrue(exception.getMessage().contains("Map keys must be Strings"));
    }

    @Test
    void testInvalidProtobufBytesThrowException() {
        byte[] invalidBytes = new byte[]{0, 1, 2, 3, 4, 5}; // Random bytes

        SerializationException exception = assertThrows(SerializationException.class, () -> {
            ProtoSerialization.deserializeFromTransport(invalidBytes);
        });
        assertTrue(exception.getMessage().contains("Unable to deserialize"));
    }

    @Test
    void testNullPayloadThrowsException() {
        SerializationException exception = assertThrows(SerializationException.class, () -> {
            ProtoSerialization.deserializeFromTransport(null);
        });
        assertTrue(exception.getMessage().contains("null"));
    }

    @Test
    void testEmptyPayloadThrowsException() {
        byte[] emptyBytes = new byte[0];
        SerializationException exception = assertThrows(SerializationException.class, () -> {
            ProtoSerialization.deserializeFromTransport(emptyBytes);
        });
        assertTrue(exception.getMessage().contains("empty"));
    }

    @Test
    void testTypeMismatchThrowsException() throws SerializationException {
        String value = "hello";
        byte[] bytes = ProtoSerialization.serializeToTransport(value);

        SerializationException exception = assertThrows(SerializationException.class, () -> {
            ProtoSerialization.deserializeFromTransport(bytes, Integer.class);
        });
        assertTrue(exception.getMessage().contains("does not match expected type"));
    }

    // ==================== Edge Cases Tests ====================

    @Test
    void testUnicodeStrings() throws SerializationException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("chinese", "你好世界");
        map.put("arabic", "مرحبا بالعالم");
        map.put("emoji", "😀🎉");

        byte[] bytes = ProtoSerialization.serializeToTransport(map);
        Map<?, ?> result = deserializeMap(bytes);

        assertEquals("你好世界", result.get("chinese"));
        assertEquals("مرحبا بالعالم", result.get("arabic"));
        assertEquals("😀🎉", result.get("emoji"));
    }

    @Test
    void testKeysWithSpecialCharacters() throws SerializationException {
        Properties props = new Properties();
        props.setProperty("key with spaces", "value1");
        props.setProperty("key.with.dots", "value2");
        props.setProperty("key_with_underscores", "value3");
        props.setProperty("key-with-dashes", "value4");

        byte[] bytes = ProtoSerialization.serializeToTransport(props);
        Properties result = ProtoSerialization.deserializeFromTransport(bytes, Properties.class);

        assertEquals("value1", result.getProperty("key with spaces"));
        assertEquals("value2", result.getProperty("key.with.dots"));
        assertEquals("value3", result.getProperty("key_with_underscores"));
        assertEquals("value4", result.getProperty("key-with-dashes"));
    }

    @Test
    void testLargeDataStructure() throws SerializationException {
        Map<String, Object> largeMap = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            largeMap.put("key" + i, "value" + i);
        }

        byte[] bytes = ProtoSerialization.serializeToTransport(largeMap);
        Map<?, ?> result = deserializeMap(bytes);

        assertEquals(100, result.size());
        for (int i = 0; i < 100; i++) {
            assertEquals("value" + i, result.get("key" + i));
        }
    }

    @Test
    void testNumberPrecision() throws SerializationException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("int", 42);
        map.put("long", 9223372036854775807L);
        map.put("float", 3.14f);
        map.put("double", 2.718281828459045);

        byte[] bytes = ProtoSerialization.serializeToTransport(map);
        Map<?, ?> result = deserializeMap(bytes);

        // All numbers are returned as doubles
        assertEquals(42.0, result.get("int"));
        assertEquals(9.223372036854776E18, result.get("long")); // Note: may lose precision
        assertEquals(3.14, (Double) result.get("float"), 0.01);
        assertEquals(2.718281828459045, result.get("double"));
    }

    private static Map<?, ?> deserializeMap(byte[] bytes) throws SerializationException {
        Object result = ProtoSerialization.deserializeFromTransport(bytes, Map.class);
        assertNotNull(result);
        assertInstanceOf(Map.class, result);
        return (Map<?, ?>) result;
    }

    private static List<?> deserializeList(byte[] bytes) throws SerializationException {
        Object result = ProtoSerialization.deserializeFromTransport(bytes, List.class);
        assertNotNull(result);
        assertInstanceOf(List.class, result);
        return (List<?>) result;
    }
}
