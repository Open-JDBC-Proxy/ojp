package org.openjproxy.grpc;

import com.openjproxy.grpc.ParameterValue;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.sql.RowIdLifetime;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for new types added to ProtoConverter: UUID, BigInteger, String[], Calendar, RowIdLifetime
 */
public class ProtoConverterNewTypesTest {

    @Test
    void testUuidRoundTrip() {
        UUID original = UUID.randomUUID();
        ParameterValue value = ProtoConverter.toParameterValue(original);
        assertNotNull(value);
        assertEquals(ParameterValue.ValueCase.UUID_VALUE, value.getValueCase());
        
        UUID result = ProtoTypeConverters.uuidFromProto(value.getUuidValue());
        assertEquals(original, result);
    }

    @Test
    void testBigIntegerRoundTrip() {
        BigInteger original = new BigInteger("123456789012345678901234567890");
        ParameterValue value = ProtoConverter.toParameterValue(original);
        assertNotNull(value);
        assertEquals(ParameterValue.ValueCase.BIGINTEGER_VALUE, value.getValueCase());
        
        Object result = ProtoConverter.fromParameterValue(value, null);
        assertEquals(original, result);
    }

    @Test
    void testStringArrayRoundTrip() {
        String[] original = new String[]{"a", "b", "c", null};
        ParameterValue value = ProtoConverter.toParameterValue(original);
        assertNotNull(value);
        assertEquals(ParameterValue.ValueCase.STRING_ARRAY_VALUE, value.getValueCase());
        
        Object result = ProtoConverter.fromParameterValue(value, null);
        assertArrayEquals(original, (String[]) result);
    }

    @Test
    void testCalendarRoundTrip() {
        GregorianCalendar original = new GregorianCalendar();
        ParameterValue value = ProtoConverter.toParameterValue(original);
        assertNotNull(value);
        // Calendar is converted to TimestampWithZone
        assertEquals(ParameterValue.ValueCase.TIMESTAMP_VALUE, value.getValueCase());
        
        // Result should now be a Calendar (with original_type preservation)
        Object result = ProtoConverter.fromParameterValue(value, null);
        assertNotNull(result);
        assertInstanceOf(Calendar.class, result);
        
        // Verify times are equivalent (allowing for millisecond precision)
        Calendar resultCal = (Calendar) result;
        assertEquals(original.getTimeInMillis(), resultCal.getTimeInMillis());
        assertEquals(original.getTimeZone(), resultCal.getTimeZone());
    }
    
    @Test
    void testTimestampRoundTrip() {
        // Test that regular Timestamp still returns Timestamp (not Calendar)
        java.sql.Timestamp original = new java.sql.Timestamp(System.currentTimeMillis());
        ParameterValue value = ProtoConverter.toParameterValue(original, java.time.ZoneId.systemDefault());
        assertNotNull(value);
        assertEquals(ParameterValue.ValueCase.TIMESTAMP_VALUE, value.getValueCase());
        
        // Result should be a Timestamp
        Object result = ProtoConverter.fromParameterValue(value, null);
        assertNotNull(result);
        assertInstanceOf(Timestamp.class, result);
        
        // Verify times are equivalent
        java.sql.Timestamp resultTs = (java.sql.Timestamp) result;
        assertEquals(original.getTime(), resultTs.getTime());
    }

    @Test
    void testRowIdLifetimeRoundTrip() {
        // Test all enum values
        for (RowIdLifetime original : RowIdLifetime.values()) {
            ParameterValue value = ProtoConverter.toParameterValue(original);
            assertNotNull(value);
            assertEquals(ParameterValue.ValueCase.ROWIDLIFETIME_VALUE, value.getValueCase());
            
            Object result = ProtoConverter.fromParameterValue(value, null);
            assertEquals(original, result);
        }
    }
}
