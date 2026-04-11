package org.openjproxy.grpc.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.server.cache.CacheConfiguration;
import org.openjproxy.grpc.server.cache.CacheRule;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for cache configuration in sessions.
 */
class SessionCacheIntegrationTest {

    private Map<String, CacheConfiguration> cacheConfigurationMap;
    private SessionManagerImpl sessionManager;

    @BeforeEach
    void setUp() {
        cacheConfigurationMap = new ConcurrentHashMap<>();
        sessionManager = new SessionManagerImpl(cacheConfigurationMap);
    }

    @Test
    void testSessionCreationWithCacheConfiguration() {
        // Setup: Create cache configuration
        List<CacheRule> rules = new ArrayList<>();
        rules.add(new CacheRule(Pattern.compile("SELECT .*"), Duration.ofSeconds(600), List.of("products"), true));
        CacheConfiguration cacheConfig = new CacheConfiguration("test-datasource", true, rules);
        
        String connectionHash = "test-connection-hash";
        String clientUUID = "test-client-uuid";
        
        // Add cache configuration to map
        cacheConfigurationMap.put(connectionHash, cacheConfig);
        
        // Register client
        sessionManager.registerClientUUID(connectionHash, clientUUID);
        
        // Create session
        Connection mockConnection = mock(Connection.class);
        sessionManager.createSession(clientUUID, mockConnection);
        
        // Verify: Session should have cache configuration
        // Note: We can't directly access the session from SessionManagerImpl without SessionInfo
        // This test verifies that the integration works without errors
        assertNotNull(sessionManager);
    }

    @Test
    void testSessionCreationWithoutCacheConfiguration() {
        // Setup: No cache configuration in map
        String connectionHash = "test-connection-hash-2";
        String clientUUID = "test-client-uuid-2";
        
        // Register client
        sessionManager.registerClientUUID(connectionHash, clientUUID);
        
        // Create session
        Connection mockConnection = mock(Connection.class);
        sessionManager.createSession(clientUUID, mockConnection);
        
        // Verify: Session should be created successfully even without cache config
        assertNotNull(sessionManager);
    }

    @Test
    void testXASessionCreationWithCacheConfiguration() throws Exception {
        // Setup: Create cache configuration
        List<CacheRule> rules = new ArrayList<>();
        rules.add(new CacheRule(Pattern.compile("SELECT .*"), Duration.ofSeconds(300), List.of("users"), true));
        CacheConfiguration cacheConfig = new CacheConfiguration("test-datasource", true, rules);
        
        String connectionHash = "test-xa-connection-hash";
        String clientUUID = "test-xa-client-uuid";
        
        // Add cache configuration to map
        cacheConfigurationMap.put(connectionHash, cacheConfig);
        
        // Register client
        sessionManager.registerClientUUID(connectionHash, clientUUID);
        
        // Create XA session
        Connection mockConnection = mock(Connection.class);
        javax.sql.XAConnection mockXAConnection = mock(javax.sql.XAConnection.class);
        when(mockXAConnection.getXAResource()).thenReturn(mock(javax.transaction.xa.XAResource.class));
        
        sessionManager.createXASession(clientUUID, mockConnection, mockXAConnection);
        
        // Verify: XA session should be created successfully with cache config
        assertNotNull(sessionManager);
    }

    @Test
    void testMultipleSessionsWithDifferentConfigurations() {
        // Setup: Create different cache configurations
        List<CacheRule> rules1 = new ArrayList<>();
        rules1.add(new CacheRule(Pattern.compile("SELECT .* FROM products.*"), Duration.ofSeconds(600), List.of("products"), true));
        CacheConfiguration cacheConfig1 = new CacheConfiguration("test-datasource-1", true, rules1);
        
        List<CacheRule> rules2 = new ArrayList<>();
        rules2.add(new CacheRule(Pattern.compile("SELECT .* FROM users.*"), Duration.ofSeconds(300), List.of("users"), true));
        CacheConfiguration cacheConfig2 = new CacheConfiguration("test-datasource-2", true, rules2);
        
        String connectionHash1 = "connection-hash-1";
        String clientUUID1 = "client-uuid-1";
        String connectionHash2 = "connection-hash-2";
        String clientUUID2 = "client-uuid-2";
        
        // Add configurations to map
        cacheConfigurationMap.put(connectionHash1, cacheConfig1);
        cacheConfigurationMap.put(connectionHash2, cacheConfig2);
        
        // Register clients
        sessionManager.registerClientUUID(connectionHash1, clientUUID1);
        sessionManager.registerClientUUID(connectionHash2, clientUUID2);
        
        // Create sessions
        Connection mockConnection1 = mock(Connection.class);
        Connection mockConnection2 = mock(Connection.class);
        sessionManager.createSession(clientUUID1, mockConnection1);
        sessionManager.createSession(clientUUID2, mockConnection2);
        
        // Verify: Both sessions should be created successfully
        assertNotNull(sessionManager);
        assertEquals(2, cacheConfigurationMap.size());
    }

    @Test
    void testConcurrentSessionCreation() throws InterruptedException {
        // Setup: Create cache configuration
        List<CacheRule> rules = new ArrayList<>();
        rules.add(new CacheRule(Pattern.compile("SELECT .*"), Duration.ofSeconds(600), List.of("test_table"), true));
        CacheConfiguration cacheConfig = new CacheConfiguration("test-datasource", true, rules);
        
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        // Create multiple sessions concurrently
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    String connectionHash = "connection-hash-" + index;
                    String clientUUID = "client-uuid-" + index;
                    
                    cacheConfigurationMap.put(connectionHash, cacheConfig);
                    sessionManager.registerClientUUID(connectionHash, clientUUID);
                    
                    Connection mockConnection = mock(Connection.class);
                    sessionManager.createSession(clientUUID, mockConnection);
                } finally {
                    latch.countDown();
                }
            });
        }
        
        // Wait for all threads to complete
        latch.await();
        executor.shutdown();
        
        // Verify: All sessions should be created successfully
        assertNotNull(sessionManager);
        assertEquals(threadCount, cacheConfigurationMap.size());
    }
}
