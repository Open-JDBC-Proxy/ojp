package org.openjproxy.grpc;

import com.openjproxy.grpc.ParameterProto;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.dto.Parameter;
import org.openjproxy.grpc.dto.ParameterType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test to verify that java.time types work end-to-end through the Parameter DTO to ParameterProto conversion.
 * This test simulates what happens when a user passes java.time types to a PreparedStatement.
 */
class JavaTimeIntegrationTest {

    @Test
    void testLocalDateTimeEndToEnd() {
        // Simulate user code: passing LocalDateTime as a parameter
        LocalDateTime localDateTime = LocalDateTime.of(2024, 11, 2, 14, 30, 45, 123456789);
        
        // Build Parameter DTO (as done by PreparedStatement)
        Parameter param = Parameter.builder()
            .index(1)
            .type(ParameterType.TIMESTAMP)
            .values(Collections.singletonList(localDateTime))
            .build();
        
        // Convert to proto (this is where the original error occurred)
        // This should not throw IllegalArgumentException anymore
        ParameterProto proto = ProtoConverter.toProto(param);
        assertNotNull(proto);
        
        // Convert back to verify round-trip
        Parameter result = ProtoConverter.fromProto(proto);
        assertNotNull(result);
        assertEquals(1, result.getValues().size());
        
        // Result should be LocalDateTime (with original_type preservation)
        Object value = result.getValues().get(0);
        assertInstanceOf(LocalDateTime.class, value);
        assertEquals(localDateTime, value);
    }
    
    @Test
    void testLocalDateEndToEnd() {
        LocalDate localDate = LocalDate.of(2024, 11, 2);
        
        Parameter param = Parameter.builder()
            .index(1)
            .type(ParameterType.DATE)
            .values(Collections.singletonList(localDate))
            .build();
        
        // Should not throw IllegalArgumentException
        ParameterProto proto = ProtoConverter.toProto(param);
        assertNotNull(proto);
        
        Parameter result = ProtoConverter.fromProto(proto);
        assertNotNull(result);
        assertEquals(1, result.getValues().size());
        
        // Result will be java.sql.Date (standard for DATE type)
        Object value = result.getValues().get(0);
        assertInstanceOf(java.sql.Date.class, value);
        assertEquals(localDate, ((java.sql.Date) value).toLocalDate());
    }
    
    @Test
    void testLocalTimeEndToEnd() {
        LocalTime localTime = LocalTime.of(14, 30, 45, 123456789);
        
        Parameter param = Parameter.builder()
            .index(1)
            .type(ParameterType.TIME)
            .values(Collections.singletonList(localTime))
            .build();
        
        // Should not throw IllegalArgumentException
        ParameterProto proto = ProtoConverter.toProto(param);
        assertNotNull(proto);
        
        Parameter result = ProtoConverter.fromProto(proto);
        assertNotNull(result);
        assertEquals(1, result.getValues().size());
        
        // Result will be java.sql.Time (standard for TIME type)
        Object value = result.getValues().get(0);
        assertInstanceOf(java.sql.Time.class, value);
    }
    
    @Test
    void testInstantEndToEnd() {
        Instant instant = Instant.now();
        
        Parameter param = Parameter.builder()
            .index(1)
            .type(ParameterType.TIMESTAMP)
            .values(Collections.singletonList(instant))
            .build();
        
        // Should not throw IllegalArgumentException
        ParameterProto proto = ProtoConverter.toProto(param);
        assertNotNull(proto);
        
        Parameter result = ProtoConverter.fromProto(proto);
        assertNotNull(result);
        assertEquals(1, result.getValues().size());
        
        // Result should be Instant (with original_type preservation)
        Object value = result.getValues().get(0);
        assertInstanceOf(Instant.class, value);
        assertEquals(instant, value);
    }
    
    @Test
    void testOffsetDateTimeEndToEnd() {
        OffsetDateTime offsetDateTime = OffsetDateTime.of(2024, 11, 2, 14, 30, 45, 123456789, ZoneOffset.ofHours(2));
        
        Parameter param = Parameter.builder()
            .index(1)
            .type(ParameterType.TIMESTAMP)
            .values(Collections.singletonList(offsetDateTime))
            .build();
        
        // Should not throw IllegalArgumentException
        ParameterProto proto = ProtoConverter.toProto(param);
        assertNotNull(proto);
        
        Parameter result = ProtoConverter.fromProto(proto);
        assertNotNull(result);
        assertEquals(1, result.getValues().size());
        
        // Result should be OffsetDateTime (with original_type preservation)
        Object value = result.getValues().get(0);
        assertInstanceOf(OffsetDateTime.class, value);
        assertEquals(offsetDateTime.toInstant(), ((OffsetDateTime) value).toInstant());
    }
    
    @Test
    void testOffsetTimeEndToEnd() {
        OffsetTime offsetTime = OffsetTime.of(14, 30, 45, 123456789, ZoneOffset.ofHours(2));
        
        Parameter param = Parameter.builder()
            .index(1)
            .type(ParameterType.TIMESTAMP)
            .values(Collections.singletonList(offsetTime))
            .build();
        
        // Should not throw IllegalArgumentException
        ParameterProto proto = ProtoConverter.toProto(param);
        assertNotNull(proto);
        
        Parameter result = ProtoConverter.fromProto(proto);
        assertNotNull(result);
        assertEquals(1, result.getValues().size());
        
        // Result should be OffsetTime (with original_type preservation)
        Object value = result.getValues().get(0);
        assertInstanceOf(OffsetTime.class, value);
        OffsetTime resultOt = (OffsetTime) value;
        assertEquals(offsetTime.getHour(), resultOt.getHour());
        assertEquals(offsetTime.getMinute(), resultOt.getMinute());
        assertEquals(offsetTime.getSecond(), resultOt.getSecond());
    }
    
    @Test
    void testMultipleJavaTimeParametersInSameRequest() {
        // Simulate a PreparedStatement with multiple java.time parameters
        LocalDateTime ldt = LocalDateTime.of(2024, 11, 2, 14, 30, 45);
        LocalDate ld = LocalDate.of(2024, 11, 2);
        LocalTime lt = LocalTime.of(14, 30, 45);
        Instant instant = Instant.now();
        
        Parameter param1 = Parameter.builder()
            .index(1)
            .type(ParameterType.TIMESTAMP)
            .values(Collections.singletonList(ldt))
            .build();
        
        Parameter param2 = Parameter.builder()
            .index(2)
            .type(ParameterType.DATE)
            .values(Collections.singletonList(ld))
            .build();
        
        Parameter param3 = Parameter.builder()
            .index(3)
            .type(ParameterType.TIME)
            .values(Collections.singletonList(lt))
            .build();
        
        Parameter param4 = Parameter.builder()
            .index(4)
            .type(ParameterType.TIMESTAMP)
            .values(Collections.singletonList(instant))
            .build();
        
        // All should convert without error
        assertDoesNotThrow(() -> {
            ParameterProto proto1 = ProtoConverter.toProto(param1);
            ParameterProto proto2 = ProtoConverter.toProto(param2);
            ParameterProto proto3 = ProtoConverter.toProto(param3);
            ParameterProto proto4 = ProtoConverter.toProto(param4);
            
            assertNotNull(proto1);
            assertNotNull(proto2);
            assertNotNull(proto3);
            assertNotNull(proto4);
        });
    }
    
    @Test
    void testOriginalIssueScenario() {
        // This is the exact scenario from the issue: 
        // User has an entity with LocalDateTime column and tries to insert it
        LocalDateTime createdAt = LocalDateTime.now();
        LocalDateTime updatedAt = LocalDateTime.now();
        
        // Simulate what happens in Spring Data JDBC when it calls PreparedStatement.setObject
        Parameter param1 = Parameter.builder()
            .index(1)
            .type(ParameterType.OBJECT)
            .values(Collections.singletonList(createdAt))
            .build();
        
        Parameter param2 = Parameter.builder()
            .index(2)
            .type(ParameterType.OBJECT)
            .values(Collections.singletonList(updatedAt))
            .build();
        
        // This was throwing IllegalArgumentException before the fix
        assertDoesNotThrow(() -> {
            ParameterProto proto1 = ProtoConverter.toProto(param1);
            ParameterProto proto2 = ProtoConverter.toProto(param2);
            
            assertNotNull(proto1);
            assertNotNull(proto2);
            
            // Verify values can be converted back
            Parameter result1 = ProtoConverter.fromProto(proto1);
            Parameter result2 = ProtoConverter.fromProto(proto2);
            
            assertNotNull(result1.getValues().get(0));
            assertNotNull(result2.getValues().get(0));
        });
    }
}
