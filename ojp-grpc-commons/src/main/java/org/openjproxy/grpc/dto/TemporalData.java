package org.openjproxy.grpc.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * DTO for timezone-aware temporal data (Date, Time, Timestamp).
 * Stores time in milliseconds since epoch and the timezone ID.
 * This ensures consistent date/time handling across different timezones
 * between the client and server.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemporalData implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * Time in milliseconds since epoch (UTC)
     */
    private long timeMillis;
    
    /**
     * Nanoseconds component (for Timestamp precision)
     */
    private int nanos;
    
    /**
     * Timezone ID (e.g., "America/New_York", "UTC", etc.)
     * If null, uses the default timezone
     */
    private String timezoneId;
}
