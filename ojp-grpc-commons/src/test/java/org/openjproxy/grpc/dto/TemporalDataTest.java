package org.openjproxy.grpc.dto;

import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.SerializationHandler;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TemporalData DTO serialization and deserialization.
 */
public class TemporalDataTest {

    @Test
    public void testTemporalDataSerialization() {
        // Create a TemporalData object
        TemporalData temporalData = TemporalData.builder()
                .timeMillis(System.currentTimeMillis())
                .nanos(123456789)
                .timezoneId("America/New_York")
                .build();

        // Serialize and deserialize
        byte[] serialized = SerializationHandler.serialize(temporalData);
        TemporalData deserialized = SerializationHandler.deserialize(serialized, TemporalData.class);

        // Verify
        assertEquals(temporalData.getTimeMillis(), deserialized.getTimeMillis());
        assertEquals(temporalData.getNanos(), deserialized.getNanos());
        assertEquals(temporalData.getTimezoneId(), deserialized.getTimezoneId());
    }

    @Test
    public void testTemporalDataFromDate() {
        Date date = new Date(System.currentTimeMillis());
        
        TemporalData temporalData = TemporalData.builder()
                .timeMillis(date.getTime())
                .nanos(0)
                .timezoneId(TimeZone.getDefault().getID())
                .build();

        assertNotNull(temporalData);
        assertEquals(date.getTime(), temporalData.getTimeMillis());
        assertEquals(0, temporalData.getNanos());
        assertNotNull(temporalData.getTimezoneId());
    }

    @Test
    public void testTemporalDataFromTime() {
        Time time = new Time(System.currentTimeMillis());
        
        TemporalData temporalData = TemporalData.builder()
                .timeMillis(time.getTime())
                .nanos(0)
                .timezoneId(TimeZone.getDefault().getID())
                .build();

        assertNotNull(temporalData);
        assertEquals(time.getTime(), temporalData.getTimeMillis());
        assertEquals(0, temporalData.getNanos());
        assertNotNull(temporalData.getTimezoneId());
    }

    @Test
    public void testTemporalDataFromTimestamp() {
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        timestamp.setNanos(987654321);
        
        TemporalData temporalData = TemporalData.builder()
                .timeMillis(timestamp.getTime())
                .nanos(timestamp.getNanos())
                .timezoneId(TimeZone.getDefault().getID())
                .build();

        assertNotNull(temporalData);
        assertEquals(timestamp.getTime(), temporalData.getTimeMillis());
        assertEquals(timestamp.getNanos(), temporalData.getNanos());
        assertNotNull(temporalData.getTimezoneId());
    }

    @Test
    public void testTemporalDataToDate() {
        long timeMillis = System.currentTimeMillis();
        String timezoneId = "UTC";
        
        TemporalData temporalData = TemporalData.builder()
                .timeMillis(timeMillis)
                .nanos(0)
                .timezoneId(timezoneId)
                .build();

        // Reconstruct Date
        Date date = new Date(temporalData.getTimeMillis());
        
        assertEquals(timeMillis, date.getTime());
    }

    @Test
    public void testTemporalDataToTime() {
        long timeMillis = System.currentTimeMillis();
        String timezoneId = "UTC";
        
        TemporalData temporalData = TemporalData.builder()
                .timeMillis(timeMillis)
                .nanos(0)
                .timezoneId(timezoneId)
                .build();

        // Reconstruct Time
        Time time = new Time(temporalData.getTimeMillis());
        
        assertEquals(timeMillis, time.getTime());
    }

    @Test
    public void testTemporalDataToTimestamp() {
        // Create a timestamp first to understand its behavior
        Timestamp original = new Timestamp(System.currentTimeMillis());
        original.setNanos(123456789);
        
        // Now create TemporalData from it
        long timeMillis = original.getTime();
        int nanos = original.getNanos();
        String timezoneId = "UTC";
        
        TemporalData temporalData = TemporalData.builder()
                .timeMillis(timeMillis)
                .nanos(nanos)
                .timezoneId(timezoneId)
                .build();

        // Reconstruct Timestamp
        Timestamp timestamp = new Timestamp(temporalData.getTimeMillis());
        timestamp.setNanos(temporalData.getNanos());
        
        // Verify - timestamps should be equal
        assertEquals(original.getTime(), timestamp.getTime());
        assertEquals(original.getNanos(), timestamp.getNanos());
    }

    @Test
    public void testNullTemporalData() {
        TemporalData temporalData = null;
        
        // This should handle null gracefully
        assertNull(temporalData);
    }

    @Test
    public void testDifferentTimezones() {
        long timeMillis = System.currentTimeMillis();
        
        // Create TemporalData with different timezones
        TemporalData nyData = TemporalData.builder()
                .timeMillis(timeMillis)
                .nanos(0)
                .timezoneId("America/New_York")
                .build();
        
        TemporalData londonData = TemporalData.builder()
                .timeMillis(timeMillis)
                .nanos(0)
                .timezoneId("Europe/London")
                .build();
        
        TemporalData tokyoData = TemporalData.builder()
                .timeMillis(timeMillis)
                .nanos(0)
                .timezoneId("Asia/Tokyo")
                .build();
        
        // All should have the same timeMillis (UTC epoch)
        assertEquals(nyData.getTimeMillis(), londonData.getTimeMillis());
        assertEquals(nyData.getTimeMillis(), tokyoData.getTimeMillis());
        
        // But different timezone IDs
        assertNotEquals(nyData.getTimezoneId(), londonData.getTimezoneId());
        assertNotEquals(nyData.getTimezoneId(), tokyoData.getTimezoneId());
    }
}
