package org.openjproxy.grpc;

import com.openjproxy.grpc.ParameterValue;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.dto.ParameterType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ProtoConverter to verify correct handling of null vs empty byte arrays.
 */
public class ProtoConverterTest {
    private static final int PARAM_INDEX_3 = 3;
    private static final int PARAM_INDEX_4 = 4;
    private static final int PARAM_INDEX_5 = 5;
    private static final int TEST_INT_VALUE = 42;

    @Test
    void testNullValueSerialization() {
        // Convert null to ParameterValue
        ParameterValue pv = ProtoConverter.toParameterValue(null);
        
        // Verify is_null is set
        assertEquals(ParameterValue.ValueCase.IS_NULL, pv.getValueCase());
        assertTrue(pv.getIsNull());
        
        // Convert back to Object
        Object result = ProtoConverter.fromParameterValue(pv, ParameterType.BYTES);
        assertNull(result);
    }

    @Test
    void testEmptyByteArraySerialization() {
        // Convert empty byte array to ParameterValue
        byte[] emptyArray = new byte[0];
        ParameterValue pv = ProtoConverter.toParameterValue(emptyArray);
        
        // Verify bytes_value is set (not is_null)
        assertEquals(ParameterValue.ValueCase.BYTES_VALUE, pv.getValueCase());
        assertNotNull(pv.getBytesValue());
        assertEquals(0, pv.getBytesValue().size());
        
        // Convert back to Object with BYTES type (should preserve empty array)
        Object result = ProtoConverter.fromParameterValue(pv, ParameterType.BYTES);
        assertNotNull(result, "Empty byte array should not be null");
        assertInstanceOf(byte[].class, result);
        assertEquals(0, ((byte[]) result).length);
    }

    @Test
    void testEmptyByteArrayWithBlobType() {
        // Convert empty byte array to ParameterValue
        byte[] emptyArray = new byte[0];
        ParameterValue pv = ProtoConverter.toParameterValue(emptyArray);
        
        // Convert back with BLOB type
        Object result = ProtoConverter.fromParameterValue(pv, ParameterType.BLOB);
        assertNotNull(result, "Empty BLOB should not be null");
        assertInstanceOf(byte[].class, result);
        assertEquals(0, ((byte[]) result).length);
    }

    @Test
    void testNonEmptyByteArraySerialization() {
        // Convert non-empty byte array to ParameterValue
        byte[] dataArray = new byte[]{1, 2, 3, 4, 5};
        ParameterValue pv = ProtoConverter.toParameterValue(dataArray);
        
        // Verify bytes_value is set
        assertEquals(ParameterValue.ValueCase.BYTES_VALUE, pv.getValueCase());
        assertEquals(PARAM_INDEX_5, pv.getBytesValue().size());
        
        // Convert back to Object
        Object result = ProtoConverter.fromParameterValue(pv, ParameterType.BYTES);
        assertNotNull(result);
        assertInstanceOf(byte[].class, result);
        assertArrayEquals(dataArray, (byte[]) result);
    }

    @Test
    void testNullVsEmptyByteArrayDistinction() {
        // Test null
        ParameterValue nullPv = ProtoConverter.toParameterValue(null);
        Object nullResult = ProtoConverter.fromParameterValue(nullPv, ParameterType.BYTES);
        assertNull(nullResult, "Null should remain null");
        
        // Test empty byte array
        byte[] emptyArray = new byte[0];
        ParameterValue emptyPv = ProtoConverter.toParameterValue(emptyArray);
        Object emptyResult = ProtoConverter.fromParameterValue(emptyPv, ParameterType.BYTES);
        assertNotNull(emptyResult, "Empty byte array should not be null");
        assertInstanceOf(byte[].class, emptyResult);
        assertEquals(0, ((byte[]) emptyResult).length);
        
        // Verify they're different
        assertNotEquals(nullPv.getValueCase(), emptyPv.getValueCase());
    }

    @Test
    void testStringValueSerialization() {
        String testString = "test string";
        ParameterValue pv = ProtoConverter.toParameterValue(testString);
        
        assertEquals(ParameterValue.ValueCase.STRING_VALUE, pv.getValueCase());
        
        Object result = ProtoConverter.fromParameterValue(pv, ParameterType.STRING);
        assertEquals(testString, result);
    }

    @Test
    void testIntegerValueSerialization() {
        Integer testInt = 42;
        ParameterValue pv = ProtoConverter.toParameterValue(testInt);
        
        assertEquals(ParameterValue.ValueCase.INT_VALUE, pv.getValueCase());
        
        Object result = ProtoConverter.fromParameterValue(pv, ParameterType.INT);
        assertEquals(testInt, result);
    }

    @Test
    void testBackwardCompatibilityForNonBinaryTypes() {
        // For non-binary types (like OBJECT), empty bytes should be handled gracefully
        // This tests that our change doesn't break existing behavior
        ParameterValue emptyBytesPv = ParameterValue.newBuilder()
                .setBytesValue(com.google.protobuf.ByteString.EMPTY)
                .build();
        
        // For OBJECT type, empty bytes should be handled gracefully (no crash)
        // Deserialization may fail for empty bytes, in which case raw bytes are returned
        Object result = ProtoConverter.fromParameterValue(emptyBytesPv, ParameterType.OBJECT);
        
        // If deserialization fails, the result will be the raw byte array
        if (result instanceof byte[]) {
            assertEquals(0, ((byte[]) result).length, "Empty bytes should remain empty");
        }
    }

    @Test
    void testBigDecimalSerialization() {
        BigDecimal testValue = new BigDecimal("123.456");
        ParameterValue pv = ProtoConverter.toParameterValue(testValue);
        
        // BigDecimal should be serialized as bytes using BigDecimalWire
        assertEquals(ParameterValue.ValueCase.BYTES_VALUE, pv.getValueCase());
        
        // Convert back to Object with BIG_DECIMAL type
        Object result = ProtoConverter.fromParameterValue(pv, ParameterType.BIG_DECIMAL);
        assertNotNull(result);
        assertInstanceOf(BigDecimal.class, result);
        assertEquals(testValue, result);
    }

    @Test
    void testBigDecimalNull() {
        BigDecimal testValue = null;
        ParameterValue pv = ProtoConverter.toParameterValue(testValue);
        
        // Null should set is_null flag
        assertEquals(ParameterValue.ValueCase.IS_NULL, pv.getValueCase());
        
        // Convert back
        Object result = ProtoConverter.fromParameterValue(pv, ParameterType.BIG_DECIMAL);
        assertNull(result);
    }

    @Test
    void testBigDecimalZero() {
        BigDecimal testValue = BigDecimal.ZERO;
        ParameterValue pv = ProtoConverter.toParameterValue(testValue);
        
        assertEquals(ParameterValue.ValueCase.BYTES_VALUE, pv.getValueCase());
        
        Object result = ProtoConverter.fromParameterValue(pv, ParameterType.BIG_DECIMAL);
        assertEquals(testValue, result);
    }

    @Test
    void testBigDecimalNegative() {
        BigDecimal testValue = new BigDecimal("-987.654321");
        ParameterValue pv = ProtoConverter.toParameterValue(testValue);
        
        Object result = ProtoConverter.fromParameterValue(pv, ParameterType.BIG_DECIMAL);
        assertEquals(testValue, result);
    }

    @Test
    void testBigDecimalVeryLarge() {
        BigDecimal testValue = new BigDecimal("123456789012345678901234567890.123456789");
        ParameterValue pv = ProtoConverter.toParameterValue(testValue);
        
        Object result = ProtoConverter.fromParameterValue(pv, ParameterType.BIG_DECIMAL);
        assertEquals(testValue, result);
    }

    @Test
    void testBigDecimalRoundTrip() {
        // Test multiple BigDecimal values
        BigDecimal[] testValues = {
            null,
            BigDecimal.ZERO,
            BigDecimal.ONE,
            BigDecimal.TEN,
            new BigDecimal("123.456"),
            new BigDecimal("-789.012"),
            new BigDecimal("0.000000001"),
            new BigDecimal("999999999999999999999999999999")
        };
        
        for (BigDecimal original : testValues) {
            ParameterValue pv = ProtoConverter.toParameterValue(original);
            Object result = ProtoConverter.fromParameterValue(pv, ParameterType.BIG_DECIMAL);
            assertEquals(original, result, "Failed for value: " + original);
        }
    }
    
    @Test
    void testOffsetDateTimeSerialization() {
        // Test that OffsetDateTime can be serialized and deserialized
        java.time.OffsetDateTime offsetDateTime = java.time.OffsetDateTime.parse("2024-11-02T14:30:45.123456789+02:00");
        
        ParameterValue pv = ProtoConverter.toParameterValue(offsetDateTime);
        
        // Verify timestamp_value is set
        assertEquals(ParameterValue.ValueCase.TIMESTAMP_VALUE, pv.getValueCase());
        assertNotNull(pv.getTimestampValue());
        assertEquals(com.openjproxy.grpc.TemporalType.TEMPORAL_TYPE_OFFSET_DATE_TIME, pv.getTimestampValue().getOriginalType());
        
        // Convert back to Object
        Object result = ProtoConverter.fromParameterValue(pv, null);
        assertNotNull(result);
        assertInstanceOf(OffsetDateTime.class, result);
        assertEquals(offsetDateTime.toInstant(), ((java.time.OffsetDateTime) result).toInstant());
    }
    
    @Test
    void testOffsetDateTimeNullSerialization() {
        // Test that null OffsetDateTime is handled correctly
        java.time.OffsetDateTime offsetDateTime = null;
        
        ParameterValue pv = ProtoConverter.toParameterValue(offsetDateTime);
        
        // Verify is_null is set
        assertEquals(ParameterValue.ValueCase.IS_NULL, pv.getValueCase());
        assertTrue(pv.getIsNull());
        
        // Convert back to Object
        Object result = ProtoConverter.fromParameterValue(pv, null);
        assertNull(result);
    }
}
