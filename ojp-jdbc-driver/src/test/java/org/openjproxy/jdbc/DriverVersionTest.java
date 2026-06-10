package org.openjproxy.jdbc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverVersionTest {

    @Test
    void shouldReturnNonNullVersionString() {
        assertNotNull(DriverVersion.getVersionString());
    }

    @Test
    void shouldReturnNonNegativeMajorVersion() {
        assertTrue(DriverVersion.getMajorVersion() >= 0);
    }

    @Test
    void shouldReturnNonNegativeMinorVersion() {
        assertTrue(DriverVersion.getMinorVersion() >= 0);
    }

    @Test
    void shouldNotReturnUnknownWhenPropertiesFileIsPresent() {
        // The properties file is on the classpath during tests (Maven copies it to target/test-classes)
        assertNotEquals(DriverVersion.UNKNOWN, DriverVersion.getVersionString());
    }

    @Test
    void shouldExposeVersionThroughDriverInterface() {
        Driver driver = new Driver();
        assertEquals(DriverVersion.getMajorVersion(), driver.getMajorVersion());
        assertEquals(DriverVersion.getMinorVersion(), driver.getMinorVersion());
    }
}
