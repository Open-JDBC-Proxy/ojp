package org.openjproxy.grpc.server.pool;

import com.atomikos.jdbc.AtomikosDataSourceBean;
import com.google.protobuf.ByteString;
import com.openjproxy.grpc.ConnectionDetails;
import org.junit.jupiter.api.Test;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.SerializationHandler;
import org.postgresql.xa.PGXADataSource;

import javax.sql.XADataSource;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AtomikosDataSourceFactory - validates configuration mapping and timeout conversions.
 */
public class AtomikosDataSourceFactoryTest {

    @Test
    public void testCreateAtomikosDataSourceWithDefaults() throws Exception {
        // Create a PostgreSQL XADataSource
        PGXADataSource xaDS = new PGXADataSource();
        xaDS.setServerNames(new String[]{"localhost"});
        xaDS.setPortNumbers(new int[]{5432});
        xaDS.setDatabaseName("testdb");
        xaDS.setUser("test");
        xaDS.setPassword("test");
        
        // Create connection details without custom properties
        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:postgresql://localhost:5432/testdb")
                .setUser("test")
                .setPassword("test")
                .setClientUUID("test-client")
                .setIsXA(true)
                .build();
        
        // Create Atomikos datasource
        AtomikosDataSourceBean atomikosDS = AtomikosDataSourceFactory.createAtomikosDataSource(
                connectionDetails, xaDS, "test-resource-1");
        
        // Verify default settings
        assertEquals("test-resource-1", atomikosDS.getUniqueResourceName());
        assertEquals(CommonConstants.DEFAULT_MAXIMUM_POOL_SIZE, atomikosDS.getMaxPoolSize());
        assertEquals(CommonConstants.DEFAULT_MINIMUM_IDLE, atomikosDS.getMinPoolSize());
        
        // Verify timeout conversions (ms to seconds)
        assertEquals(CommonConstants.DEFAULT_CONNECTION_TIMEOUT / 1000, atomikosDS.getBorrowConnectionTimeout());
        assertEquals(CommonConstants.DEFAULT_IDLE_TIMEOUT / 1000, atomikosDS.getMaxIdleTime());
        assertEquals(CommonConstants.DEFAULT_MAX_LIFETIME / 1000, atomikosDS.getMaxLifetime());
    }

    @Test
    public void testCreateAtomikosDataSourceWithCustomProperties() throws Exception {
        // Create a PostgreSQL XADataSource
        PGXADataSource xaDS = new PGXADataSource();
        xaDS.setServerNames(new String[]{"localhost"});
        xaDS.setPortNumbers(new int[]{5432});
        xaDS.setDatabaseName("testdb");
        
        // Create custom properties
        Properties clientProperties = new Properties();
        clientProperties.setProperty(CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY, "15");
        clientProperties.setProperty(CommonConstants.MINIMUM_IDLE_PROPERTY, "3");
        clientProperties.setProperty(CommonConstants.CONNECTION_TIMEOUT_PROPERTY, "20000"); // 20 seconds
        clientProperties.setProperty(CommonConstants.IDLE_TIMEOUT_PROPERTY, "300000"); // 5 minutes
        clientProperties.setProperty(CommonConstants.MAX_LIFETIME_PROPERTY, "900000"); // 15 minutes
        
        byte[] serializedProperties = SerializationHandler.serialize(clientProperties);
        
        // Create connection details with properties
        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:postgresql://localhost:5432/testdb")
                .setUser("test")
                .setPassword("test")
                .setClientUUID("test-client")
                .setIsXA(true)
                .setProperties(ByteString.copyFrom(serializedProperties))
                .build();
        
        // Create Atomikos datasource
        AtomikosDataSourceBean atomikosDS = AtomikosDataSourceFactory.createAtomikosDataSource(
                connectionDetails, xaDS, "test-resource-2");
        
        // Verify custom settings
        assertEquals("test-resource-2", atomikosDS.getUniqueResourceName());
        assertEquals(15, atomikosDS.getMaxPoolSize());
        assertEquals(3, atomikosDS.getMinPoolSize());
        
        // Verify timeout conversions (ms to seconds)
        assertEquals(20, atomikosDS.getBorrowConnectionTimeout()); // 20000ms -> 20s
        assertEquals(300, atomikosDS.getMaxIdleTime()); // 300000ms -> 300s
        assertEquals(900, atomikosDS.getMaxLifetime()); // 900000ms -> 900s
    }

    @Test
    public void testMillisecondsToSecondsConversion() throws Exception {
        // Create a PostgreSQL XADataSource
        PGXADataSource xaDS = new PGXADataSource();
        xaDS.setServerNames(new String[]{"localhost"});
        xaDS.setPortNumbers(new int[]{5432});
        xaDS.setDatabaseName("testdb");
        
        // Create properties with specific millisecond values
        Properties clientProperties = new Properties();
        clientProperties.setProperty(CommonConstants.CONNECTION_TIMEOUT_PROPERTY, "5500"); // 5.5 seconds -> 5s (truncated)
        clientProperties.setProperty(CommonConstants.IDLE_TIMEOUT_PROPERTY, "65000"); // 65 seconds
        clientProperties.setProperty(CommonConstants.MAX_LIFETIME_PROPERTY, "125000"); // 125 seconds
        
        byte[] serializedProperties = SerializationHandler.serialize(clientProperties);
        
        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:postgresql://localhost:5432/testdb")
                .setUser("test")
                .setPassword("test")
                .setClientUUID("test-client")
                .setIsXA(true)
                .setProperties(ByteString.copyFrom(serializedProperties))
                .build();
        
        AtomikosDataSourceBean atomikosDS = AtomikosDataSourceFactory.createAtomikosDataSource(
                connectionDetails, xaDS, "test-resource-3");
        
        // Verify conversions (integer division truncates)
        assertEquals(5, atomikosDS.getBorrowConnectionTimeout()); // 5500ms -> 5s
        assertEquals(65, atomikosDS.getMaxIdleTime()); // 65000ms -> 65s
        assertEquals(125, atomikosDS.getMaxLifetime()); // 125000ms -> 125s
    }

    @Test
    public void testAtomikosConfigExtraction() throws Exception {
        // Test with logging enabled
        Properties props1 = new Properties();
        props1.setProperty("jdbc.atomikos.logging.enabled", "true");
        props1.setProperty("jdbc.atomikos.logging.dir", "/custom/logs");
        
        byte[] serialized1 = SerializationHandler.serialize(props1);
        ConnectionDetails cd1 = ConnectionDetails.newBuilder()
                .setUrl("jdbc:postgresql://localhost:5432/testdb")
                .setUser("test")
                .setPassword("test")
                .setClientUUID("test-client")
                .setIsXA(true)
                .setProperties(ByteString.copyFrom(serialized1))
                .build();
        
        AtomikosDataSourceFactory.AtomikosConfig config1 = 
                AtomikosDataSourceFactory.getAtomikosConfig(cd1);
        
        assertTrue(config1.isLoggingEnabled());
        assertEquals("/custom/logs", config1.getLogDir());
        
        // Test with logging disabled (default)
        ConnectionDetails cd2 = ConnectionDetails.newBuilder()
                .setUrl("jdbc:postgresql://localhost:5432/testdb")
                .setUser("test")
                .setPassword("test")
                .setClientUUID("test-client")
                .setIsXA(true)
                .build();
        
        AtomikosDataSourceFactory.AtomikosConfig config2 = 
                AtomikosDataSourceFactory.getAtomikosConfig(cd2);
        
        assertFalse(config2.isLoggingEnabled());
        assertEquals("./atomikos-logs", config2.getLogDir());
    }

    @Test
    public void testTestQueryForDifferentDatabases() throws Exception {
        PGXADataSource xaDS = new PGXADataSource();
        xaDS.setServerNames(new String[]{"localhost"});
        xaDS.setPortNumbers(new int[]{5432});
        xaDS.setDatabaseName("testdb");
        
        // Test PostgreSQL
        ConnectionDetails pgDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:postgresql://localhost:5432/testdb")
                .setUser("test")
                .setPassword("test")
                .setClientUUID("test-client")
                .setIsXA(true)
                .build();
        
        AtomikosDataSourceBean pgDS = AtomikosDataSourceFactory.createAtomikosDataSource(
                pgDetails, xaDS, "test-pg");
        
        // Verify test query is set (should be "SELECT 1" for PostgreSQL)
        assertNotNull(pgDS.getTestQuery());
        assertEquals("SELECT 1", pgDS.getTestQuery());
    }
}
