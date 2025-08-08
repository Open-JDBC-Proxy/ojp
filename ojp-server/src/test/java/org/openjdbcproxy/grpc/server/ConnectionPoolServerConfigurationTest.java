package org.openjdbcproxy.grpc.server;

import com.google.protobuf.ByteString;
import com.openjdbcproxy.grpc.ConnectionDetails;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;
import org.openjdbcproxy.constants.CommonConstants;
import org.openjdbcproxy.grpc.SerializationHandler;

import java.lang.reflect.Method;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for server-side connection pool configuration.
 */
public class ConnectionPoolServerConfigurationTest {

    private StatementServiceImpl createTestStatementServiceImpl() {
        ServerConfiguration testConfig = new ServerConfiguration();
        return new StatementServiceImpl(null, null, testConfig);
    }

    @Test
    public void testHikariConfigurationWithClientProperties() throws Exception {
        // Create a StatementServiceImpl instance
        StatementServiceImpl serviceImpl = createTestStatementServiceImpl();
        
        // Create test properties that a client would send
        Properties clientProperties = new Properties();
        clientProperties.setProperty("ojp.connection.pool.maximumPoolSize", "25");
        clientProperties.setProperty("ojp.connection.pool.minimumIdle", "7");

        // Serialize properties as the client would
        byte[] serializedProperties = SerializationHandler.serialize(clientProperties);
        
        // Create ConnectionDetails with properties
        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:testdb")
                .setUser("test")
                .setPassword("test")
                .setClientUUID("test-client")
                .setProperties(ByteString.copyFrom(serializedProperties))
                .build();
        
        // Create HikariConfig to test configuration
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:testdb");
        config.setUsername("test");
        config.setPassword("test");
        
        // Use reflection to call the private configureHikariPool method
        Method configureMethod = StatementServiceImpl.class.getDeclaredMethod("configureHikariPool", HikariConfig.class, ConnectionDetails.class);
        configureMethod.setAccessible(true);
        configureMethod.invoke(serviceImpl, config, connectionDetails);
        
        // Verify that client properties were applied
        assertEquals(25, config.getMaximumPoolSize());
        assertEquals(7, config.getMinimumIdle());
        assertEquals(true, config.isAutoCommit());

        // Verify that properties not provided use defaults
        assertEquals(CommonConstants.DEFAULT_IDLE_TIMEOUT, config.getIdleTimeout());
        assertEquals(CommonConstants.DEFAULT_MAX_LIFETIME, config.getMaxLifetime());
        assertEquals(CommonConstants.DEFAULT_CONNECTION_TIMEOUT, config.getConnectionTimeout());
    }

    @Test
    public void testHikariConfigurationWithoutClientProperties() throws Exception {
        // Create a StatementServiceImpl instance
        StatementServiceImpl serviceImpl = createTestStatementServiceImpl();
        
        // Create ConnectionDetails without properties
        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:testdb")
                .setUser("test")
                .setPassword("test")
                .setClientUUID("test-client")
                .build();
        
        // Create HikariConfig to test configuration
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:testdb");
        config.setUsername("test");
        config.setPassword("test");
        
        // Use reflection to call the private configureHikariPool method
        Method configureMethod = StatementServiceImpl.class.getDeclaredMethod("configureHikariPool", HikariConfig.class, ConnectionDetails.class);
        configureMethod.setAccessible(true);
        configureMethod.invoke(serviceImpl, config, connectionDetails);
        
        // Verify that all default values are applied
        assertEquals(CommonConstants.DEFAULT_MAXIMUM_POOL_SIZE, config.getMaximumPoolSize());
        assertEquals(CommonConstants.DEFAULT_MINIMUM_IDLE, config.getMinimumIdle());
        assertEquals(CommonConstants.DEFAULT_IDLE_TIMEOUT, config.getIdleTimeout());
        assertEquals(CommonConstants.DEFAULT_MAX_LIFETIME, config.getMaxLifetime());
        assertEquals(CommonConstants.DEFAULT_CONNECTION_TIMEOUT, config.getConnectionTimeout());
    }

    @Test
    public void testHikariConfigurationWithInvalidProperties() throws Exception {
        // Create a StatementServiceImpl instance
        StatementServiceImpl serviceImpl = createTestStatementServiceImpl();
        
        // Create test properties with invalid values
        Properties clientProperties = new Properties();
        clientProperties.setProperty("ojp.connection.pool.maximumPoolSize", "invalid_number");
        clientProperties.setProperty("ojp.connection.pool.minimumIdle", "not_a_number");

        // Serialize properties as the client would
        byte[] serializedProperties = SerializationHandler.serialize(clientProperties);
        
        // Create ConnectionDetails with properties
        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:testdb")
                .setUser("test")
                .setPassword("test")
                .setClientUUID("test-client")
                .setProperties(ByteString.copyFrom(serializedProperties))
                .build();
        
        // Create HikariConfig to test configuration
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:testdb");
        config.setUsername("test");
        config.setPassword("test");
        
        // Use reflection to call the private configureHikariPool method
        Method configureMethod = StatementServiceImpl.class.getDeclaredMethod("configureHikariPool", HikariConfig.class, ConnectionDetails.class);
        configureMethod.setAccessible(true);
        configureMethod.invoke(serviceImpl, config, connectionDetails);
        
        // Verify that invalid values fall back to defaults, but valid values are applied
        assertEquals(CommonConstants.DEFAULT_MAXIMUM_POOL_SIZE, config.getMaximumPoolSize()); // Falls back to default
        assertEquals(CommonConstants.DEFAULT_MINIMUM_IDLE, config.getMinimumIdle()); // Falls back to default
        assertEquals(true, config.isAutoCommit()); // Valid value applied
    }
}