package org.openjproxy.grpc.server.readwrite;

import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.PropertyEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Test cases for ReadWriteDataSourceManager.
 */
class ReadWriteDataSourceManagerTest {
    
    private ReadWriteDataSourceRegistry registry;
    private ReadWriteDataSourceManager manager;
    
    @BeforeEach
    void setUp() {
        registry = new ReadWriteDataSourceRegistry();
        manager = new ReadWriteDataSourceManager(registry);
    }
    
    @Test
    void testIsReadWriteSplittingEnabled_NoProperties() {
        ConnectionDetails details = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:test")
                .build();
        
        assertFalse(manager.isReadWriteSplittingEnabled(details, "testds"));
    }
    
    @Test
    void testIsReadWriteSplittingEnabled_NotConfigured() {
        ConnectionDetails details = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:test")
                .addProperties(PropertyEntry.newBuilder()
                        .setKey("some.other.property")
                        .setStringValue("value")
                        .build())
                .build();
        
        assertFalse(manager.isReadWriteSplittingEnabled(details, "testds"));
    }
    
    @Test
    void testIsReadWriteSplittingEnabled_ConfiguredButDisabled() {
        ConnectionDetails details = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:test")
                .addProperties(PropertyEntry.newBuilder()
                        .setKey("testds.ojp.readwrite.enabled")
                        .setStringValue("false")
                        .build())
                .addProperties(PropertyEntry.newBuilder()
                        .setKey("testds.ojp.readwrite.role")
                        .setStringValue("primary")
                        .build())
                .build();
        
        assertFalse(manager.isReadWriteSplittingEnabled(details, "testds"));
    }
    
    @Test
    void testIsReadWriteSplittingEnabled_EnabledWithoutReplicas() {
        ConnectionDetails details = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:test")
                .addProperties(PropertyEntry.newBuilder()
                        .setKey("testds.ojp.readwrite.enabled")
                        .setStringValue("true")
                        .build())
                .addProperties(PropertyEntry.newBuilder()
                        .setKey("testds.ojp.readwrite.role")
                        .setStringValue("primary")
                        .build())
                .build();
        
        // Without replicas configured, it's not really enabled
        assertFalse(manager.isReadWriteSplittingEnabled(details, "testds"));
    }
    
    @Test
    void testIsReadWriteSplittingEnabled_EnabledWithReplicas() {
        ConnectionDetails details = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:test")
                .addProperties(PropertyEntry.newBuilder()
                        .setKey("testds.ojp.readwrite.enabled")
                        .setStringValue("true")
                        .build())
                .addProperties(PropertyEntry.newBuilder()
                        .setKey("testds.ojp.readwrite.role")
                        .setStringValue("primary")
                        .build())
                .addProperties(PropertyEntry.newBuilder()
                        .setKey("replica1.ojp.readwrite.role")
                        .setStringValue("replica")
                        .build())
                .addProperties(PropertyEntry.newBuilder()
                        .setKey("replica1.ojp.readwrite.primary")
                        .setStringValue("testds")
                        .build())
                .build();
        
        assertTrue(manager.isReadWriteSplittingEnabled(details, "testds"));
    }
    
    @Test
    void testSetupReadWriteSplitting_NoConfiguration() {
        ConnectionDetails details = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:test")
                .build();
        
        DataSource ds = mock(DataSource.class);
        ReadWriteConfiguration config = manager.setupReadWriteSplitting(
                details, "conn123", ds, "testds");
        
        assertNull(config);
    }
    
    @Test
    void testSetupReadWriteSplitting_ValidConfiguration() {
        ConnectionDetails details = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:primary")
                .addProperties(PropertyEntry.newBuilder()
                        .setKey("primary.ojp.readwrite.enabled")
                        .setStringValue("true")
                        .build())
                .addProperties(PropertyEntry.newBuilder()
                        .setKey("primary.ojp.readwrite.role")
                        .setStringValue("primary")
                        .build())
                .addProperties(PropertyEntry.newBuilder()
                        .setKey("replica1.ojp.readwrite.role")
                        .setStringValue("replica")
                        .build())
                .addProperties(PropertyEntry.newBuilder()
                        .setKey("replica1.ojp.readwrite.primary")
                        .setStringValue("primary")
                        .build())
                .addProperties(PropertyEntry.newBuilder()
                        .setKey("replica1.connection.url")
                        .setStringValue("jdbc:h2:mem:replica1")
                        .build())
                .build();
        
        DataSource ds = mock(DataSource.class);
        ReadWriteConfiguration config = manager.setupReadWriteSplitting(
                details, "conn123", ds, "primary");
        
        assertNotNull(config);
        assertTrue(config.isEnabled());
        assertEquals("primary", config.getPrimaryName());
        assertEquals(1, config.getReplicaNames().size());
        assertEquals("replica1", config.getReplicaNames().get(0));
        
        // Verify registration - check primary mapping and replicas
        String primaryName = registry.getPrimaryName("conn123");
        assertNotNull(primaryName);
        assertEquals("primary", primaryName);
        
        List<DataSource> registeredReplicas = registry.getReplicas("primary");
        assertNotNull(registeredReplicas);
        assertEquals(1, registeredReplicas.size());
    }
    
    @Test
    void testConstructorNullRegistry() {
        assertThrows(NullPointerException.class, () -> {
            new ReadWriteDataSourceManager(null);
        });
    }
}
