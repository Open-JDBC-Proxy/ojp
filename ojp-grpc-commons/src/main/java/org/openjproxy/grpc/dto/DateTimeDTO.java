package org.openjproxy.grpc.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;

/**
 * Data Transfer Object for date/time values to ensure consistent serialization
 * between client and server regardless of timezone settings.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DateTimeDTO implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * Type of date/time value: DATE, TIME, or TIMESTAMP
     */
    private DateTimeType type;
    
    /**
     * Milliseconds since epoch (1970-01-01 00:00:00 GMT)
     */
    private long milliseconds;
    
    /**
     * Nanoseconds component (for Timestamp precision)
     */
    private long nanoseconds;
    
    /**
     * Create a DateTimeDTO from a java.sql.Date
     */
    public static DateTimeDTO fromDate(Date date) {
        if (date == null) {
            return null;
        }
        return DateTimeDTO.builder()
                .type(DateTimeType.DATE)
                .milliseconds(date.getTime())
                .nanoseconds(0)
                .build();
    }
    
    /**
     * Create a DateTimeDTO from a java.sql.Time
     */
    public static DateTimeDTO fromTime(Time time) {
        if (time == null) {
            return null;
        }
        return DateTimeDTO.builder()
                .type(DateTimeType.TIME)
                .milliseconds(time.getTime())
                .nanoseconds(0)
                .build();
    }
    
    /**
     * Create a DateTimeDTO from a java.sql.Timestamp
     */
    public static DateTimeDTO fromTimestamp(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return DateTimeDTO.builder()
                .type(DateTimeType.TIMESTAMP)
                .milliseconds(timestamp.getTime())
                .nanoseconds(timestamp.getNanos())
                .build();
    }
    
    /**
     * Convert this DTO back to a java.sql.Date
     */
    public Date toDate() {
        if (type != DateTimeType.DATE) {
            throw new IllegalStateException("Cannot convert " + type + " to Date");
        }
        return new Date(milliseconds);
    }
    
    /**
     * Convert this DTO back to a java.sql.Time
     */
    public Time toTime() {
        if (type != DateTimeType.TIME) {
            throw new IllegalStateException("Cannot convert " + type + " to Time");
        }
        return new Time(milliseconds);
    }
    
    /**
     * Convert this DTO back to a java.sql.Timestamp
     */
    public Timestamp toTimestamp() {
        if (type != DateTimeType.TIMESTAMP) {
            throw new IllegalStateException("Cannot convert " + type + " to Timestamp");
        }
        Timestamp timestamp = new Timestamp(milliseconds);
        timestamp.setNanos((int) nanoseconds);
        return timestamp;
    }
    
    /**
     * Convert this DTO to the appropriate java.sql type based on its type field
     */
    public Object toSqlType() {
        if (this == null) {
            return null;
        }
        switch (type) {
            case DATE:
                return toDate();
            case TIME:
                return toTime();
            case TIMESTAMP:
                return toTimestamp();
            default:
                throw new IllegalStateException("Unknown DateTimeType: " + type);
        }
    }
    
    public enum DateTimeType {
        DATE,
        TIME,
        TIMESTAMP
    }
}
