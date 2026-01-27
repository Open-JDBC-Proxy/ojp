package openjproxy.grpc.client;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.openjproxy.grpc.client.MultinodeUrlParser;
import org.openjproxy.grpc.client.StatementService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test to demonstrate and verify that gRPC connections are reused
 * across multiple JDBC connection requests.
 * 
 * This test verifies the connection lifecycle described in documents/GRPC_CONNECTION_LIFECYCLE.md:
 * - gRPC channels are created once per server configuration
 * - StatementService instances are cached and reused
 * - Multiple JDBC connections to the same server(s) share the underlying gRPC channel(s)
 */
@Slf4j
public class GrpcConnectionReuseTest {

    /**
     * Test that single-node configurations reuse the same StatementService
     * and therefore the same gRPC channel.
     */
    @Test
    public void testSingleNodeStatementServiceReuse() {
        log.info("=== Testing Single-Node gRPC Channel Reuse ===");
        
        String url = "jdbc:ojp[localhost:1059]_postgresql://localhost:5432/testdb";
        
        // First request - should create new StatementService
        log.info("First connection request to: {}", url);
        MultinodeUrlParser.ServiceAndUrl result1 = MultinodeUrlParser.getOrCreateStatementService(url);
        StatementService service1 = result1.getService();
        assertNotNull(service1, "First StatementService should not be null");
        log.info("First StatementService created: {}", service1.getClass().getSimpleName());
        
        // Second request - should return cached StatementService
        log.info("Second connection request to same URL: {}", url);
        MultinodeUrlParser.ServiceAndUrl result2 = MultinodeUrlParser.getOrCreateStatementService(url);
        StatementService service2 = result2.getService();
        assertNotNull(service2, "Second StatementService should not be null");
        log.info("Second StatementService retrieved: {}", service2.getClass().getSimpleName());
        
        // Verify same instance is returned (proving reuse)
        assertSame(service1, service2, 
            "Both requests should return the SAME StatementService instance, proving gRPC channel reuse");
        
        log.info("✓ Verified: Same StatementService instance returned for both requests");
        log.info("✓ This proves the gRPC channel is created once and reused");
    }
    
    /**
     * Test that different single-node endpoints get different StatementServices
     * (and therefore different gRPC channels).
     */
    @Test
    public void testDifferentSingleNodeEndpointsGetDifferentServices() {
        log.info("=== Testing Different Single-Node Endpoints Get Different Channels ===");
        
        String url1 = "jdbc:ojp[server1:1059]_postgresql://localhost:5432/testdb";
        String url2 = "jdbc:ojp[server2:1059]_postgresql://localhost:5432/testdb";
        
        log.info("Request to server1: {}", url1);
        MultinodeUrlParser.ServiceAndUrl result1 = MultinodeUrlParser.getOrCreateStatementService(url1);
        StatementService service1 = result1.getService();
        
        log.info("Request to server2: {}", url2);
        MultinodeUrlParser.ServiceAndUrl result2 = MultinodeUrlParser.getOrCreateStatementService(url2);
        StatementService service2 = result2.getService();
        
        // Different servers should have different services (and different channels)
        assertNotSame(service1, service2,
            "Different server endpoints should have DIFFERENT StatementService instances (and different gRPC channels)");
        
        log.info("✓ Verified: Different server endpoints get different StatementService instances");
        log.info("✓ This proves each server endpoint has its own dedicated gRPC channel");
    }
    
    /**
     * Test that multinode configurations reuse the same MultinodeStatementService
     * (which internally manages multiple gRPC channels, one per server).
     */
    @Test
    public void testMultinodeStatementServiceReuse() {
        log.info("=== Testing Multinode gRPC Channel Reuse ===");
        
        String url = "jdbc:ojp[server1:1059,server2:1059,server3:1059]_postgresql://localhost:5432/testdb";
        
        // First request - should create MultinodeStatementService with 3 channels
        log.info("First connection request to multinode cluster: {}", url);
        MultinodeUrlParser.ServiceAndUrl result1 = MultinodeUrlParser.getOrCreateStatementService(url);
        StatementService service1 = result1.getService();
        assertNotNull(service1, "First MultinodeStatementService should not be null");
        assertEquals(3, result1.getServerEndpoints().size(), "Should have 3 server endpoints");
        log.info("First MultinodeStatementService created with {} endpoints", result1.getServerEndpoints().size());
        
        // Second request - should return cached MultinodeStatementService
        log.info("Second connection request to same multinode cluster: {}", url);
        MultinodeUrlParser.ServiceAndUrl result2 = MultinodeUrlParser.getOrCreateStatementService(url);
        StatementService service2 = result2.getService();
        assertNotNull(service2, "Second MultinodeStatementService should not be null");
        log.info("Second MultinodeStatementService retrieved");
        
        // Verify same instance is returned
        assertSame(service1, service2,
            "Both requests should return the SAME MultinodeStatementService instance, proving gRPC channel reuse");
        
        log.info("✓ Verified: Same MultinodeStatementService instance returned for both requests");
        log.info("✓ This proves all 3 gRPC channels are created once and reused");
    }
    
    /**
     * Test that the same database on the same server reuses the connection,
     * regardless of which database is specified in the JDBC URL.
     * gRPC channels are per-server-endpoint, not per-database.
     */
    @Test
    public void testDifferentDatabasesSameServerReusesChannel() {
        log.info("=== Testing Different Databases on Same Server Reuse Channel ===");
        
        String url1 = "jdbc:ojp[localhost:1059]_postgresql://localhost:5432/database1";
        String url2 = "jdbc:ojp[localhost:1059]_postgresql://localhost:5432/database2";
        
        log.info("Request to database1 via localhost:1059: {}", url1);
        MultinodeUrlParser.ServiceAndUrl result1 = MultinodeUrlParser.getOrCreateStatementService(url1);
        StatementService service1 = result1.getService();
        
        log.info("Request to database2 via localhost:1059: {}", url2);
        MultinodeUrlParser.ServiceAndUrl result2 = MultinodeUrlParser.getOrCreateStatementService(url2);
        StatementService service2 = result2.getService();
        
        // Same server endpoint should reuse same service (and gRPC channel)
        assertSame(service1, service2,
            "Different databases on the same OJP server endpoint should reuse the SAME StatementService and gRPC channel");
        
        log.info("✓ Verified: Different databases on same server reuse the same StatementService");
        log.info("✓ This proves gRPC channels are per-server-endpoint, not per-database");
    }
    
    /**
     * Test that different multinode configurations with different server lists
     * get different services (since they're different clusters).
     */
    @Test
    public void testDifferentMultinodeClustersGetDifferentServices() {
        log.info("=== Testing Different Multinode Clusters Get Different Services ===");
        
        String cluster1 = "jdbc:ojp[server1:1059,server2:1059]_postgresql://localhost:5432/testdb";
        String cluster2 = "jdbc:ojp[server3:1059,server4:1059]_postgresql://localhost:5432/testdb";
        
        log.info("Request to cluster1 (server1,server2): {}", cluster1);
        MultinodeUrlParser.ServiceAndUrl result1 = MultinodeUrlParser.getOrCreateStatementService(cluster1);
        StatementService service1 = result1.getService();
        
        log.info("Request to cluster2 (server3,server4): {}", cluster2);
        MultinodeUrlParser.ServiceAndUrl result2 = MultinodeUrlParser.getOrCreateStatementService(cluster2);
        StatementService service2 = result2.getService();
        
        // Different clusters should have different services
        assertNotSame(service1, service2,
            "Different multinode clusters should have DIFFERENT MultinodeStatementService instances");
        
        log.info("✓ Verified: Different multinode clusters get different StatementService instances");
        log.info("✓ This proves each cluster configuration has its own set of gRPC channels");
    }
}
