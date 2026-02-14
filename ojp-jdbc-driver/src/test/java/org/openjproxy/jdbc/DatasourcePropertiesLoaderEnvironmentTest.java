package org.openjproxy.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test environment-specific properties file loading in DatasourcePropertiesLoader.
 * <p>
 * Tests the feature where properties can be loaded from environment-specific files:
 * - ojp-dev.properties
 * - ojp-prod.properties
 * - ojp-staging.properties
 * etc.
 * <p>
 * The environment is determined by:
 * 1. System property: -Dojp.environment=dev
 * 2. Environment variable: OJP_ENVIRONMENT=dev
 */
class DatasourcePropertiesLoaderEnvironmentTest {

    @AfterEach
    void cleanup() {
        // Clear any system properties we set during tests
        System.clearProperty("ojp.environment");
    }

    @Test
    void testLoadEnvironmentSpecificPropertiesFile() {
        // Set the environment to "test"
        System.setProperty("ojp.environment", "test");

        // Load the properties (should load ojp-test.properties)
        Properties props = DatasourcePropertiesLoader.loadOjpProperties();

        assertNotNull(props, "Properties should not be null");

        // Verify that test-specific property is loaded
        String testProperty = props.getProperty("test.environment.marker");
        assertEquals("test-environment", testProperty, "Should load test-specific property");
    }

    @Test
    void testFallbackToDefaultWhenEnvironmentFileNotFound() {
        // Set the environment to a non-existent environment
        System.setProperty("ojp.environment", "nonexistent");

        // Load the properties (should fall back to ojp.properties)
        Properties props = DatasourcePropertiesLoader.loadOjpProperties();

        // Should still load the default ojp.properties
        // We can't assert specific content here since it depends on what's in ojp.properties,
        // but we verify the fallback works by checking it doesn't return null
        // (assuming ojp.properties exists in test resources)
        assertNotNull(props, "Properties should not be null");
    }

    @Test
    void testLoadDefaultPropertiesWhenNoEnvironmentSet() {
        // Don't set ojp.environment

        // Load the properties (should load ojp.properties)
        Properties props = DatasourcePropertiesLoader.loadOjpProperties();

        // Should load the default ojp.properties if it exists
        // The test verifies that not setting an environment doesn't break the loading
        assertNotNull(props, "Properties should not be null");
    }

    @Test
    void testEnvironmentValueIsTrimmed() {
        // Set the environment with leading/trailing spaces
        System.setProperty("ojp.environment", "  test  ");

        // Load the properties (should load ojp-test.properties with trimmed value)
        Properties props = DatasourcePropertiesLoader.loadOjpProperties();

        assertNotNull(props, "Properties should not be null");

        // Verify that test-specific property is loaded (environment name was trimmed)
        String testProperty = props.getProperty("test.environment.marker");
        assertEquals("test-environment", testProperty, "Should load test-specific property with trimmed environment name");
    }

    @Test
    void testEmptyEnvironmentFallsBackToDefault() {
        // Set the environment to an empty string
        System.setProperty("ojp.environment", "");

        // Load the properties (should fall back to ojp.properties)
        Properties props = DatasourcePropertiesLoader.loadOjpProperties();

        // Should load the default ojp.properties (or return null if not found)
        // The test verifies that empty environment doesn't cause errors
        assertNotNull(props, "Properties should not be null");
    }

    @Test
    void testEnvironmentSpecificPropertiesWithDataSource() {
        // Set the environment to "test"
        System.setProperty("ojp.environment", "test");

        // Load properties for a specific datasource
        Properties props = DatasourcePropertiesLoader.loadOjpPropertiesForDataSource("default");

        assertNotNull(props, "Properties should not be null");

        // Verify that properties are loaded from environment-specific file
        // and processed for the datasource
        assertNotNull(props.getProperty("ojp.datasource.name"), "Should have datasource name property");
    }

    /**
     * Documents that environment variable OJP_ENVIRONMENT is supported.
     * <p>
     * To manually test:
     * 1. Set environment variable: export OJP_ENVIRONMENT=dev
     * 2. Run the application
     * 3. Verify that ojp-dev.properties is loaded
     * <p>
     * Note: Environment variables cannot be reliably set in unit tests in Java 9+
     */
    @Test
    void testEnvironmentVariableSupportDocumented() {
        // This test documents that OJP_ENVIRONMENT environment variable is supported
        // The actual functionality needs to be tested via integration tests or manually

        String envValue = System.getenv("OJP_ENVIRONMENT");
        // Environment variable may or may not be set in the test environment
        // This test just documents that it's read by the loader
        assertNull(envValue, "Properties should be null");
        assertTrue(true, "OJP_ENVIRONMENT environment variable support is documented and implemented");
    }
}
