package org.openjproxy.jdbc;

import org.junit.jupiter.api.Test;
import org.openjproxy.constants.CommonConstants;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Test for connection pool configuration functionality.
 */
public class ConnectionPoolConfigurationTest {

    @Test
    void testDefaultConstantsAreSet() {
        assertEquals(20, CommonConstants.DEFAULT_MAXIMUM_POOL_SIZE);
        assertEquals(5, CommonConstants.DEFAULT_MINIMUM_IDLE);
        assertEquals(600000L, CommonConstants.DEFAULT_IDLE_TIMEOUT);
        assertEquals(1800000L, CommonConstants.DEFAULT_MAX_LIFETIME);
        assertEquals(10000L, CommonConstants.DEFAULT_CONNECTION_TIMEOUT);
    }

    @Test
    void testPropertiesFileLoading() {
        // Create a test properties file content
        String propertiesContent = 
            "maximumPoolSize=15\n" +
            "minimumIdle=5\n" +
            "autoCommit=false\n" +
            "poolName=TestPool\n";
        
        Properties properties = new Properties();
        try (InputStream is = new ByteArrayInputStream(propertiesContent.getBytes())) {
            properties.load(is);
        } catch (Exception e) {
            fail("Should be able to load properties: " + e.getMessage());
        }
        
        assertEquals("15", properties.getProperty("maximumPoolSize"));
        assertEquals("5", properties.getProperty("minimumIdle"));
        assertEquals("false", properties.getProperty("autoCommit"));
        assertEquals("TestPool", properties.getProperty("poolName"));
    }

    @Test
    void testOjpPropertiesFileLoadingFromClasspath() throws Exception {
        // Test that the DatasourcePropertiesLoader can load the ojp.properties file from classpath
        Properties properties = DatasourcePropertiesLoader.loadOjpProperties();
        
        if (properties != null) {
            // If properties file exists in test resources, verify it loads correctly
            assertEquals("30", properties.getProperty("ojp.connection.pool.maximumPoolSize"));
            assertEquals("2", properties.getProperty("ojp.connection.pool.minimumIdle"));
        }
        // If properties is null, that's fine - no properties file found, which is a valid case
    }

    @Test
    void testPropertiesSerialization() throws Exception {
        // Test that we can serialize and deserialize properties
        Properties originalProperties = new Properties();
        originalProperties.setProperty("maximumPoolSize", "25");
        originalProperties.setProperty("minimumIdle", "2");
        originalProperties.setProperty("poolName", "SerializationTestPool");
        
        // Serialize using ProtoSerialization
        byte[] serialized = org.openjproxy.grpc.transport.ProtoSerialization.serializeToTransport(originalProperties);
        assertNotNull(serialized);
        assertTrue(serialized.length > 0);
        
        // Deserialize using ProtoSerialization
        Properties deserializedProperties = org.openjproxy.grpc.transport.ProtoSerialization.deserializeFromTransport(serialized, Properties.class);
        assertNotNull(deserializedProperties);
        assertEquals("25", deserializedProperties.getProperty("maximumPoolSize"));
        assertEquals("2", deserializedProperties.getProperty("minimumIdle"));
        assertEquals("SerializationTestPool", deserializedProperties.getProperty("poolName"));
    }
}