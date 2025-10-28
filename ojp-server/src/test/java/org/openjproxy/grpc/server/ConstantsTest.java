package org.openjproxy.grpc.server;

import org.junit.jupiter.api.Test;

import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ConstantsTest {

    @Test
    void testUtcCalendarConfiguration() {
        assertNotNull(Constants.UTC_CALENDAR, "UTC_CALENDAR should not be null");
        
        TimeZone utcTimeZone = Constants.UTC_CALENDAR.getTimeZone();
        assertEquals("UTC", utcTimeZone.getID(), "UTC_CALENDAR should use UTC timezone");
        assertEquals(TimeZone.getTimeZone("UTC"), utcTimeZone, "UTC_CALENDAR timezone should match UTC");
        assertEquals(0, utcTimeZone.getRawOffset(), "UTC timezone should have 0 offset");
    }
}
