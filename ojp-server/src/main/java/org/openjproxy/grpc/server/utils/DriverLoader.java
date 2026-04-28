package org.openjproxy.grpc.server.utils;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Utility class for loading JDBC drivers from a configurable directory at runtime.
 * This allows customers to add proprietary drivers without recompiling OJP Server.
 */
@Slf4j
@UtilityClass
public class DriverLoader {

    /**
     * Loads all JAR files from the specified directory into the classpath.
     * Creates the directory if it doesn't exist.
     *
     * @param driversPath Path to the directory containing external library JAR files
     * @return true if loading was successful (even if no JARs found), false on error
     */
    public boolean loadDriversFromPath(String driversPath) {
        if (driversPath == null || driversPath.trim().isEmpty()) {
            log.debug("No external libraries path configured, skipping external driver loading");
            return true;
        }

        Path driverDir = Paths.get(driversPath);

        // Check if directory exists
        if (!Files.exists(driverDir)) {
            log.info("External libraries directory not found: {}", driverDir.toAbsolutePath());
            log.info("No external libraries will be loaded. To add proprietary drivers, create the directory and place JAR files there.");
            return true;
        }

        // Check if it's a directory
        if (!Files.isDirectory(driverDir)) {
            log.error("External libraries path exists but is not a directory: {}", driverDir.toAbsolutePath());
            return false;
        }

        // Find all JAR files in the directory
        List<File> jarFiles = new ArrayList<>();
        File dir = driverDir.toFile();
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));

        if (files == null || files.length == 0) {
            log.info("No JAR files found in external libraries directory: {}", driverDir.toAbsolutePath());
            return true;
        }

        for (File file : files) {
            jarFiles.add(file);
        }

        // Load JARs into classpath
        try {
            List<URL> urls = new ArrayList<>();
            for (File jarFile : jarFiles) {
                urls.add(jarFile.toURI().toURL());
                log.info("Loading external library JAR: {}", jarFile.getName());
            }

            // Create a new URLClassLoader with the JAR files
            URL[] urlArray = urls.toArray(new URL[0]);
            URLClassLoader classLoader = new URLClassLoader(urlArray, Thread.currentThread().getContextClassLoader());

            // Set the context class loader so JDBC DriverManager can find the drivers
            Thread.currentThread().setContextClassLoader(classLoader);

            // Use ServiceLoader to discover and register JDBC drivers from the loaded JARs
            // This is the standard JDBC 4.0+ mechanism for driver auto-registration
            ServiceLoader<Driver> driverLoader = ServiceLoader.load(Driver.class, classLoader);
            int registeredCount = 0;
            for (Driver driver : driverLoader) {
                try {
                    // Explicitly register the driver with DriverManager
                    DriverManager.registerDriver(new DriverShim(driver));
                    log.info("Registered JDBC driver: {}", driver.getClass().getName());
                    registeredCount++;
                } catch (Exception e) {
                    log.warn("Failed to register JDBC driver: {}", driver.getClass().getName(), e);
                }
            }

            log.info("Successfully loaded {} external library JAR(s) and registered {} JDBC driver(s) from: {}",
                     jarFiles.size(), registeredCount, driverDir.toAbsolutePath());
            return true;

        } catch (Exception e) {
            log.error("Failed to load external library JARs from: {}", driverDir.toAbsolutePath(), e);
            return false;
        }
    }

    /**
     * Wrapper class for JDBC drivers loaded from external class loaders.
     * This is necessary because DriverManager only accepts drivers loaded by the system class loader
     * or its parent. This shim delegates all calls to the actual driver.
     *
     * Package-private visibility allows DriverUtils to check if drivers are properly loaded
     * by inspecting the wrapped driver class name.
     */
    static class DriverShim implements Driver {
        private final Driver driver;

        DriverShim(Driver driver) {
            this.driver = driver;
        }

        /**
         * Get the class name of the wrapped driver.
         * This is useful for checking which driver is actually loaded.
         */
        public String getWrappedDriverClassName() {
            return driver.getClass().getName();
        }

        @Override
        public java.sql.Connection connect(String url, java.util.Properties info) throws java.sql.SQLException {
            return driver.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws java.sql.SQLException {
            return driver.acceptsURL(url);
        }

        @Override
        public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info) throws java.sql.SQLException {
            return driver.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return driver.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return driver.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return driver.jdbcCompliant();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
            return driver.getParentLogger();
        }
    }
}
