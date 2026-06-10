package org.openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.Properties;

/**
 * Reads the driver version from the bundled {@code driver-version.properties}
 * resource, which is populated by Maven at build time via resource filtering.
 */
@Slf4j
public class DriverVersion {

    static final String UNKNOWN = "unknown";

    private static final int DEFAULT_MAJOR = 0;
    private static final int DEFAULT_MINOR = 0;

    private static final int MAJOR_INDEX = 0;
    private static final int MINOR_INDEX = 1;

    private static final int MAJOR_VERSION;
    private static final int MINOR_VERSION;
    private static final String VERSION_STRING;

    static {
        String raw = loadVersionString();
        VERSION_STRING = raw;
        int[] parts = parseVersion(raw);
        MAJOR_VERSION = parts[MAJOR_INDEX];
        MINOR_VERSION = parts[MINOR_INDEX];
    }

    private DriverVersion() {
    }

    public static int getMajorVersion() {
        return MAJOR_VERSION;
    }

    public static int getMinorVersion() {
        return MINOR_VERSION;
    }

    public static String getVersionString() {
        return VERSION_STRING;
    }

    private static String loadVersionString() {
        try (InputStream is = DriverVersion.class.getResourceAsStream("driver-version.properties")) {
            if (is == null) {
                log.warn("driver-version.properties not found on classpath; version will default to {}.{}",
                        DEFAULT_MAJOR, DEFAULT_MINOR);
                return UNKNOWN;
            }
            Properties props = new Properties();
            props.load(is);
            String version = props.getProperty("version");
            if (version == null || version.isBlank()) {
                return UNKNOWN;
            }
            return version.trim();
        } catch (Exception e) {
            log.warn("Failed to read driver-version.properties: {}", e.getMessage());
            return UNKNOWN;
        }
    }

    private static int[] parseVersion(String version) {
        int major = DEFAULT_MAJOR;
        int minor = DEFAULT_MINOR;
        if (version == null || UNKNOWN.equals(version)) {
            return new int[]{major, minor};
        }
        // Strip any qualifier like -SNAPSHOT, -beta, -rc1, etc.
        String numeric = version.split("-")[0];
        String[] parts = numeric.split("\\.");
        try {
            if (parts.length > MAJOR_INDEX) {
                major = Integer.parseInt(parts[MAJOR_INDEX]);
            }
            if (parts.length > MINOR_INDEX) {
                minor = Integer.parseInt(parts[MINOR_INDEX]);
            }
        } catch (NumberFormatException e) {
            log.warn("Could not parse version '{}'; defaulting to {}.{}", version, DEFAULT_MAJOR, DEFAULT_MINOR);
        }
        return new int[]{major, minor};
    }
}
