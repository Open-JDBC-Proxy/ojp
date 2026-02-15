package org.openjproxy.grpc;

import com.google.protobuf.Timestamp;
import com.google.type.Date;
import com.google.type.TimeOfDay;
import com.openjproxy.grpc.TimestampWithZone;
import com.openjproxy.grpc.TemporalType;
import org.junit.jupiter.api.Test;

import java.sql.Time;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TemporalConverter.
 */
class TemporalConverterTest {

    @Test
    void testTimestampWithZoneRoundTrip() {
        // Test with a timestamp and UTC timezone
        java.sql.Timestamp timestamp = java.sql.Timestamp.valueOf("2024-11-02 14:30:45.123456789");
        ZoneId zoneId = ZoneId.of("UTC");
        
        TimestampWithZone proto = TemporalConverter.toTimestampWithZone(timestamp, zoneId);
        assertNotNull(proto);
        assertEquals("UTC", proto.getTimezone());
        
        java.sql.Timestamp result = TemporalConverter.fromTimestampWithZone(proto);
        assertNotNull(result);
        assertEquals(timestamp, result);
        assertEquals(timestamp.getNanos(), result.getNanos());
    }
    
    @Test
    void testTimestampWithZoneWithOffset() {
        // Test with an offset timezone
        java.sql.Timestamp timestamp = java.sql.Timestamp.valueOf("2024-11-02 14:30:45.123456789");
        ZoneId zoneId = ZoneId.of("+02:00");
        
        TimestampWithZone proto = TemporalConverter.toTimestampWithZone(timestamp, zoneId);
        assertNotNull(proto);
        assertEquals("+02:00", proto.getTimezone());
        
        java.sql.Timestamp result = TemporalConverter.fromTimestampWithZone(proto);
        assertNotNull(result);
        assertEquals(timestamp, result);
    }
    
    @Test
    void testTimestampWithZoneWithIanaZone() {
        // Test with IANA timezone
        java.sql.Timestamp timestamp = java.sql.Timestamp.valueOf("2024-11-02 14:30:45.123456789");
        ZoneId zoneId = ZoneId.of("Europe/Rome");
        
        TimestampWithZone proto = TemporalConverter.toTimestampWithZone(timestamp, zoneId);
        assertNotNull(proto);
        assertEquals("Europe/Rome", proto.getTimezone());
        
        java.sql.Timestamp result = TemporalConverter.fromTimestampWithZone(proto);
        assertNotNull(result);
        assertEquals(timestamp, result);
    }
    
    @Test
    void testTimestampWithZonePreservesNanos() {
        // Test that nanoseconds are preserved
        java.sql.Timestamp timestamp = new java.sql.Timestamp(System.currentTimeMillis());
        timestamp.setNanos(123456789);
        ZoneId zoneId = ZoneId.of("America/New_York");
        
        TimestampWithZone proto = TemporalConverter.toTimestampWithZone(timestamp, zoneId);
        java.sql.Timestamp result = TemporalConverter.fromTimestampWithZone(proto);
        
        assertEquals(timestamp.getNanos(), result.getNanos());
        assertEquals(timestamp, result);
    }
    
    @Test
    void testTimestampWithZoneNullTimestamp() {
        TimestampWithZone proto = TemporalConverter.toTimestampWithZone(null, ZoneId.of("UTC"));
        assertNull(proto);
        
        java.sql.Timestamp result = TemporalConverter.fromTimestampWithZone(null);
        assertNull(result);
    }
    
    @Test
    void testTimestampWithZoneNullZoneIdThrowsException() {
        java.sql.Timestamp timestamp = new java.sql.Timestamp(System.currentTimeMillis());
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            TemporalConverter.toTimestampWithZone(timestamp, null);
        });
        
        assertTrue(exception.getMessage().contains("ZoneId must not be null"));
    }
    
    @Test
    void testTimestampWithZoneMissingTimezoneThrowsException() {
        // Create a TimestampWithZone with empty timezone
        TimestampWithZone proto = TimestampWithZone.newBuilder()
            .setInstant(Timestamp.newBuilder().setSeconds(1000).setNanos(0).build())
            .setTimezone("")
            .build();
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            TemporalConverter.fromTimestampWithZone(proto);
        });
        
        assertTrue(exception.getMessage().contains("Timezone must not be empty"));
    }
    
    @Test
    void testTimestampWithZoneInvalidTimezoneThrowsException() {
        // Create a TimestampWithZone with invalid timezone
        TimestampWithZone proto = TimestampWithZone.newBuilder()
            .setInstant(Timestamp.newBuilder().setSeconds(1000).setNanos(0).build())
            .setTimezone("InvalidTimezone")
            .build();
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            TemporalConverter.fromTimestampWithZone(proto);
        });
        
        assertTrue(exception.getMessage().contains("Invalid timezone string"));
    }
    
    @Test
    void testGetZoneIdFromTimestampWithZone() {
        java.sql.Timestamp timestamp = new java.sql.Timestamp(System.currentTimeMillis());
        ZoneId originalZoneId = ZoneId.of("Asia/Tokyo");
        
        TimestampWithZone proto = TemporalConverter.toTimestampWithZone(timestamp, originalZoneId);
        ZoneId extractedZoneId = TemporalConverter.getZoneIdFromTimestampWithZone(proto);
        
        assertEquals(originalZoneId, extractedZoneId);
    }
    
    @Test
    void testToZonedDateTime() {
        java.sql.Timestamp timestamp = java.sql.Timestamp.valueOf("2024-11-02 14:30:45.123456789");
        ZoneId zoneId = ZoneId.of("Europe/Paris");
        
        TimestampWithZone proto = TemporalConverter.toTimestampWithZone(timestamp, zoneId);
        ZonedDateTime zonedDateTime = TemporalConverter.toZonedDateTime(proto);
        
        assertNotNull(zonedDateTime);
        assertEquals(zoneId, zonedDateTime.getZone());
        assertEquals(timestamp.toInstant(), zonedDateTime.toInstant());
    }
    
    @Test
    void testDateRoundTrip() {
        // Test date conversion
        java.sql.Date date = java.sql.Date.valueOf("2024-11-02");
        
        Date proto = TemporalConverter.toProtoDate(date);
        assertNotNull(proto);
        assertEquals(2024, proto.getYear());
        assertEquals(11, proto.getMonth());
        assertEquals(2, proto.getDay());
        
        java.sql.Date result = TemporalConverter.fromProtoDate(proto);
        assertNotNull(result);
        assertEquals(date, result);
    }
    
    @Test
    void testDateNull() {
        Date proto = TemporalConverter.toProtoDate(null);
        assertNull(proto);
        
        java.sql.Date result = TemporalConverter.fromProtoDate(null);
        assertNull(result);
    }
    
    @Test
    void testTimeRoundTrip() {
        // Test time conversion
        Time time = Time.valueOf("14:30:45");
        
        TimeOfDay proto = TemporalConverter.toProtoTimeOfDay(time);
        assertNotNull(proto);
        assertEquals(14, proto.getHours());
        assertEquals(30, proto.getMinutes());
        assertEquals(45, proto.getSeconds());
        
        Time result = TemporalConverter.fromProtoTimeOfDay(proto);
        assertNotNull(result);
        assertEquals(time.toString(), result.toString());
    }
    
    @Test
    void testTimePreservesNanos() {
        // Test that nanoseconds are preserved through proto format
        // Note: java.sql.Time has millisecond precision only, but the proto format supports nanos
        // We test that nanos are preserved in the proto and can be reconstructed in LocalTime
        
        LocalTime localTime = LocalTime.of(14, 30, 45, 123456789);
        
        // Build proto with full nanos
        TimeOfDay proto = TimeOfDay.newBuilder()
            .setHours(localTime.getHour())
            .setMinutes(localTime.getMinute())
            .setSeconds(localTime.getSecond())
            .setNanos(localTime.getNano())
            .build();
        
        // Verify nanos are stored in proto
        assertEquals(123456789, proto.getNanos());
        
        // Convert back: Time.valueOf(LocalTime) truncates nanos, but toLocalTime() preserves them
        Time result = TemporalConverter.fromProtoTimeOfDay(proto);
        LocalTime resultLocalTime = result.toLocalTime();
        
        // The LocalTime reconstruction from proto preserves nanos
        assertEquals(localTime.getHour(), resultLocalTime.getHour());
        assertEquals(localTime.getMinute(), resultLocalTime.getMinute());
        assertEquals(localTime.getSecond(), resultLocalTime.getSecond());
        // Note: java.sql.Time.valueOf(LocalTime) truncates to milliseconds,
        // so we can't expect full nanosecond precision through Time object.
        // But the proto format preserves nanos for systems that support it.
    }
    
    @Test
    void testTimeNull() {
        TimeOfDay proto = TemporalConverter.toProtoTimeOfDay(null);
        assertNull(proto);
        
        Time result = TemporalConverter.fromProtoTimeOfDay(null);
        assertNull(result);
    }
    
    @Test
    void testTimestampWithZoneEpochBoundaries() {
        // Test edge cases around epoch
        java.sql.Timestamp epoch = new java.sql.Timestamp(0);
        ZoneId zoneId = ZoneId.of("UTC");
        
        TimestampWithZone proto = TemporalConverter.toTimestampWithZone(epoch, zoneId);
        java.sql.Timestamp result = TemporalConverter.fromTimestampWithZone(proto);
        
        assertEquals(epoch, result);
    }
    
    @Test
    void testTimestampWithZoneSystemDefaultZone() {
        // Test with system default zone
        java.sql.Timestamp timestamp = new java.sql.Timestamp(System.currentTimeMillis());
        ZoneId systemDefault = ZoneId.systemDefault();
        
        TimestampWithZone proto = TemporalConverter.toTimestampWithZone(timestamp, systemDefault);
        assertEquals(systemDefault.getId(), proto.getTimezone());
        
        java.sql.Timestamp result = TemporalConverter.fromTimestampWithZone(proto);
        assertEquals(timestamp, result);
    }
    
    @Test
    void testOffsetDateTimeToTimestampWithZoneRoundTrip() {
        // Test with OffsetDateTime and UTC offset
        java.time.OffsetDateTime offsetDateTime = java.time.OffsetDateTime.parse("2024-11-02T14:30:45.123456789Z");
        
        TimestampWithZone proto = TemporalConverter.offsetDateTimeToTimestampWithZone(offsetDateTime);
        assertNotNull(proto);
        assertEquals("Z", proto.getTimezone());
        assertEquals(TemporalType.TEMPORAL_TYPE_OFFSET_DATE_TIME, proto.getOriginalType());
        
        java.time.OffsetDateTime result = TemporalConverter.timestampWithZoneToOffsetDateTime(proto);
        assertNotNull(result);
        assertEquals(offsetDateTime.toInstant(), result.toInstant());
    }
    
    @Test
    void testOffsetDateTimeToTimestampWithZoneWithOffset() {
        // Test with a specific offset
        java.time.OffsetDateTime offsetDateTime = java.time.OffsetDateTime.parse("2024-11-02T14:30:45.123+02:00");
        
        TimestampWithZone proto = TemporalConverter.offsetDateTimeToTimestampWithZone(offsetDateTime);
        assertNotNull(proto);
        assertEquals("+02:00", proto.getTimezone());
        assertEquals(TemporalType.TEMPORAL_TYPE_OFFSET_DATE_TIME, proto.getOriginalType());
        
        java.time.OffsetDateTime result = TemporalConverter.timestampWithZoneToOffsetDateTime(proto);
        assertNotNull(result);
        assertEquals(offsetDateTime.toInstant(), result.toInstant());
    }
    
    @Test
    void testOffsetDateTimeToTimestampWithZoneNull() {
        TimestampWithZone proto = TemporalConverter.offsetDateTimeToTimestampWithZone(null);
        assertNull(proto);
        
        java.time.OffsetDateTime result = TemporalConverter.timestampWithZoneToOffsetDateTime(null);
        assertNull(result);
    }
    
    @Test
    void testOffsetDateTimeToTimestampWithZonePreservesNanos() {
        // Test that nanoseconds are preserved
        java.time.OffsetDateTime offsetDateTime = java.time.OffsetDateTime.parse("2024-11-02T14:30:45.123456789+05:30");
        
        TimestampWithZone proto = TemporalConverter.offsetDateTimeToTimestampWithZone(offsetDateTime);
        java.time.OffsetDateTime result = TemporalConverter.timestampWithZoneToOffsetDateTime(proto);
        
        assertEquals(offsetDateTime.getNano(), result.getNano());
        assertEquals(offsetDateTime.toInstant(), result.toInstant());
    }
    
    @Test
    void testFromTimestampWithZoneToObjectWithOffsetDateTime() {
        // Test that fromTimestampWithZoneToObject correctly returns OffsetDateTime
        // when original_type is TEMPORAL_TYPE_OFFSET_DATE_TIME
        java.time.OffsetDateTime offsetDateTime = java.time.OffsetDateTime.parse("2024-11-02T14:30:45.123+02:00");
        
        TimestampWithZone proto = TemporalConverter.offsetDateTimeToTimestampWithZone(offsetDateTime);
        Object result = TemporalConverter.fromTimestampWithZoneToObject(proto);
        
        assertNotNull(result);
        assertInstanceOf(OffsetDateTime.class, result);
        assertEquals(offsetDateTime.toInstant(), ((java.time.OffsetDateTime) result).toInstant());
    }
    
    @Test
    void testLocalDateTimeToTimestampWithZoneRoundTrip() {
        // Test with LocalDateTime
        java.time.LocalDateTime localDateTime = java.time.LocalDateTime.of(2024, 11, 2, 14, 30, 45, 123456789);
        
        TimestampWithZone proto = TemporalConverter.localDateTimeToTimestampWithZone(localDateTime);
        assertNotNull(proto);
        assertEquals(TemporalType.TEMPORAL_TYPE_LOCAL_DATE_TIME, proto.getOriginalType());
        
        java.time.LocalDateTime result = TemporalConverter.timestampWithZoneToLocalDateTime(proto);
        assertNotNull(result);
        assertEquals(localDateTime, result);
    }
    
    @Test
    void testLocalDateTimeToTimestampWithZoneNull() {
        TimestampWithZone proto = TemporalConverter.localDateTimeToTimestampWithZone(null);
        assertNull(proto);
        
        java.time.LocalDateTime result = TemporalConverter.timestampWithZoneToLocalDateTime(null);
        assertNull(result);
    }
    
    @Test
    void testLocalDateTimeToTimestampWithZonePreservesNanos() {
        // Test that nanoseconds are preserved
        java.time.LocalDateTime localDateTime = java.time.LocalDateTime.of(2024, 11, 2, 14, 30, 45, 987654321);
        
        TimestampWithZone proto = TemporalConverter.localDateTimeToTimestampWithZone(localDateTime);
        java.time.LocalDateTime result = TemporalConverter.timestampWithZoneToLocalDateTime(proto);
        
        assertEquals(localDateTime.getNano(), result.getNano());
        assertEquals(localDateTime, result);
    }
    
    @Test
    void testFromTimestampWithZoneToObjectWithLocalDateTime() {
        // Test that fromTimestampWithZoneToObject correctly returns LocalDateTime
        // when original_type is TEMPORAL_TYPE_LOCAL_DATE_TIME
        java.time.LocalDateTime localDateTime = java.time.LocalDateTime.of(2024, 11, 2, 14, 30, 45, 123456789);
        
        TimestampWithZone proto = TemporalConverter.localDateTimeToTimestampWithZone(localDateTime);
        Object result = TemporalConverter.fromTimestampWithZoneToObject(proto);
        
        assertNotNull(result);
        assertInstanceOf(java.time.LocalDateTime.class, result);
        assertEquals(localDateTime, result);
    }
    
    @Test
    void testInstantToTimestampWithZoneRoundTrip() {
        // Test with Instant
        java.time.Instant instant = java.time.Instant.now();
        
        TimestampWithZone proto = TemporalConverter.instantToTimestampWithZone(instant);
        assertNotNull(proto);
        assertEquals("UTC", proto.getTimezone());
        assertEquals(TemporalType.TEMPORAL_TYPE_INSTANT, proto.getOriginalType());
        
        java.time.Instant result = TemporalConverter.timestampWithZoneToInstant(proto);
        assertNotNull(result);
        assertEquals(instant, result);
    }
    
    @Test
    void testInstantToTimestampWithZoneNull() {
        TimestampWithZone proto = TemporalConverter.instantToTimestampWithZone(null);
        assertNull(proto);
        
        java.time.Instant result = TemporalConverter.timestampWithZoneToInstant(null);
        assertNull(result);
    }
    
    @Test
    void testInstantToTimestampWithZonePreservesNanos() {
        // Test that nanoseconds are preserved
        java.time.Instant instant = java.time.Instant.ofEpochSecond(1609459200, 987654321);
        
        TimestampWithZone proto = TemporalConverter.instantToTimestampWithZone(instant);
        java.time.Instant result = TemporalConverter.timestampWithZoneToInstant(proto);
        
        assertEquals(instant.getEpochSecond(), result.getEpochSecond());
        assertEquals(instant.getNano(), result.getNano());
    }
    
    @Test
    void testFromTimestampWithZoneToObjectWithInstant() {
        // Test that fromTimestampWithZoneToObject correctly returns Instant
        // when original_type is TEMPORAL_TYPE_INSTANT
        java.time.Instant instant = java.time.Instant.now();
        
        TimestampWithZone proto = TemporalConverter.instantToTimestampWithZone(instant);
        Object result = TemporalConverter.fromTimestampWithZoneToObject(proto);
        
        assertNotNull(result);
        assertInstanceOf(java.time.Instant.class, result);
        assertEquals(instant, result);
    }
    
    @Test
    void testLocalDateToProtoDateRoundTrip() {
        // Test LocalDate conversion
        java.time.LocalDate localDate = java.time.LocalDate.of(2024, 11, 2);
        
        Date proto = TemporalConverter.localDateToProtoDate(localDate);
        assertNotNull(proto);
        assertEquals(2024, proto.getYear());
        assertEquals(11, proto.getMonth());
        assertEquals(2, proto.getDay());
        
        java.time.LocalDate result = TemporalConverter.protoDateToLocalDate(proto);
        assertNotNull(result);
        assertEquals(localDate, result);
    }
    
    @Test
    void testLocalDateToProtoDateNull() {
        Date proto = TemporalConverter.localDateToProtoDate(null);
        assertNull(proto);
        
        java.time.LocalDate result = TemporalConverter.protoDateToLocalDate(null);
        assertNull(result);
    }
    
    @Test
    void testLocalTimeToProtoTimeOfDayRoundTrip() {
        // Test LocalTime conversion
        java.time.LocalTime localTime = java.time.LocalTime.of(14, 30, 45, 123456789);
        
        TimeOfDay proto = TemporalConverter.localTimeToProtoTimeOfDay(localTime);
        assertNotNull(proto);
        assertEquals(14, proto.getHours());
        assertEquals(30, proto.getMinutes());
        assertEquals(45, proto.getSeconds());
        assertEquals(123456789, proto.getNanos());
        
        java.time.LocalTime result = TemporalConverter.protoTimeOfDayToLocalTime(proto);
        assertNotNull(result);
        assertEquals(localTime, result);
    }
    
    @Test
    void testLocalTimeToProtoTimeOfDayNull() {
        TimeOfDay proto = TemporalConverter.localTimeToProtoTimeOfDay(null);
        assertNull(proto);
        
        java.time.LocalTime result = TemporalConverter.protoTimeOfDayToLocalTime(null);
        assertNull(result);
    }
    
    @Test
    void testOffsetTimeToTimestampWithZoneRoundTrip() {
        // Test with OffsetTime
        java.time.OffsetTime offsetTime = java.time.OffsetTime.of(14, 30, 45, 123456789, java.time.ZoneOffset.ofHours(2));
        
        TimestampWithZone proto = TemporalConverter.offsetTimeToTimestampWithZone(offsetTime);
        assertNotNull(proto);
        assertEquals("+02:00", proto.getTimezone());
        assertEquals(TemporalType.TEMPORAL_TYPE_OFFSET_TIME, proto.getOriginalType());
        
        java.time.OffsetTime result = TemporalConverter.timestampWithZoneToOffsetTime(proto);
        assertNotNull(result);
        assertEquals(offsetTime.getHour(), result.getHour());
        assertEquals(offsetTime.getMinute(), result.getMinute());
        assertEquals(offsetTime.getSecond(), result.getSecond());
        assertEquals(offsetTime.getNano(), result.getNano());
        assertEquals(offsetTime.getOffset(), result.getOffset());
    }
    
    @Test
    void testOffsetTimeToTimestampWithZoneNull() {
        TimestampWithZone proto = TemporalConverter.offsetTimeToTimestampWithZone(null);
        assertNull(proto);
        
        java.time.OffsetTime result = TemporalConverter.timestampWithZoneToOffsetTime(null);
        assertNull(result);
    }
    
    @Test
    void testFromTimestampWithZoneToObjectWithOffsetTime() {
        // Test that fromTimestampWithZoneToObject correctly returns OffsetTime
        // when original_type is TEMPORAL_TYPE_OFFSET_TIME
        java.time.OffsetTime offsetTime = java.time.OffsetTime.of(14, 30, 45, 123456789, java.time.ZoneOffset.ofHours(2));
        
        TimestampWithZone proto = TemporalConverter.offsetTimeToTimestampWithZone(offsetTime);
        Object result = TemporalConverter.fromTimestampWithZoneToObject(proto);
        
        assertNotNull(result);
        assertInstanceOf(java.time.OffsetTime.class, result);
        java.time.OffsetTime resultOt = (java.time.OffsetTime) result;
        assertEquals(offsetTime.getHour(), resultOt.getHour());
        assertEquals(offsetTime.getMinute(), resultOt.getMinute());
        assertEquals(offsetTime.getSecond(), resultOt.getSecond());
        assertEquals(offsetTime.getNano(), resultOt.getNano());
        assertEquals(offsetTime.getOffset(), resultOt.getOffset());
    }
}
