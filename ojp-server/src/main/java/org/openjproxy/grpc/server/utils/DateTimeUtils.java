package org.openjproxy.grpc.server.utils;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.dto.TemporalData;
import org.openjproxy.grpc.dto.TemporalDataType;

@Slf4j
@UtilityClass
public class DateTimeUtils {

    /**
     * Converts a com.microsoft.sqlserver.jdbc.DateTimeOffset into a java.time.OffsetDateTime.
     *
     * @param rawObject - instance of com.microsoft.sqlserver.jdbc.DateTimeOffset
     * @return java.time.OffsetDateTime
     */
    public OffsetDateTime extractOffsetDateTime(Object rawObject) {
        try {

            if (rawObject == null) return null;

            // Print class name to verify
            log.info("raw object class: " + rawObject.getClass().getName());

            // Step 2: Access getTimestamp and getMinutesOffset via reflection
            Method getTimestampMethod = rawObject.getClass().getMethod("getTimestamp");
            Method getMinutesOffsetMethod = rawObject.getClass().getMethod("getMinutesOffset");

            Timestamp timestamp = (Timestamp) getTimestampMethod.invoke(rawObject);
            int offsetMinutes = (Integer) getMinutesOffsetMethod.invoke(rawObject);

            // Step 3: Convert to OffsetDateTime
            LocalDateTime ldt = timestamp.toLocalDateTime();
            ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetMinutes * 60);
            return OffsetDateTime.of(ldt, offset);

        } catch (Exception e) {
            throw new RuntimeException("Failed to convert DateTimeOffset to OffsetDateTime", e);
        }
    }

    public static List<Object> translateTemporalParams(List<Object> paramsReceived) {
        // Convert TemporalData objects back to Date/Time/Timestamp for method invocation
        if (paramsReceived != null && !paramsReceived.isEmpty()) {
            // Create a mutable copy of the list to allow modifications
            paramsReceived = new ArrayList<>(paramsReceived);

            log.debug("Parameters before TemporalData conversion: {}",
                    paramsReceived.stream().map(p -> p == null ? "null" : p.getClass().getName()).toList());

            for (int i = 0; i < paramsReceived.size(); i++) {
                Object param = paramsReceived.get(i);
                if (param instanceof TemporalData) {
                    TemporalData temporalData = (TemporalData) param;
                    try {
                        log.debug("Converting TemporalData at index {}: timeMillis={}, nanos={}, timezoneId={}",
                                i, temporalData.getTimeMillis(), temporalData.getNanos(), temporalData.getTimezoneId());

                        // Convert based on method name or precision requirements
                        if (TemporalDataType.TIMESTAMP.equals(temporalData.getTemporalDataType())) {
                            // Has sub-millisecond precision or method is setTimestamp, use Timestamp
                            Timestamp timestamp = new Timestamp(temporalData.getTimeMillis());
                            timestamp.setNanos(temporalData.getNanos());
                            paramsReceived.set(i, timestamp);
                            log.debug("Converted TemporalData at index {} to Timestamp", i);
                        } else if (TemporalDataType.TIME.equals(temporalData.getTemporalDataType())) {
                            // Method is setTime, use java.sql.Time
                            java.sql.Time time = new java.sql.Time(temporalData.getTimeMillis());
                            paramsReceived.set(i, time);
                            log.debug("Converted TemporalData at index {} to Time", i);
                        } else {
                            // Method is setDate or unknown, use java.sql.Date
                            java.sql.Date date = new java.sql.Date(temporalData.getTimeMillis());
                            paramsReceived.set(i, date);
                            log.debug("Converted TemporalData at index {} to Date", i);
                        }

                        // Check if there's a Calendar parameter following this TemporalData
                        // If yes, replace it with a Calendar based on the timezone from TemporalData
                        if (i + 1 < paramsReceived.size() && temporalData.getTimezoneId() != null) {
                            Object nextParam = paramsReceived.get(i + 1);
                            log.debug("Next parameter at index {} is type: {}", i + 1,
                                    nextParam == null ? "null" : nextParam.getClass().getName());
                            // The next param might be a Calendar (or serialized Calendar)
                            // Replace it with a proper Calendar from the timezone
                            if (nextParam != null) {
                                Calendar cal = Calendar.getInstance(TimeZone.getTimeZone(temporalData.getTimezoneId()));
                                paramsReceived.set(i + 1, cal);
                                log.debug("Replaced Calendar at index {} with timezone {}", i + 1, temporalData.getTimezoneId());
                            }
                        }
                    } catch (Exception e) {
                        log.error("Error converting TemporalData at index {}: {} - TemporalData: {}",
                                i, e.getMessage(), temporalData, e);
                        throw new RuntimeException("Failed to convert TemporalData at index " + i +
                                ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
                    }
                }
            }
            log.debug("Parameters after TemporalData conversion: {}",
                    paramsReceived.stream().map(p -> p == null ? "null" : p.getClass().getName()).toList());
        }

        return paramsReceived;
    }
}
