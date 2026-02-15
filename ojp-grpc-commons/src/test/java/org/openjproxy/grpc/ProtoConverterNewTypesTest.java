package org.openjproxy.grpc;

import com.openjproxy.grpc.ParameterValue;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.sql.RowIdLifetime;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for new types added to ProtoConverter: UUID, BigInteger, String[], Calendar, RowIdLifetime, and java.time types
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
    
    // Tests for java.time types
    
    @Test
    void testLocalDateTimeRoundTrip() {
        LocalDateTime original = LocalDateTime.of(2024, 11, 2, 14, 30, 45, 123456789);
        ParameterValue value = ProtoConverter.toParameterValue(original);
        assertNotNull(value);
        assertEquals(ParameterValue.ValueCase.TIMESTAMP_VALUE, value.getValueCase());
        
        Object result = ProtoConverter.fromParameterValue(value, null);
        assertNotNull(result);
        assertInstanceOf(LocalDateTime.class, result);
        
        LocalDateTime resultLdt = (LocalDateTime) result;
        assertEquals(original, resultLdt);
    }
    
    @Test
    void testLocalDateTimeNull() {
        LocalDateTime original = null;
        ParameterValue value = ProtoConverter.toParameterValue(original);
        assertNotNull(value);
        assertEquals(ParameterValue.ValueCase.IS_NULL, value.getValueCase());
        
        Object result = ProtoConverter.fromParameterValue(value, null);
        assertNull(result);
    }
    
    @Test
    void testLocalDateRoundTrip() {
        LocalDate original = LocalDate.of(2024, 11, 2);
        ParameterValue value = ProtoConverter.toParameterValue(original);
        assertNotNull(value);
        assertEquals(ParameterValue.ValueCase.DATE_VALUE, value.getValueCase());
        
        // Result should be java.sql.Date (default for date_value)
        Object result = ProtoConverter.fromParameterValue(value, null);
        assertNotNull(result);
        assertInstanceOf(java.sql.Date.class, result);
        
        // Convert back to LocalDate for comparison
        java.sql.Date resultDate = (java.sql.Date) result;
        assertEquals(original, resultDate.toLocalDate());
    }
    
    @Test
    void testLocalDateNull() {
        LocalDate original = null;
        ParameterValue value = ProtoConverter.toParameterValue(original);
        assertNotNull(value);
        assertEquals(ParameterValue.ValueCase.IS_NULL, value.getValueCase());
        
        Object result = ProtoConverter.fromParameterValue(value, null);
        assertNull(result);
    }
    
    @Test
    void testLocalTimeRoundTrip() {
        LocalTime original = LocalTime.of(14, 30, 45, 123456789);
        ParameterValue value = ProtoConverter.toParameterValue(original);
        assertNotNull(value);
        assertEquals(ParameterValue.ValueCase.TIME_VALUE, value.getValueCase());
        
        // Result should be java.sql.Time (default for time_value)
        Object result = ProtoConverter.fromParameterValue(value, null);
        assertNotNull(result);
        assertInstanceOf(java.sql.Time.class, result);
        
        // Convert back to LocalTime for comparison
        java.sql.Time resultTime = (java.sql.Time) result;
        LocalTime resultLt = resultTime.toLocalTime();
        assertEquals(original.getHour(), resultLt.getHour());
        assertEquals(original.getMinute(), resultLt.getMinute());
        assertEquals(original.getSecond(), resultLt.getSecond());
        // Note: nanos preserved in proto, but java.sql.Time has millisecond precision
    }
    
    @Test
    void testLocalTimeNull() {
        LocalTime original = null;
        ParameterValue value = ProtoConverter.toParameterValue(original);
        assertNotNull(value);
        assertEquals(ParameterValue.ValueCase.IS_NULL, value.getValueCase());
        
        Object result = ProtoConverter.fromParameterValue(value, null);
        assertNull(result);
    }
    
    @Test
    void testOffsetDateTimeRoundTrip() {
        OffsetDateTime original = OffsetDateTime.of(2024, 11, 2, 14, 30, 45, 123456789, ZoneOffset.ofHours(2));
        ParameterValue value = ProtoConverter.toParameterValue(original);
        assertNotNull(value);
        assertEquals(ParameterValue.ValueCase.TIMESTAMP_VALUE, value.getValueCase());
        
        Object result = ProtoConverter.fromParameterValue(value, null);
        assertNotNull(result);
        assertInstanceOf(OffsetDateTime.class, result);
        
        OffsetDateTime resultOdt = (OffsetDateTime) result;
        assertEquals(original.toInstant(), resultOdt.toInstant());
    }
    
    @Test
    void testOffsetDateTimeNull() {
        OffsetDateTime original = null;
        ParameterValue value = ProtoConverter.toParameterValue(original);
        assertNotNull(value);
        assertEquals(ParameterValue.ValueCase.IS_NULL, value.getValueCase());
        
        Object result = ProtoConverter.fromParameterValue(value, null);
        assertNull(result);
    }
    
    @Test
    void testInstantRoundTrip() {
        Instant original = Instant.now();
        ParameterValue value = ProtoConverter.toParameterValue(original);
        assertNotNull(value);
        assertEquals(ParameterValue.ValueCase.TIMESTAMP_VALUE, value.getValueCase());
        
        Object result = ProtoConverter.fromParameterValue(value, null);
        assertNotNull(result);
        assertInstanceOf(Instant.class, result);
        
        Instant resultInstant = (Instant) result;
        assertEquals(original, resultInstant);
    }
    
    @Test
    void testInstantNull() {
        Instant original = null;
        ParameterValue value = ProtoConverter.toParameterValue(original);
        assertNotNull(value);
        assertEquals(ParameterValue.ValueCase.IS_NULL, value.getValueCase());
        
        Object result = ProtoConverter.fromParameterValue(value, null);
        assertNull(result);
    }
    
    @Test
    void testOffsetTimeRoundTrip() {
        OffsetTime original = OffsetTime.of(14, 30, 45, 123456789, ZoneOffset.ofHours(2));
        ParameterValue value = ProtoConverter.toParameterValue(original);
        assertNotNull(value);
        assertEquals(ParameterValue.ValueCase.TIMESTAMP_VALUE, value.getValueCase());
        
        Object result = ProtoConverter.fromParameterValue(value, null);
        assertNotNull(result);
        assertInstanceOf(OffsetTime.class, result);
        
        OffsetTime resultOt = (OffsetTime) result;
        // Compare time components
        assertEquals(original.getHour(), resultOt.getHour());
        assertEquals(original.getMinute(), resultOt.getMinute());
        assertEquals(original.getSecond(), resultOt.getSecond());
        assertEquals(original.getNano(), resultOt.getNano());
        assertEquals(original.getOffset(), resultOt.getOffset());
    }
    
    @Test
    void testOffsetTimeNull() {
        OffsetTime original = null;
        ParameterValue value = ProtoConverter.toParameterValue(original);
        assertNotNull(value);
        assertEquals(ParameterValue.ValueCase.IS_NULL, value.getValueCase());
        
        Object result = ProtoConverter.fromParameterValue(value, null);
        assertNull(result);
    }
    
    @Test
    void testLocalDateTimePreservesNanos() {
        LocalDateTime original = LocalDateTime.of(2024, 11, 2, 14, 30, 45, 987654321);
        ParameterValue value = ProtoConverter.toParameterValue(original);
        
        Object result = ProtoConverter.fromParameterValue(value, null);
        LocalDateTime resultLdt = (LocalDateTime) result;
        
        assertEquals(original.getNano(), resultLdt.getNano());
    }
    
    @Test
    void testInstantPreservesNanos() {
        Instant original = Instant.ofEpochSecond(1609459200, 987654321); // 2021-01-01T00:00:00.987654321Z
        ParameterValue value = ProtoConverter.toParameterValue(original);
        
        Object result = ProtoConverter.fromParameterValue(value, null);
        Instant resultInstant = (Instant) result;
        
        assertEquals(original.getEpochSecond(), resultInstant.getEpochSecond());
        assertEquals(original.getNano(), resultInstant.getNano());
    }
}
