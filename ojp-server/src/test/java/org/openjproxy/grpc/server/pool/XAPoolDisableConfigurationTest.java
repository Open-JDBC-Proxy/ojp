package org.openjproxy.grpc.server.pool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.constants.CommonConstants;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for XA pool disable configuration parsing.
 * Tests the parsing and application of the ojp.xa.connection.pool.enabled property.
 */
class XAPoolDisableConfigurationTest {

    @BeforeEach
    void setUp() {
        // Clear cache before each test
        DataSourceConfigurationManager.clearCache();
    }

    @Test
    void testXAPoolDisabledPropertyParsing() {
        Properties props = new Properties();
        props.setProperty(CommonConstants.XA_POOL_ENABLED_PROPERTY, "false");
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "xaTestDS");

        DataSourceConfigurationManager.XADataSourceConfiguration config =
                DataSourceConfigurationManager.getXAConfiguration(props);

        assertFalse(config.isPoolEnabled(),
                "XA pool should be disabled when property is false");
        assertEquals("xaTestDS", config.getDataSourceName());
    }

    @Test
    void testXAPoolEnabledByDefault() {
        Properties props = new Properties();
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "xaDefaultDS");

        DataSourceConfigurationManager.XADataSourceConfiguration config =
                DataSourceConfigurationManager.getXAConfiguration(props);

        assertTrue(config.isPoolEnabled(),
                "XA pool should be enabled by default when property is not specified");
        assertEquals("xaDefaultDS", config.getDataSourceName());
    }

    @Test
    void testXAPoolDisabledWithNamedDataSource() {
        Properties props = new Properties();
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "xaNamedDS");
        props.setProperty(CommonConstants.XA_POOL_ENABLED_PROPERTY, "false");
        props.setProperty(CommonConstants.XA_MAXIMUM_POOL_SIZE_PROPERTY, "25");

        DataSourceConfigurationManager.XADataSourceConfiguration config =
                DataSourceConfigurationManager.getXAConfiguration(props);

        assertFalse(config.isPoolEnabled(), "XA named datasource should have pool disabled");
        assertEquals("xaNamedDS", config.getDataSourceName());
        // Pool size properties should still be parsed even if pool is disabled
        assertEquals(25, config.getMaximumPoolSize());
    }

    @Test
    void testXAPoolDisabledInvalidValue() {
        Properties props = new Properties();
        props.setProperty(CommonConstants.XA_POOL_ENABLED_PROPERTY, "maybe");
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "xaInvalidDS");

        DataSourceConfigurationManager.XADataSourceConfiguration config =
                DataSourceConfigurationManager.getXAConfiguration(props);

        // Invalid boolean value should be parsed as false by Boolean.parseBoolean()
        assertFalse(config.isPoolEnabled(),
                "Invalid boolean value should be parsed as false");
    }

    @Test
    void testXAPoolDisabledCaseInsensitive() {
        // Test "FALSE"
        Properties props1 = new Properties();
        props1.setProperty(CommonConstants.XA_POOL_ENABLED_PROPERTY, "FALSE");
        DataSourceConfigurationManager.XADataSourceConfiguration config1 =
                DataSourceConfigurationManager.getXAConfiguration(props1);
        assertFalse(config1.isPoolEnabled(), "XA pool should be disabled with 'FALSE'");

        DataSourceConfigurationManager.clearCache();

        // Test "False"
        Properties props2 = new Properties();
        props2.setProperty(CommonConstants.XA_POOL_ENABLED_PROPERTY, "False");
        DataSourceConfigurationManager.XADataSourceConfiguration config2 =
                DataSourceConfigurationManager.getXAConfiguration(props2);
        assertFalse(config2.isPoolEnabled(), "XA pool should be disabled with 'False'");

        DataSourceConfigurationManager.clearCache();

        // Test "true"
        Properties props3 = new Properties();
        props3.setProperty(CommonConstants.XA_POOL_ENABLED_PROPERTY, "true");
        DataSourceConfigurationManager.XADataSourceConfiguration config3 =
                DataSourceConfigurationManager.getXAConfiguration(props3);
        assertTrue(config3.isPoolEnabled(), "XA pool should be enabled with 'true'");
    }

    @Test
    void testXAPoolDisabledWithNullProperties() {
        DataSourceConfigurationManager.XADataSourceConfiguration config =
                DataSourceConfigurationManager.getXAConfiguration(null);

        assertTrue(config.isPoolEnabled(),
                "XA pool should be enabled by default when properties are null");
        assertEquals("default", config.getDataSourceName());
    }

    @Test
    void testXAPoolDisabledWithEmptyProperties() {
        Properties props = new Properties();

        DataSourceConfigurationManager.XADataSourceConfiguration config =
                DataSourceConfigurationManager.getXAConfiguration(props);

        assertTrue(config.isPoolEnabled(),
                "XA pool should be enabled by default when properties are empty");
    }

    @Test
    void testXAPoolIndependentFromNonXA() {
        // Non-XA pooled
        Properties nonXaProps = new Properties();
        nonXaProps.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "nonXA");
        nonXaProps.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "true");

        DataSourceConfigurationManager.DataSourceConfiguration nonXaConfig =
                DataSourceConfigurationManager.getConfiguration(nonXaProps);

        // XA unpooled
        Properties xaProps = new Properties();
        xaProps.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "xa");
        xaProps.setProperty(CommonConstants.XA_POOL_ENABLED_PROPERTY, "false");

        DataSourceConfigurationManager.XADataSourceConfiguration xaConfig =
                DataSourceConfigurationManager.getXAConfiguration(xaProps);

        // Verify independence
        assertTrue(nonXaConfig.isPoolEnabled(), "Non-XA should be pooled");
        assertFalse(xaConfig.isPoolEnabled(), "XA should be unpooled");
    }

    @Test
    void testMultipleXADatasourcesWithDifferentPoolSettings() {
        // XA DS1: Pool enabled
        Properties props1 = new Properties();
        props1.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "xaDS1");
        props1.setProperty(CommonConstants.XA_POOL_ENABLED_PROPERTY, "true");
        props1.setProperty(CommonConstants.XA_MAXIMUM_POOL_SIZE_PROPERTY, "30");

        DataSourceConfigurationManager.XADataSourceConfiguration config1 =
                DataSourceConfigurationManager.getXAConfiguration(props1);

        assertTrue(config1.isPoolEnabled(), "XA DS1 should have pool enabled");
        assertEquals(30, config1.getMaximumPoolSize());

        // XA DS2: Pool disabled
        Properties props2 = new Properties();
        props2.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "xaDS2");
        props2.setProperty(CommonConstants.XA_POOL_ENABLED_PROPERTY, "false");
        props2.setProperty(CommonConstants.XA_MAXIMUM_POOL_SIZE_PROPERTY, "15");

        DataSourceConfigurationManager.XADataSourceConfiguration config2 =
                DataSourceConfigurationManager.getXAConfiguration(props2);

        assertFalse(config2.isPoolEnabled(), "XA DS2 should have pool disabled");
        assertEquals(15, config2.getMaximumPoolSize());

        // Verify both are cached independently
        assertTrue(DataSourceConfigurationManager.getCacheSize() >= 2);
    }

    @Test
    void testXAConfigurationCachingWithPoolDisabled() {
        Properties props = new Properties();
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "xaCachedDS");
        props.setProperty(CommonConstants.XA_POOL_ENABLED_PROPERTY, "false");

        // First call should create and cache
        DataSourceConfigurationManager.XADataSourceConfiguration config1 =
                DataSourceConfigurationManager.getXAConfiguration(props);

        // Second call should return cached instance
        DataSourceConfigurationManager.XADataSourceConfiguration config2 =
                DataSourceConfigurationManager.getXAConfiguration(props);

        assertSame(config1, config2, "XA configuration should be cached");
        assertFalse(config1.isPoolEnabled());
        assertFalse(config2.isPoolEnabled());
    }

    @Test
    void testXAPoolDisabledWithAllOtherProperties() {
        Properties props = new Properties();
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "xaFullConfig");
        props.setProperty(CommonConstants.XA_POOL_ENABLED_PROPERTY, "false");
        props.setProperty(CommonConstants.XA_MAXIMUM_POOL_SIZE_PROPERTY, "40");
        props.setProperty(CommonConstants.XA_MINIMUM_IDLE_PROPERTY, "8");
        props.setProperty(CommonConstants.XA_CONNECTION_TIMEOUT_PROPERTY, "25000");
        props.setProperty(CommonConstants.XA_IDLE_TIMEOUT_PROPERTY, "400000");
        props.setProperty(CommonConstants.XA_MAX_LIFETIME_PROPERTY, "1000000");

        DataSourceConfigurationManager.XADataSourceConfiguration config =
                DataSourceConfigurationManager.getXAConfiguration(props);

        assertFalse(config.isPoolEnabled(), "XA pool should be disabled");
        // Other properties should still be parsed correctly
        assertEquals(40, config.getMaximumPoolSize());
        assertEquals(8, config.getMinimumIdle());
        assertEquals(25000, config.getConnectionTimeout());
        assertEquals(400000, config.getIdleTimeout());
        assertEquals(1000000, config.getMaxLifetime());
    }
}
