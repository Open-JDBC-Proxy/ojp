package org.openjproxy.grpc;

import com.google.protobuf.StringValue;
import org.junit.jupiter.api.Test;

import java.net.MalformedURLException;
import java.net.URL;
import java.sql.RowId;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ProtoTypeConverters to verify proper handling of UUID, URL, and RowId
 * conversions with null vs empty string semantics.
 */
class ProtoTypeConvertersTest {

    // ===== UUID Tests =====

    @Test
    void testUuidToProtoNull() {
        Optional<StringValue> result = ProtoTypeConverters.uuidToProto(null);
        assertFalse(result.isPresent(), "Null UUID should result in absent Optional");
    }

    @Test
    void testUuidToProtoValidUuid() {
        UUID uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        Optional<StringValue> result = ProtoTypeConverters.uuidToProto(uuid);
        
        assertTrue(result.isPresent(), "Valid UUID should result in present Optional");
        assertEquals("550e8400-e29b-41d4-a716-446655440000", result.get().getValue());
    }

    @Test
    void testUuidFromProtoNull() {
        UUID result = ProtoTypeConverters.uuidFromProto(null);
        assertNull(result, "Null StringValue should result in null UUID");
    }

    @Test
    void testUuidFromProtoValidUuid() {
        StringValue wrapper = StringValue.of("550e8400-e29b-41d4-a716-446655440000");
        UUID result = ProtoTypeConverters.uuidFromProto(wrapper);
        
        assertNotNull(result);
        assertEquals("550e8400-e29b-41d4-a716-446655440000", result.toString());
    }

    @Test
    void testUuidFromProtoEmptyString() {
        StringValue wrapper = StringValue.of("");
        assertThrows(IllegalArgumentException.class, () -> {
            ProtoTypeConverters.uuidFromProto(wrapper);
        }, "Empty string should throw IllegalArgumentException");
    }

    @Test
    void testUuidFromProtoInvalidFormat() {
        StringValue wrapper = StringValue.of("not-a-uuid");
        assertThrows(IllegalArgumentException.class, () -> {
            ProtoTypeConverters.uuidFromProto(wrapper);
        }, "Invalid UUID format should throw IllegalArgumentException");
    }

    @Test
    void testUuidRoundtrip() {
        UUID original = UUID.randomUUID();
        Optional<StringValue> wrapper = ProtoTypeConverters.uuidToProto(original);
        UUID result = ProtoTypeConverters.uuidFromProto(wrapper.orElse(null));
        
        assertEquals(original, result, "UUID should survive roundtrip conversion");
    }

    // ===== URL Tests =====

    @Test
    void testUrlToProtoNull() {
        Optional<StringValue> result = ProtoTypeConverters.urlToProto(null);
        assertFalse(result.isPresent(), "Null URL should result in absent Optional");
    }

    @Test
    void testUrlToProtoValidUrl() throws MalformedURLException {
        URL url = new URL("https://example.com:8080/path?query=value#fragment");
        Optional<StringValue> result = ProtoTypeConverters.urlToProto(url);
        
        assertTrue(result.isPresent(), "Valid URL should result in present Optional");
        assertEquals("https://example.com:8080/path?query=value#fragment", result.get().getValue());
    }

    @Test
    void testUrlFromProtoNull() {
        URL result = ProtoTypeConverters.urlFromProto(null);
        assertNull(result, "Null StringValue should result in null URL");
    }

    @Test
    void testUrlFromProtoValidUrl() {
        StringValue wrapper = StringValue.of("https://example.com/path");
        URL result = ProtoTypeConverters.urlFromProto(wrapper);
        
        assertNotNull(result);
        assertEquals("https://example.com/path", result.toString());
    }

    @Test
    void testUrlFromProtoInvalidUrl() {
        StringValue wrapper = StringValue.of("not a valid url");
        assertThrows(IllegalArgumentException.class, () -> {
            ProtoTypeConverters.urlFromProto(wrapper);
        }, "Invalid URL format should throw IllegalArgumentException");
    }

    @Test
    void testUrlFromProtoEmptyString() {
        StringValue wrapper = StringValue.of("");
        assertThrows(IllegalArgumentException.class, () -> {
            ProtoTypeConverters.urlFromProto(wrapper);
        }, "Empty string should throw MalformedURLException wrapped in IllegalArgumentException");
    }

    @Test
    void testUrlRoundtrip() throws MalformedURLException {
        URL original = new URL("https://example.com:443/secure/path?param=value");
        Optional<StringValue> wrapper = ProtoTypeConverters.urlToProto(original);
        URL result = ProtoTypeConverters.urlFromProto(wrapper.orElse(null));
        
        assertEquals(original.toString(), result.toString(), "URL should survive roundtrip conversion");
    }

    // ===== RowId Tests =====

    /**
     * Mock implementation of RowId for testing purposes.
     */
    private static class MockRowId implements RowId {
        private final byte[] bytes;

        public MockRowId(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public byte[] getBytes() {
            return bytes;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof RowId)) return false;
            return java.util.Arrays.equals(bytes, ((RowId) obj).getBytes());
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "MockRowId[" + java.util.Arrays.toString(bytes) + "]";
        }
    }

    @Test
    void testRowIdToProtoNull() {
        Optional<StringValue> result = ProtoTypeConverters.rowIdToProto(null);
        assertFalse(result.isPresent(), "Null RowId should result in absent Optional");
    }

    @Test
    void testRowIdToProtoValidRowId() {
        byte[] rowIdBytes = new byte[]{1, 2, 3, 4, 5};
        RowId rowId = new MockRowId(rowIdBytes);
        Optional<StringValue> result = ProtoTypeConverters.rowIdToProto(rowId);
        
        assertTrue(result.isPresent(), "Valid RowId should result in present Optional");
        String expectedBase64 = Base64.getEncoder().encodeToString(rowIdBytes);
        assertEquals(expectedBase64, result.get().getValue());
    }

    @Test
    void testRowIdToProtoEmptyBytes() {
        byte[] rowIdBytes = new byte[0];
        RowId rowId = new MockRowId(rowIdBytes);
        Optional<StringValue> result = ProtoTypeConverters.rowIdToProto(rowId);
        
        assertTrue(result.isPresent(), "RowId with empty bytes should result in present Optional");
        assertEquals("", result.get().getValue(), "Empty bytes should encode to empty string");
    }

    @Test
    void testRowIdBytesFromProtoNull() {
        byte[] result = ProtoTypeConverters.rowIdBytesFromProto(null);
        assertNull(result, "Null StringValue should result in null bytes");
    }

    @Test
    void testRowIdBytesFromProtoValidBase64() {
        byte[] originalBytes = new byte[]{1, 2, 3, 4, 5};
        String base64 = Base64.getEncoder().encodeToString(originalBytes);
        StringValue wrapper = StringValue.of(base64);
        
        byte[] result = ProtoTypeConverters.rowIdBytesFromProto(wrapper);
        
        assertNotNull(result);
        assertArrayEquals(originalBytes, result);
    }

    @Test
    void testRowIdBytesFromProtoEmptyString() {
        StringValue wrapper = StringValue.of("");
        byte[] result = ProtoTypeConverters.rowIdBytesFromProto(wrapper);
        
        assertNotNull(result);
        assertEquals(0, result.length, "Empty string should decode to empty byte array");
    }

    @Test
    void testRowIdBytesFromProtoInvalidBase64() {
        StringValue wrapper = StringValue.of("not-valid-base64!");
        assertThrows(IllegalArgumentException.class, () -> {
            ProtoTypeConverters.rowIdBytesFromProto(wrapper);
        }, "Invalid Base64 should throw IllegalArgumentException");
    }

    @Test
    void testRowIdRoundtrip() {
        byte[] originalBytes = new byte[]{10, 20, 30, 40, 50, 60, 70, 80};
        RowId original = new MockRowId(originalBytes);
        
        Optional<StringValue> wrapper = ProtoTypeConverters.rowIdToProto(original);
        byte[] resultBytes = ProtoTypeConverters.rowIdBytesFromProto(wrapper.orElse(null));
        
        assertArrayEquals(originalBytes, resultBytes, "RowId bytes should survive roundtrip conversion");
    }

    @Test
    void testRowIdRoundtripLargeBytes() {
        // Test with larger byte array to ensure Base64 encoding handles it
        byte[] originalBytes = new byte[256];
        for (int i = 0; i < 256; i++) {
            originalBytes[i] = (byte) i;
        }
        RowId original = new MockRowId(originalBytes);
        
        Optional<StringValue> wrapper = ProtoTypeConverters.rowIdToProto(original);
        byte[] resultBytes = ProtoTypeConverters.rowIdBytesFromProto(wrapper.orElse(null));
        
        assertArrayEquals(originalBytes, resultBytes, "Large RowId bytes should survive roundtrip conversion");
    }

    // ===== Null vs Empty Semantics Tests =====

    @Test
    void testNullVsEmptySemanticsUuid() {
        // Null → absent wrapper
        Optional<StringValue> nullResult = ProtoTypeConverters.uuidToProto(null);
        assertFalse(nullResult.isPresent(), "Null UUID should be absent");
        
        // Absent wrapper → null
        UUID fromNull = ProtoTypeConverters.uuidFromProto(null);
        assertNull(fromNull, "Absent wrapper should give null UUID");
    }

    @Test
    void testNullVsEmptySemanticsUrl() {
        // Null → absent wrapper
        Optional<StringValue> nullResult = ProtoTypeConverters.urlToProto(null);
        assertFalse(nullResult.isPresent(), "Null URL should be absent");
        
        // Absent wrapper → null
        URL fromNull = ProtoTypeConverters.urlFromProto(null);
        assertNull(fromNull, "Absent wrapper should give null URL");
    }

    @Test
    void testNullVsEmptySemanticsRowId() {
        // Null → absent wrapper
        Optional<StringValue> nullResult = ProtoTypeConverters.rowIdToProto(null);
        assertFalse(nullResult.isPresent(), "Null RowId should be absent");
        
        // Absent wrapper → null
        byte[] fromNull = ProtoTypeConverters.rowIdBytesFromProto(null);
        assertNull(fromNull, "Absent wrapper should give null bytes");
        
        // Empty string → empty bytes (not null)
        StringValue emptyWrapper = StringValue.of("");
        byte[] emptyBytes = ProtoTypeConverters.rowIdBytesFromProto(emptyWrapper);
        assertNotNull(emptyBytes, "Empty string wrapper should give non-null bytes");
        assertEquals(0, emptyBytes.length, "Empty string wrapper should give zero-length bytes");
    }
}
