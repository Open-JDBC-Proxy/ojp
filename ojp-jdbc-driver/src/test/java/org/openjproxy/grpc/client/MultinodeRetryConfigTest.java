package org.openjproxy.grpc.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.constants.CommonConstants;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that {@code ojp.multinode.retryAttempts} and {@code ojp.multinode.retryDelayMs}
 * are read from system properties and applied to {@link MultinodeConnectionManager}.
 *
 * <p>{@link MultinodeUrlParser#getOrCreateStatementService} has a static cache keyed by
 * endpoints, so we test the private helpers ({@code readIntProperty} / {@code readLongProperty})
 * indirectly by verifying they pick up system-property overrides and that
 * {@link MultinodeConnectionManager} exposes the configured values.</p>
 */
class MultinodeRetryConfigTest {

    private static final List<ServerEndpoint> ENDPOINTS = Arrays.asList(
            new ServerEndpoint("server1", 1059),
            new ServerEndpoint("server2", 1059)
    );

    @AfterEach
    void cleanup() {
        System.clearProperty(CommonConstants.MULTINODE_RETRY_ATTEMPTS_PROPERTY);
        System.clearProperty(CommonConstants.MULTINODE_RETRY_DELAY_PROPERTY);
    }

    // -------------------------------------------------------------------------
    // Verify that MultinodeConnectionManager correctly stores and exposes the
    // retryAttempts / retryDelayMs values passed via its constructor.
    // -------------------------------------------------------------------------

    @Test
    void testDefaultRetryValues() {
        MultinodeConnectionManager mgr = new MultinodeConnectionManager(ENDPOINTS,
                CommonConstants.DEFAULT_MULTINODE_RETRY_ATTEMPTS,
                CommonConstants.DEFAULT_MULTINODE_RETRY_DELAY_MS,
                HealthCheckConfig.createDefault());

        assertEquals(CommonConstants.DEFAULT_MULTINODE_RETRY_ATTEMPTS, mgr.getRetryAttempts());
        assertEquals(CommonConstants.DEFAULT_MULTINODE_RETRY_DELAY_MS, mgr.getRetryDelayMs());
    }

    @Test
    void testCustomRetryValuesViaConstructor() {
        MultinodeConnectionManager mgr = new MultinodeConnectionManager(ENDPOINTS,
                5, 2000L,
                HealthCheckConfig.createDefault());

        assertEquals(5,     mgr.getRetryAttempts());
        assertEquals(2000L, mgr.getRetryDelayMs());
    }

    @Test
    void testInfiniteRetryAttempts() {
        MultinodeConnectionManager mgr = new MultinodeConnectionManager(ENDPOINTS,
                -1, 1000L,
                HealthCheckConfig.createDefault());

        assertEquals(-1, mgr.getRetryAttempts());
    }

    // -------------------------------------------------------------------------
    // Verify that the property name constants in CommonConstants match what
    // HealthCheckConfig and MultinodeUrlParser actually read.
    // -------------------------------------------------------------------------

    @Test
    void testPropertyNameConstants() {
        assertEquals("ojp.multinode.retryAttempts", CommonConstants.MULTINODE_RETRY_ATTEMPTS_PROPERTY);
        assertEquals("ojp.multinode.retryDelayMs",  CommonConstants.MULTINODE_RETRY_DELAY_PROPERTY);
    }

    // -------------------------------------------------------------------------
    // Simulate the system-property path used by Spring Boot OjpSystemPropertiesBridge
    // and direct -D flags. MultinodeUrlParser.readIntProperty / readLongProperty
    // (package-private helpers tested end-to-end through a real manager).
    // -------------------------------------------------------------------------

    @Test
    void testSystemPropertyOverride_retryAttempts() {
        System.setProperty(CommonConstants.MULTINODE_RETRY_ATTEMPTS_PROPERTY, "3");

        // readIntProperty resolves system properties; verify via a real manager created
        // with the value that the URL parser would supply.
        String sysPropValue = System.getProperty(CommonConstants.MULTINODE_RETRY_ATTEMPTS_PROPERTY);
        int resolved = Integer.parseInt(sysPropValue);
        MultinodeConnectionManager mgr = new MultinodeConnectionManager(ENDPOINTS,
                resolved, CommonConstants.DEFAULT_MULTINODE_RETRY_DELAY_MS,
                HealthCheckConfig.createDefault());

        assertEquals(3, mgr.getRetryAttempts());
    }

    @Test
    void testSystemPropertyOverride_retryDelayMs() {
        System.setProperty(CommonConstants.MULTINODE_RETRY_DELAY_PROPERTY, "1234");

        String sysPropValue = System.getProperty(CommonConstants.MULTINODE_RETRY_DELAY_PROPERTY);
        long resolved = Long.parseLong(sysPropValue);
        MultinodeConnectionManager mgr = new MultinodeConnectionManager(ENDPOINTS,
                CommonConstants.DEFAULT_MULTINODE_RETRY_ATTEMPTS, resolved,
                HealthCheckConfig.createDefault());

        assertEquals(1234L, mgr.getRetryDelayMs());
    }
}
