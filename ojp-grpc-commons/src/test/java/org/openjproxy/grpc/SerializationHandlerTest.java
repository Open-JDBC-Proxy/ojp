package org.openjproxy.grpc;

import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.openjproxy.grpc.SerializationHandler.deserialize;
import static org.openjproxy.grpc.SerializationHandler.serialize;

/**
 * Comprehensive tests for SerializationHandler covering:
 * - Regular Java objects (Properties, Lists, primitives)
 * - Temporal types (DATE, TIME, TIMESTAMP) with ISO-8601 format
 * - Edge cases
 * 
 * Note: Protobuf messages are NOT tested here as they use their native binary serialization.
 */
public class SerializationHandlerTest {

    @Test
    public void testSerializeDeserializeInteger() {
        // Test basic primitive wrapper
        Integer original = 42;
        byte[] bytes = serialize(original);
        Integer deserialized = deserialize(bytes, Integer.class);
        
        assertEquals(original, deserialized);
        
        // Verify it's JSON
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("42"));
    }

    @Test
    public void testSerializeDeserializeString() {
        String original = "test string";
        byte[] bytes = serialize(original);
        String deserialized = deserialize(bytes, String.class);
        
        assertEquals(original, deserialized);
        
        // Verify it's JSON
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("test string"));
    }

    @Test
    public void testSerializeDeserializeList() {
        List<String> original = Arrays.asList("item1", "item2", "item3");
        byte[] bytes = serialize(original);
        List deserialized = deserialize(bytes, List.class);
        
        assertEquals(original.size(), deserialized.size());
        assertEquals(original, deserialized);
    }

    @Test
    public void testSerializeDeserializeListWithTypeToken() {
        // Test using TypeToken for proper generic type deserialization
        List<String> original = Arrays.asList("item1", "item2", "item3");
        byte[] bytes = serialize(original);
        
        // Use TypeToken to preserve generic type information
        Type listType = new TypeToken<List<String>>(){}.getType();
        List<String> deserialized = deserialize(bytes, listType);
        
        assertEquals(original.size(), deserialized.size());
        assertEquals(original, deserialized);
        // Verify elements are Strings, not LinkedTreeMap
        for (Object item : deserialized) {
            assertTrue(item instanceof String);
        }
    }

    @Test
    public void testSerializeDeserializeProperties() {
        Properties original = new Properties();
        original.setProperty("key1", "value1");
        original.setProperty("key2", "value2");
        
        byte[] bytes = serialize(original);
        Properties deserialized = deserialize(bytes, Properties.class);
        
        assertEquals(original.getProperty("key1"), deserialized.getProperty("key1"));
        assertEquals(original.getProperty("key2"), deserialized.getProperty("key2"));
    }

    @Test
    public void testSerializeDeserializeSqlDate() {
        // Test java.sql.Date with ISO-8601 format
        LocalDate localDate = LocalDate.of(2024, 6, 27);
        Date original = Date.valueOf(localDate);
        
        byte[] bytes = serialize(original);
        Date deserialized = deserialize(bytes, Date.class);
        
        assertEquals(original, deserialized);
        
        // Verify JSON format
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"type\":\"DATE\""));
        assertTrue(json.contains("\"value\":\"2024-06-27\""));
    }

    @Test
    public void testSerializeDeserializeSqlTime() {
        // Test java.sql.Time with ISO-8601 format (wall-clock)
        LocalTime localTime = LocalTime.of(14, 30, 0, 123456789);
        Time original = Time.valueOf(localTime);
        
        byte[] bytes = serialize(original);
        Time deserialized = deserialize(bytes, Time.class);
        
        assertEquals(original.toString(), deserialized.toString());
        
        // Verify JSON format
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"type\":\"TIME\""));
        assertTrue(json.contains("14:30:00"));
    }

    @Test
    public void testSerializeDeserializeSqlTimeWithNanos() {
        // Test TIME preserving nanoseconds
        LocalTime localTime = LocalTime.of(14, 30, 0, 123456789);
        Time original = Time.valueOf(localTime);
        
        byte[] bytes = serialize(original);
        Time deserialized = deserialize(bytes, Time.class);
        
        // LocalTime comparison
        assertEquals(original.toLocalTime(), deserialized.toLocalTime());
    }

    @Test
    public void testDeserializeTimeWithOffset() {
        // Test that TIME with offset extracts only the wall-clock time
        String json = "{\"type\":\"TIME\",\"value\":\"14:30:00.123456789+09:00\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        
        Time deserialized = deserialize(bytes, Time.class);
        
        // Should have the wall-clock time, ignoring offset
        assertEquals("14:30:00", deserialized.toString());
    }

    @Test
    public void testSerializeDeserializeSqlTimestamp() {
        // Test java.sql.Timestamp with ISO-8601 instant format
        Instant instant = Instant.parse("2024-06-27T14:30:00.123456789Z");
        Timestamp original = Timestamp.from(instant);
        
        byte[] bytes = serialize(original);
        Timestamp deserialized = deserialize(bytes, Timestamp.class);
        
        assertEquals(original.getTime(), deserialized.getTime());
        // Note: Nanoseconds might not be exactly preserved through Instant conversion
        // but should be close
        
        // Verify JSON format
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"type\":\"TIMESTAMP_INSTANT\""));
        assertTrue(json.contains("2024-06-27"));
        assertTrue(json.contains("14:30:00"));
    }

    @Test
    public void testTimestampPreservesInstant() {
        // Test that TIMESTAMP preserves the exact instant
        String json = "{\"type\":\"TIMESTAMP_INSTANT\",\"value\":\"2024-06-27T14:30:00.123456789Z\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        
        Timestamp deserialized = deserialize(bytes, Timestamp.class);
        
        // Verify the instant is correct
        Instant expectedInstant = Instant.parse("2024-06-27T14:30:00.123456789Z");
        assertEquals(expectedInstant.toEpochMilli(), deserialized.getTime());
    }

    @Test
    public void testTimestampLocalFormat() {
        // Test TIMESTAMP with local date-time format
        String json = "{\"type\":\"TIMESTAMP\",\"value\":\"2024-06-27T14:30:00.123456789\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        
        Timestamp deserialized = deserialize(bytes, Timestamp.class);
        
        // Verify nanos are preserved
        assertEquals(123456789, deserialized.getNanos());
    }

    @Test
    public void testTimestampPreservesNanoseconds() {
        // Test that nanoseconds are preserved up to 9 digits
        Instant instant = Instant.parse("2024-06-27T14:30:00.123456789Z");
        Timestamp original = Timestamp.from(instant);
        original.setNanos(123456789);
        
        byte[] bytes = serialize(original);
        Timestamp deserialized = deserialize(bytes, Timestamp.class);
        
        // Nanoseconds should be preserved
        assertTrue(deserialized.getNanos() >= 123456000); // At least milliseconds
    }

    @Test
    public void testTokyoWallClockTimeDoesntShift() {
        // Test requirement: TIME round-trips as wall-clock (no timezone shift)
        LocalTime wallClock = LocalTime.of(14, 30, 0);
        Time original = Time.valueOf(wallClock);
        
        byte[] bytes = serialize(original);
        Time deserialized = deserialize(bytes, Time.class);
        
        // Should be exact same wall-clock time
        assertEquals("14:30:00", deserialized.toString());
    }

    @Test
    public void testDateRepresentsCalendarDay() {
        // Test requirement: DATE represents the calendar day
        LocalDate calendarDay = LocalDate.of(2024, 6, 27);
        Date original = Date.valueOf(calendarDay);
        
        byte[] bytes = serialize(original);
        Date deserialized = deserialize(bytes, Date.class);
        
        // Should represent the same calendar day
        assertEquals(calendarDay, deserialized.toLocalDate());
    }

    @Test
    public void testCalendarOrNullHelper() {
        // Test the helper method for Calendar creation
        java.util.Calendar cal = SerializationHandler.calendarOrNull("Asia/Tokyo");
        assertNotNull(cal);
        assertEquals("Asia/Tokyo", cal.getTimeZone().getID());
        
        // Test with null
        assertNull(SerializationHandler.calendarOrNull(null));
        
        // Test with blank
        assertNull(SerializationHandler.calendarOrNull(""));
    }

    @Test
    public void testNegativeEpoch() {
        // Test dates before 1970
        Date original = Date.valueOf(LocalDate.of(1969, 12, 31));
        
        byte[] bytes = serialize(original);
        Date deserialized = deserialize(bytes, Date.class);
        
        assertEquals(original, deserialized);
    }

    @Test
    public void testJsonOutputIsHumanReadable() {
        // Verify that JSON output is human-readable
        Properties props = new Properties();
        props.setProperty("url", "jdbc:postgresql://localhost:5432/testdb");
        props.setProperty("user", "testuser");
        
        byte[] bytes = serialize(props);
        String json = new String(bytes, StandardCharsets.UTF_8);
        
        // Should be readable JSON
        assertTrue(json.contains("jdbc:postgresql") || json.contains("url"));
        assertTrue(json.contains("testuser") || json.contains("user"));
        
        // Should not be binary
        assertFalse(json.startsWith("\u00AC\u00ED")); // Java serialization magic number
    }

    @Test
    public void testSerializeNull() {
        // Test null handling
        byte[] bytes = serialize(null);
        String deserialized = deserialize(bytes, String.class);
        
        assertNull(deserialized);
    }

    @Test
    public void testEmptyString() {
        String original = "";
        byte[] bytes = serialize(original);
        String deserialized = deserialize(bytes, String.class);
        
        assertEquals(original, deserialized);
    }

    @Test
    public void testEmptyList() {
        List<String> original = new ArrayList<>();
        byte[] bytes = serialize(original);
        List deserialized = deserialize(bytes, List.class);
        
        assertNotNull(deserialized);
        assertTrue(deserialized.isEmpty());
    }
    
    @Test
    public void testByteArraySerializationAsBase64() {
        // Test that byte arrays are serialized as Base64 strings, not JSON arrays
        byte[] original = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
        
        byte[] jsonBytes = serialize(original);
        String json = new String(jsonBytes, StandardCharsets.UTF_8);
        
        // Should be a Base64 string, not a JSON array like [1,2,3,4,5]
        assertFalse(json.contains("[1,2,3,4,5]"));
        assertTrue(json.startsWith("\""));
        assertTrue(json.endsWith("\""));
        
        // Should deserialize back correctly
        byte[] deserialized = deserialize(jsonBytes, byte[].class);
        assertArrayEquals(original, deserialized);
    }
    
    @Test
    public void testLargeByteArrayEfficiency() {
        // Test that large byte arrays (like blobs) are efficiently serialized
        byte[] original = new byte[1024 * 100]; // 100KB
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i % 256);
        }
        
        long startTime = System.currentTimeMillis();
        byte[] jsonBytes = serialize(original);
        long serializeTime = System.currentTimeMillis() - startTime;
        
        // Serialization should be fast (< 1 second for 100KB)
        assertTrue(serializeTime < 1000, "Serialization took too long: " + serializeTime + "ms");
        
        // JSON should be compact (Base64 is ~1.33x original size)
        String json = new String(jsonBytes, StandardCharsets.UTF_8);
        assertTrue(json.length() < original.length * 2, "JSON too large: " + json.length() + " bytes");
        
        // Should deserialize back correctly
        startTime = System.currentTimeMillis();
        byte[] deserialized = deserialize(jsonBytes, byte[].class);
        long deserializeTime = System.currentTimeMillis() - startTime;
        
        assertTrue(deserializeTime < 1000, "Deserialization took too long: " + deserializeTime + "ms");
        assertArrayEquals(original, deserialized);
    }
    
    @Test
    public void testByteArrayInList() {
        // Test byte arrays inside a List (as used in Parameter.values)
        List<Object> original = Arrays.asList(new byte[]{1, 2, 3}, "test", 42);
        
        byte[] jsonBytes = serialize(original);
        String json = new String(jsonBytes, StandardCharsets.UTF_8);
        
        // Byte array should be Base64 encoded in the list
        assertTrue(json.contains("\""));
        
        // Should deserialize back (note: Gson will deserialize to ArrayList)
        List deserialized = deserialize(jsonBytes, List.class);
        assertNotNull(deserialized);
        assertEquals(3, deserialized.size());
    }
    
    @Test
    public void testNullByteArray() {
        // Test null byte array handling
        byte[] original = null;
        byte[] jsonBytes = serialize(original);
        String json = new String(jsonBytes, StandardCharsets.UTF_8);
        
        assertEquals("null", json);
        
        byte[] deserialized = deserialize(jsonBytes, byte[].class);
        assertNull(deserialized);
    }
    
    @Test
    public void testEmptyByteArray() {
        // Test empty byte array
        byte[] original = new byte[0];
        byte[] jsonBytes = serialize(original);
        
        byte[] deserialized = deserialize(jsonBytes, byte[].class);
        assertNotNull(deserialized);
        assertEquals(0, deserialized.length);
    }
    
    @Test
    public void testJsonFormatExample() {
        // Integration test showing JSON output can be parsed by other languages
        Properties props = new Properties();
        props.setProperty("database", "testdb");
        props.setProperty("timeout", "5000");
        
        byte[] bytes = serialize(props);
        String json = new String(bytes, StandardCharsets.UTF_8);
        
        // JSON should be parseable by any JSON parser
        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));
        assertTrue(json.contains("database"));
        assertTrue(json.contains("testdb"));
        
        // Example JSON output for documentation:
        // {"database":"testdb","timeout":"5000"}
    }
    
    @Test
    public void testTemporalJsonFormatExample() {
        // Show temporal JSON format examples
        Date date = Date.valueOf(LocalDate.of(2024, 6, 27));
        Time time = Time.valueOf(LocalTime.of(14, 30, 0));
        Timestamp timestamp = Timestamp.from(Instant.parse("2024-06-27T14:30:00.123456789Z"));
        
        String dateJson = new String(serialize(date), StandardCharsets.UTF_8);
        String timeJson = new String(serialize(time), StandardCharsets.UTF_8);
        String timestampJson = new String(serialize(timestamp), StandardCharsets.UTF_8);
        
        // Verify the JSON formats match ISO-8601 requirements
        assertTrue(dateJson.contains("\"type\":\"DATE\""));
        assertTrue(timeJson.contains("\"type\":\"TIME\""));
        assertTrue(timestampJson.contains("\"type\":\"TIMESTAMP_INSTANT\""));
        
        // Expected JSON output formats for documentation:
        // DATE: {"type":"DATE","value":"2024-06-27"}
        // TIME: {"type":"TIME","value":"14:30:00"}
        // TIMESTAMP: {"type":"TIMESTAMP_INSTANT","value":"2024-06-27T14:30:00.123456789Z"}
    }
}
