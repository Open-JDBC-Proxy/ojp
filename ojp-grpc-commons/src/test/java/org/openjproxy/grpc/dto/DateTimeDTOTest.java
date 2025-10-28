package org.openjproxy.grpc.dto;

import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

class DateTimeDTOTest {

    @Test
    void testFromAndToDate() {
        LocalDate localDate = LocalDate.of(2024, 6, 27);
        Date sqlDate = Date.valueOf(localDate);
        
        DateTimeDTO dto = DateTimeDTO.fromDate(sqlDate);
        
        assertNotNull(dto);
        assertEquals(DateTimeDTO.DateTimeType.DATE, dto.getType());
        assertEquals(sqlDate.getTime(), dto.getMilliseconds());
        assertEquals(0, dto.getNanoseconds());
        
        Date resultDate = dto.toDate();
        assertEquals(sqlDate, resultDate);
    }

    @Test
    void testFromAndToTime() {
        LocalTime localTime = LocalTime.of(14, 30, 45);
        Time sqlTime = Time.valueOf(localTime);
        
        DateTimeDTO dto = DateTimeDTO.fromTime(sqlTime);
        
        assertNotNull(dto);
        assertEquals(DateTimeDTO.DateTimeType.TIME, dto.getType());
        assertEquals(sqlTime.getTime(), dto.getMilliseconds());
        assertEquals(0, dto.getNanoseconds());
        
        Time resultTime = dto.toTime();
        assertEquals(sqlTime, resultTime);
    }

    @Test
    void testFromAndToTimestamp() {
        LocalDateTime localDateTime = LocalDateTime.of(2024, 6, 27, 14, 30, 45, 123456789);
        Timestamp sqlTimestamp = Timestamp.valueOf(localDateTime);
        
        DateTimeDTO dto = DateTimeDTO.fromTimestamp(sqlTimestamp);
        
        assertNotNull(dto);
        assertEquals(DateTimeDTO.DateTimeType.TIMESTAMP, dto.getType());
        assertEquals(sqlTimestamp.getTime(), dto.getMilliseconds());
        assertEquals(sqlTimestamp.getNanos(), dto.getNanoseconds());
        
        Timestamp resultTimestamp = dto.toTimestamp();
        assertEquals(sqlTimestamp, resultTimestamp);
    }

    @Test
    void testNullHandling() {
        assertNull(DateTimeDTO.fromDate(null));
        assertNull(DateTimeDTO.fromTime(null));
        assertNull(DateTimeDTO.fromTimestamp(null));
    }

    @Test
    void testToSqlType() {
        Date sqlDate = Date.valueOf(LocalDate.of(2024, 6, 27));
        DateTimeDTO dateDto = DateTimeDTO.fromDate(sqlDate);
        Object result = dateDto.toSqlType();
        assertTrue(result instanceof Date);
        assertEquals(sqlDate, result);
        
        Time sqlTime = Time.valueOf(LocalTime.of(14, 30));
        DateTimeDTO timeDto = DateTimeDTO.fromTime(sqlTime);
        result = timeDto.toSqlType();
        assertTrue(result instanceof Time);
        assertEquals(sqlTime, result);
        
        Timestamp sqlTimestamp = Timestamp.valueOf(LocalDateTime.of(2024, 6, 27, 14, 30));
        DateTimeDTO timestampDto = DateTimeDTO.fromTimestamp(sqlTimestamp);
        result = timestampDto.toSqlType();
        assertTrue(result instanceof Timestamp);
        assertEquals(sqlTimestamp, result);
    }

    @Test
    void testInvalidTypeConversion() {
        DateTimeDTO dateDto = DateTimeDTO.fromDate(Date.valueOf(LocalDate.of(2024, 6, 27)));
        
        assertThrows(IllegalStateException.class, () -> dateDto.toTime());
        assertThrows(IllegalStateException.class, () -> dateDto.toTimestamp());
    }
}
