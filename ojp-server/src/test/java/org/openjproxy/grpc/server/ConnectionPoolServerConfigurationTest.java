package org.openjproxy.grpc.server;

import com.openjproxy.grpc.ConnectionDetails;
import org.junit.jupiter.api.Test;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.ProtoConverter;
import org.openjproxy.grpc.server.pool.ConnectionPoolConfigurer;
import org.openjproxy.grpc.server.pool.DataSourceConfigurationManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test for server-side connection pool configuration.
 * Tests that client properties are correctly parsed and applied to pool configuration.
 */
class ConnectionPoolServerConfigurationTest {
    private static final int TWENTY_FIVE = 25;
    private static final int SEVEN = 7;

    @Test
    void testHikariConfigurationWithClientProperties() throws Exception {
        // Create test properties that a client would send
        Properties clientProperties = new Properties();
        clientProperties.setProperty("ojp.connection.pool.maximumPoolSize", "25");
        clientProperties.setProperty("ojp.connection.pool.minimumIdle", "7");

        // Convert properties to PropertyEntry list using ProtoConverter
        Map<String, Object> propertiesMap = new HashMap<>();
        for (String key : clientProperties.stringPropertyNames()) {
            propertiesMap.put(key, clientProperties.getProperty(key));
        }

        // Create ConnectionDetails with properties
        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:testdb")
                .setUser("test")
                .setPassword("test")
                .setClientUUID("test-client")
                .addAllProperties(ProtoConverter.propertiesToProto(propertiesMap))
                .build();

        // Extract client properties and get configuration
        Properties extractedProperties = ConnectionPoolConfigurer.extractClientProperties(connectionDetails);
        DataSourceConfigurationManager.DataSourceConfiguration dsConfig =
                DataSourceConfigurationManager.getConfiguration(extractedProperties);

        // Verify that client properties were applied
        assertEquals(TWENTY_FIVE, dsConfig.getMaximumPoolSize());
        assertEquals(SEVEN, dsConfig.getMinimumIdle());

        // Verify that properties not provided use defaults
        assertEquals(CommonConstants.DEFAULT_IDLE_TIMEOUT, dsConfig.getIdleTimeout());
        assertEquals(CommonConstants.DEFAULT_MAX_LIFETIME, dsConfig.getMaxLifetime());
        assertEquals(CommonConstants.DEFAULT_CONNECTION_TIMEOUT, dsConfig.getConnectionTimeout());
    }

    @Test
    void testHikariConfigurationWithoutClientProperties() throws Exception {
        // Create ConnectionDetails without properties
        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:testdb")
                .setUser("test")
                .setPassword("test")
                .setClientUUID("test-client")
                .build();

        // Extract client properties (will be null)
        Properties extractedProperties = ConnectionPoolConfigurer.extractClientProperties(connectionDetails);
        DataSourceConfigurationManager.DataSourceConfiguration dsConfig =
                DataSourceConfigurationManager.getConfiguration(extractedProperties);

        // Verify that all default values are applied
        assertEquals(CommonConstants.DEFAULT_MAXIMUM_POOL_SIZE, dsConfig.getMaximumPoolSize());
        assertEquals(CommonConstants.DEFAULT_MINIMUM_IDLE, dsConfig.getMinimumIdle());
        assertEquals(CommonConstants.DEFAULT_IDLE_TIMEOUT, dsConfig.getIdleTimeout());
        assertEquals(CommonConstants.DEFAULT_MAX_LIFETIME, dsConfig.getMaxLifetime());
        assertEquals(CommonConstants.DEFAULT_CONNECTION_TIMEOUT, dsConfig.getConnectionTimeout());
    }

    @Test
    void testHikariConfigurationWithInvalidProperties() throws Exception {
        // Create test properties with invalid values
        Properties clientProperties = new Properties();
        clientProperties.setProperty("ojp.connection.pool.maximumPoolSize", "invalid_number");
        clientProperties.setProperty("ojp.connection.pool.minimumIdle", "not_a_number");

        // Convert properties to PropertyEntry list using ProtoConverter
        Map<String, Object> propertiesMap = new HashMap<>();
        for (String key : clientProperties.stringPropertyNames()) {
            propertiesMap.put(key, clientProperties.getProperty(key));
        }

        // Create ConnectionDetails with properties
        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:testdb")
                .setUser("test")
                .setPassword("test")
                .setClientUUID("test-client")
                .addAllProperties(ProtoConverter.propertiesToProto(propertiesMap))
                .build();

        // Extract client properties and get configuration
        Properties extractedProperties = ConnectionPoolConfigurer.extractClientProperties(connectionDetails);
        DataSourceConfigurationManager.DataSourceConfiguration dsConfig =
                DataSourceConfigurationManager.getConfiguration(extractedProperties);

        // Verify that invalid values fall back to defaults
        assertEquals(CommonConstants.DEFAULT_MAXIMUM_POOL_SIZE, dsConfig.getMaximumPoolSize()); // Falls back to default
        assertEquals(CommonConstants.DEFAULT_MINIMUM_IDLE, dsConfig.getMinimumIdle()); // Falls back to default
    }
}