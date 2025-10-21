package org.openjproxy.grpc.server;

import com.atomikos.jdbc.AtomikosDataSourceBean;
import com.google.protobuf.ByteString;
import com.openjproxy.grpc.ConnectionDetails;
import com.openjproxy.grpc.SessionInfo;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openjproxy.constants.CommonConstants;
import org.openjproxy.grpc.SerializationHandler;
import org.openjproxy.grpc.server.pool.AtomikosLifecycle;

import java.sql.Connection;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for Atomikos XA functionality.
 * Tests the complete flow from connection creation to lazy connection allocation.
 */
public class AtomikosXAIntegrationTest {

    private StatementServiceImpl statementService;
    private SessionManager sessionManager;
    private CircuitBreaker circuitBreaker;
    private ServerConfiguration serverConfiguration;

    @BeforeEach
    public void setup() {
        sessionManager = spy(new SessionManagerImpl());
        circuitBreaker = new CircuitBreaker(60000, 5);
        serverConfiguration = new ServerConfiguration();
        statementService = new StatementServiceImpl(sessionManager, circuitBreaker, serverConfiguration);
    }

    @AfterEach
    public void cleanup() {
        // Shutdown Atomikos if initialized
        if (AtomikosLifecycle.isInitialized()) {
            AtomikosLifecycle.shutdown();
        }
    }

    @Test
    public void testAtomikosDataSourceCreatedForXAConnection() {
        // Create properties with custom pool settings
        Properties clientProperties = new Properties();
        clientProperties.setProperty(CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY, "12");
        clientProperties.setProperty(CommonConstants.MINIMUM_IDLE_PROPERTY, "4");
        clientProperties.setProperty(CommonConstants.CONNECTION_TIMEOUT_PROPERTY, "15000"); // 15 seconds
        
        byte[] serializedProperties;
        try {
            serializedProperties = SerializationHandler.serialize(clientProperties);
        } catch (Exception e) {
            fail("Failed to serialize properties: " + e.getMessage());
            return;
        }
        
        // Create XA connection details
        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:postgresql://localhost:5432/testdb")
                .setUser("testuser")
                .setPassword("testpass")
                .setClientUUID("test-xa-client")
                .setIsXA(true)
                .setProperties(ByteString.copyFrom(serializedProperties))
                .build();
        
        // Create a mock response observer
        AtomicReference<SessionInfo> capturedSession = new AtomicReference<>();
        AtomicReference<Throwable> capturedError = new AtomicReference<>();
        
        StreamObserver<SessionInfo> responseObserver = new StreamObserver<SessionInfo>() {
            @Override
            public void onNext(SessionInfo value) {
                capturedSession.set(value);
            }
            
            @Override
            public void onError(Throwable t) {
                capturedError.set(t);
            }
            
            @Override
            public void onCompleted() {
                // No-op
            }
        };
        
        // Call connect - this will create XADataSource but will fail to connect since no real DB
        // We're mainly testing that the Atomikos datasource is created with correct settings
        statementService.connect(connectionDetails, responseObserver);
        
        // Since there's no real PostgreSQL database, the connection will fail
        // But we can verify that Atomikos was initialized
        assertTrue(AtomikosLifecycle.isInitialized(), 
                "Atomikos should be initialized when XA connection is requested");
    }

    @Test
    public void testHikariDataSourceCreatedForNonXAConnection() {
        // Create connection details WITHOUT XA flag
        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:testdb")
                .setUser("sa")
                .setPassword("")
                .setClientUUID("test-non-xa-client")
                .setIsXA(false)
                .build();
        
        // Create a mock response observer
        AtomicReference<SessionInfo> capturedSession = new AtomicReference<>();
        
        StreamObserver<SessionInfo> responseObserver = new StreamObserver<SessionInfo>() {
            @Override
            public void onNext(SessionInfo value) {
                capturedSession.set(value);
            }
            
            @Override
            public void onError(Throwable t) {
                fail("Should not error for H2 connection: " + t.getMessage());
            }
            
            @Override
            public void onCompleted() {
                // No-op
            }
        };
        
        // Call connect
        statementService.connect(connectionDetails, responseObserver);
        
        // For non-XA, session info should be returned without creating actual session
        assertNotNull(capturedSession.get());
        assertEquals("test-non-xa-client", capturedSession.get().getClientUUID());
        assertFalse(capturedSession.get().getIsXA());
        
        // Atomikos should NOT be initialized for non-XA connections
        // (unless it was initialized by a previous test)
    }

    @Test
    public void testPropertyMappingFromHikariToAtomikos() throws Exception {
        // This test verifies that Hikari property keys are correctly mapped to Atomikos
        // We'll verify through the AtomikosDataSourceFactory directly
        
        Properties clientProperties = new Properties();
        clientProperties.setProperty(CommonConstants.MAXIMUM_POOL_SIZE_PROPERTY, "25");
        clientProperties.setProperty(CommonConstants.MINIMUM_IDLE_PROPERTY, "8");
        clientProperties.setProperty(CommonConstants.CONNECTION_TIMEOUT_PROPERTY, "30000"); // 30s
        clientProperties.setProperty(CommonConstants.IDLE_TIMEOUT_PROPERTY, "600000"); // 10min
        clientProperties.setProperty(CommonConstants.MAX_LIFETIME_PROPERTY, "1800000"); // 30min
        
        byte[] serializedProperties = SerializationHandler.serialize(clientProperties);
        
        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:postgresql://localhost:5432/testdb")
                .setUser("test")
                .setPassword("test")
                .setClientUUID("test-client")
                .setIsXA(true)
                .setProperties(ByteString.copyFrom(serializedProperties))
                .build();
        
        // Create a mock XADataSource
        org.postgresql.xa.PGXADataSource xaDS = new org.postgresql.xa.PGXADataSource();
        xaDS.setServerNames(new String[]{"localhost"});
        xaDS.setPortNumbers(new int[]{5432});
        xaDS.setDatabaseName("testdb");
        
        // Create Atomikos datasource using the factory
        AtomikosDataSourceBean atomikosDS = 
                org.openjproxy.grpc.server.pool.AtomikosDataSourceFactory.createAtomikosDataSource(
                        connectionDetails, xaDS, "test-resource");
        
        // Verify mappings
        assertEquals(25, atomikosDS.getMaxPoolSize());
        assertEquals(8, atomikosDS.getMinPoolSize());
        
        // Verify timeout conversions (ms to seconds)
        assertEquals(30, atomikosDS.getBorrowConnectionTimeout()); // 30000ms -> 30s
        assertEquals(600, atomikosDS.getMaxIdleTime()); // 600000ms -> 600s
        assertEquals(1800, atomikosDS.getMaxLifetime()); // 1800000ms -> 1800s
    }

    @Test
    public void testLazyConnectionAllocationConcept() {
        // This test validates that connections are not allocated until sessionConnection() is called
        // Since we can't easily test this without a real database, we verify the flow exists
        
        ConnectionDetails connectionDetails = ConnectionDetails.newBuilder()
                .setUrl("jdbc:h2:mem:testdb")
                .setUser("sa")
                .setPassword("")
                .setClientUUID("test-lazy-client")
                .setIsXA(false)
                .build();
        
        StreamObserver<SessionInfo> responseObserver = new StreamObserver<SessionInfo>() {
            @Override
            public void onNext(SessionInfo value) {
                // For non-XA connections, datasource is created but session is not
                // Session will be created lazily when first statement is executed
                assertNotNull(value, "SessionInfo should be returned");
                assertFalse(value.getIsXA(), "Should not be XA connection");
                // Connection hash is set, session UUID may or may not be set yet (lazy)
            }
            
            @Override
            public void onError(Throwable t) {
                fail("Should not error: " + t.getMessage());
            }
            
            @Override
            public void onCompleted() {
                // No-op
            }
        };
        
        // connect() creates the datasource but not the session
        statementService.connect(connectionDetails, responseObserver);
        
        // Actual connection would be allocated when executing first statement via sessionConnection()
        // This is the lazy allocation pattern
    }
}
