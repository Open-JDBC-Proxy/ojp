package org.openjproxy.grpc.server.pool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.constants.CommonConstants;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simplified unit tests for Non-XA unpooled connection configuration.
 * These tests validate that the configuration system properly handles
 * pool enable/disable settings.
 */
class NonXAUnpooledConnectionManagementTest {

    @BeforeEach
    void setUp() {
        // Clear cache before each test
        DataSourceConfigurationManager.clearCache();
    }

    @Test
    void testUnpooledConfigurationIsRespected() {
        Properties props = new Properties();
        props.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "false");
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "unpooledDS");
        props.setProperty(CommonConstants.CONNECTION_TIMEOUT_PROPERTY, "20000");

        DataSourceConfigurationManager.DataSourceConfiguration config =
                DataSourceConfigurationManager.getConfiguration(props);

        assertFalse(config.isPoolEnabled(), "Pool should be disabled");
        assertEquals("unpooledDS", config.getDataSourceName());
        assertEquals(20000, config.getConnectionTimeout());
    }

    @Test
    void testPooledConfigurationIsRespected() {
        Properties props = new Properties();
        props.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "true");
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "pooledDS");
        props.setProperty(CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY, "25");

        DataSourceConfigurationManager.DataSourceConfiguration config =
                DataSourceConfigurationManager.getConfiguration(props);

        assertTrue(config.isPoolEnabled(), "Pool should be enabled");
        assertEquals("pooledDS", config.getDataSourceName());
        assertEquals(25, config.getMaximumPoolSize());
    }

    @Test
    void testMultipleUnpooledConfigurations() {
        // First unpooled datasource
        Properties props1 = new Properties();
        props1.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "false");
        props1.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "unpooled1");

        DataSourceConfigurationManager.DataSourceConfiguration config1 =
                DataSourceConfigurationManager.getConfiguration(props1);

        // Second unpooled datasource
        Properties props2 = new Properties();
        props2.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "false");
        props2.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "unpooled2");

        DataSourceConfigurationManager.DataSourceConfiguration config2 =
                DataSourceConfigurationManager.getConfiguration(props2);

        assertFalse(config1.isPoolEnabled(), "First datasource should be unpooled");
        assertFalse(config2.isPoolEnabled(), "Second datasource should be unpooled");
        assertNotSame(config1, config2, "Configurations should be independent");
    }

    @Test
    void testMixedPooledAndUnpooledConfigurations() {
        // Pooled datasource
        Properties pooledProps = new Properties();
        pooledProps.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "true");
        pooledProps.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "pooled");
        pooledProps.setProperty(CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY, "20");

        DataSourceConfigurationManager.DataSourceConfiguration pooledConfig =
                DataSourceConfigurationManager.getConfiguration(pooledProps);

        // Unpooled datasource
        Properties unpooledProps = new Properties();
        unpooledProps.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "false");
        unpooledProps.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "unpooled");

        DataSourceConfigurationManager.DataSourceConfiguration unpooledConfig =
                DataSourceConfigurationManager.getConfiguration(unpooledProps);

        assertTrue(pooledConfig.isPoolEnabled(), "Pooled datasource should have pooling");
        assertFalse(unpooledConfig.isPoolEnabled(), "Unpooled datasource should not have pooling");
        assertEquals(20, pooledConfig.getMaximumPoolSize());
    }

    @Test
    void testUnpooledConnectionDetailsStorageFormat() {
        // Verify that unpooled mode stores all necessary connection details
        Properties props = new Properties();
        props.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "false");
        props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "detailsTest");
        props.setProperty(CommonConstants.CONNECTION_TIMEOUT_PROPERTY, "15000");
        props.setProperty(CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY, "10");
        props.setProperty(CommonConstants.MINIMUM_IDLE_PROPERTY, "2");

        DataSourceConfigurationManager.DataSourceConfiguration config =
                DataSourceConfigurationManager.getConfiguration(props);

        assertFalse(config.isPoolEnabled());
        assertEquals("detailsTest", config.getDataSourceName());
        assertEquals(15000, config.getConnectionTimeout());
        // Pool settings should still be parsed even if not used
        assertEquals(10, config.getMaximumPoolSize());
        assertEquals(2, config.getMinimumIdle());
    }

    @Test
    void testConfigurationIndependenceAcrossDataSources() {
        // Create three different datasource's with different pool settings
        Properties ds1Props = new Properties();
        ds1Props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "ds1");
        ds1Props.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "true");

        Properties ds2Props = new Properties();
        ds2Props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "ds2");
        ds2Props.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "false");

        Properties ds3Props = new Properties();
        ds3Props.setProperty(CommonConstants.DATASOURCE_NAME_PROPERTY, "ds3");
        ds3Props.setProperty(CommonConstants.POOL_ENABLED_PROPERTY, "true");

        DataSourceConfigurationManager.DataSourceConfiguration config1 =
                DataSourceConfigurationManager.getConfiguration(ds1Props);
        DataSourceConfigurationManager.DataSourceConfiguration config2 =
                DataSourceConfigurationManager.getConfiguration(ds2Props);
        DataSourceConfigurationManager.DataSourceConfiguration config3 =
                DataSourceConfigurationManager.getConfiguration(ds3Props);

        assertTrue(config1.isPoolEnabled());
        assertFalse(config2.isPoolEnabled());
        assertTrue(config3.isPoolEnabled());

        // All three should be cached independently
        assertEquals(3, DataSourceConfigurationManager.getCacheSize());
    }
}
