package openjproxy.grpc;

import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.openjproxy.grpc.SerializationHandler.deserialize;
import static org.openjproxy.grpc.SerializationHandler.serialize;

public class SerializationHandlerTest {
    @Test
    public void serializeDeserializeSuccessful() {
        //FOR
        Exception eOriginal = new RuntimeException("testing exception");

        //WHEN
        byte[] byteArray = serialize(eOriginal);
        Exception eDeserialized = deserialize(byteArray, Exception.class);

        //THEN
        assertEquals(eOriginal.getMessage(), eDeserialized.getMessage());
        assertEquals(eOriginal.getCause(), eDeserialized.getCause());
        StackTraceElement[] stackTraceOriginal = eOriginal.getStackTrace();
        StackTraceElement[] stackTraceDeserialized = eOriginal.getStackTrace();
        for (int i = 0; i < stackTraceOriginal.length; i++) {
            assertEquals(stackTraceOriginal[i], stackTraceDeserialized[i]);
        }
        assertNotEquals(eOriginal, eDeserialized);
    }

    @Test
    public void serializeDeserializeDateSuccessful() {
        // Test java.sql.Date serialization
        Date originalDate = Date.valueOf(LocalDate.of(2024, 6, 27));

        byte[] byteArray = serialize(originalDate);
        Date deserializedDate = deserialize(byteArray, Date.class);

        assertEquals(originalDate, deserializedDate);
        assertEquals(originalDate.getTime(), deserializedDate.getTime());
    }

    @Test
    public void serializeDeserializeTimeSuccessful() {
        // Test java.sql.Time serialization
        Time originalTime = Time.valueOf(LocalTime.of(14, 30, 0));

        byte[] byteArray = serialize(originalTime);
        Time deserializedTime = deserialize(byteArray, Time.class);

        assertEquals(originalTime, deserializedTime);
        assertEquals(originalTime.getTime(), deserializedTime.getTime());
    }

    @Test
    public void serializeDeserializeTimestampSuccessful() {
        // Test java.sql.Timestamp serialization
        Timestamp originalTimestamp = Timestamp.valueOf(LocalDateTime.of(2024, 6, 27, 14, 30, 0, 123456789));

        byte[] byteArray = serialize(originalTimestamp);
        Timestamp deserializedTimestamp = deserialize(byteArray, Timestamp.class);

        assertEquals(originalTimestamp, deserializedTimestamp);
        assertEquals(originalTimestamp.getTime(), deserializedTimestamp.getTime());
        assertEquals(originalTimestamp.getNanos(), deserializedTimestamp.getNanos());
    }
}
