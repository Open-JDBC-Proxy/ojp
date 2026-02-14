package org.openjproxy.grpc.server.pool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.constants.CommonConstants;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Non-XA pool disable configuration parsing.
 * Tests the parsing and application of the ojp.connection.pool.enabled property.
 */
class NonXAPoolDisableConfigurationTest {

    @BeforeEach
    void setUp() {
        // Clear cache before each test
        DataSourceConfigurationManager.clearCache();
    }

    @Test
    void testPoolDisabledPropertyParsing() {
        Properties props = new Properties();
        props.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "false");
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "testDS");

        DataSourceConfigurationManager.DataSourceConfiguration config =
                DataSourceConfigurationManager.getConfiguration(props);

        assertFalse(config.isPoolEnabled(),
                "Pool should be disabled when property is false");
        assertEquals("testDS", config.getDataSourceName());
    }

    @Test
    void testPoolEnabledByDefault() {
        Properties props = new Properties();
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "defaultDS");

        DataSourceConfigurationManager.DataSourceConfiguration config =
                DataSourceConfigurationManager.getConfiguration(props);

        assertTrue(config.isPoolEnabled(),
                "Pool should be enabled by default when property is not specified");
        assertEquals("defaultDS", config.getDataSourceName());
    }

    @Test
    void testPoolDisabledWithNamedDataSource() {
        Properties props = new Properties();
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "namedDS");
        props.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "false");
        props.setProperty(CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY, "25");

        DataSourceConfigurationManager.DataSourceConfiguration config =
                DataSourceConfigurationManager.getConfiguration(props);

        assertFalse(config.isPoolEnabled(), "Named datasource should have pool disabled");
        assertEquals("namedDS", config.getDataSourceName());
        // Pool size properties should still be parsed even if pool is disabled
        assertEquals(25, config.getMaximumPoolSize());
    }

    @Test
    void testPoolDisabledInvalidValue() {
        Properties props = new Properties();
        props.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "maybe");
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "invalidDS");

        DataSourceConfigurationManager.DataSourceConfiguration config =
                DataSourceConfigurationManager.getConfiguration(props);

        // Invalid boolean value should be parsed as false by Boolean.parseBoolean()
        assertFalse(config.isPoolEnabled(),
                "Invalid boolean value should be parsed as false");
    }

    @Test
    void testPoolDisabledCaseInsensitive() {
        // Test "FALSE"
        Properties props1 = new Properties();
        props1.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "FALSE");
        DataSourceConfigurationManager.DataSourceConfiguration config1 =
                DataSourceConfigurationManager.getConfiguration(props1);
        assertFalse(config1.isPoolEnabled(), "Pool should be disabled with 'FALSE'");

        DataSourceConfigurationManager.clearCache();

        // Test "False"
        Properties props2 = new Properties();
        props2.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "False");
        DataSourceConfigurationManager.DataSourceConfiguration config2 =
                DataSourceConfigurationManager.getConfiguration(props2);
        assertFalse(config2.isPoolEnabled(), "Pool should be disabled with 'False'");

        DataSourceConfigurationManager.clearCache();

        // Test "true"
        Properties props3 = new Properties();
        props3.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "true");
        DataSourceConfigurationManager.DataSourceConfiguration config3 =
                DataSourceConfigurationManager.getConfiguration(props3);
        assertTrue(config3.isPoolEnabled(), "Pool should be enabled with 'true'");
    }

    @Test
    void testPoolDisabledWithNullProperties() {
        DataSourceConfigurationManager.DataSourceConfiguration config =
                DataSourceConfigurationManager.getConfiguration(null);

        assertTrue(config.isPoolEnabled(),
                "Pool should be enabled by default when properties are null");
        assertEquals("default", config.getDataSourceName());
    }

    @Test
    void testPoolDisabledWithEmptyProperties() {
        Properties props = new Properties();

        DataSourceConfigurationManager.DataSourceConfiguration config =
                DataSourceConfigurationManager.getConfiguration(props);

        assertTrue(config.isPoolEnabled(),
                "Pool should be enabled by default when properties are empty");
    }

    @Test
    void testMultipleDatasourcesWithDifferentPoolSettings() {
        // DS1: Pool enabled
        Properties props1 = new Properties();
        props1.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "ds1");
        props1.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "true");
        props1.setProperty(CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY, "20");

        DataSourceConfigurationManager.DataSourceConfiguration config1 =
                DataSourceConfigurationManager.getConfiguration(props1);

        assertTrue(config1.isPoolEnabled(), "DS1 should have pool enabled");
        assertEquals(20, config1.getMaximumPoolSize());

        // DS2: Pool disabled
        Properties props2 = new Properties();
        props2.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "ds2");
        props2.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "false");
        props2.setProperty(CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY, "10");

        DataSourceConfigurationManager.DataSourceConfiguration config2 =
                DataSourceConfigurationManager.getConfiguration(props2);

        assertFalse(config2.isPoolEnabled(), "DS2 should have pool disabled");
        assertEquals(10, config2.getMaximumPoolSize());

        // Verify both are cached independently
        assertEquals(2, DataSourceConfigurationManager.getCacheSize());
    }

    @Test
    void testConfigurationCachingWithPoolDisabled() {
        Properties props = new Properties();
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "cachedDS");
        props.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "false");

        // First call should create and cache
        DataSourceConfigurationManager.DataSourceConfiguration config1 =
                DataSourceConfigurationManager.getConfiguration(props);

        // Second call should return cached instance
        DataSourceConfigurationManager.DataSourceConfiguration config2 =
                DataSourceConfigurationManager.getConfiguration(props);

        assertSame(config1, config2, "Configuration should be cached");
        assertFalse(config1.isPoolEnabled());
        assertFalse(config2.isPoolEnabled());
    }

    @Test
    void testPoolDisabledWithAllOtherProperties() {
        Properties props = new Properties();
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "fullConfig");
        props.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "false");
        props.setProperty(CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY, "30");
        props.setProperty(CommonConstants.MINIMUM_IDLE_PROPERTY, "5");
        props.setProperty(CommonConstants.CONNECTION_TIMEOUT_PROPERTY, "15000");
        props.setProperty(CommonConstants.IDLE_TIMEOUT_PROPERTY, "300000");
        props.setProperty(CommonConstants.MAX_LIFETIME_PROPERTY, "900000");

        DataSourceConfigurationManager.DataSourceConfiguration config =
                DataSourceConfigurationManager.getConfiguration(props);

        assertFalse(config.isPoolEnabled(), "Pool should be disabled");
        // Other properties should still be parsed correctly
        assertEquals(30, config.getMaximumPoolSize());
        assertEquals(5, config.getMinimumIdle());
        assertEquals(15000, config.getConnectionTimeout());
        assertEquals(300000, config.getIdleTimeout());
        assertEquals(900000, config.getMaxLifetime());
    }
}
