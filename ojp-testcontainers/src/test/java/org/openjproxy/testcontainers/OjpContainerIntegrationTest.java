package org.openjproxy.testcontainers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for OjpContainer.
 * <p>
 * These tests start a real Docker container and verify that OJP Server is accessible.
 * They are disabled by default and must be explicitly enabled via the system property:
 * {@code -DenableOjpContainerTests=true}
 * </p>
 *
 * <p>Requires Docker to be available in the test environment.</p>
 */
@EnabledIf("org.openjproxy.testcontainers.OjpContainerIntegrationTest#isEnabled")
class OjpContainerIntegrationTest {

    /**
     * Returns {@code true} when the {@code enableOjpContainerTests} system property is set to {@code true}.
     * Used by {@link EnabledIf} to conditionally enable this test class.
     */
    static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("enableOjpContainerTests", "false"));
    }

    /**
     * Verifies that the OJP container starts successfully and is reachable on its exposed port.
     * <ol>
     *   <li>Starts an {@link OjpContainer} using the default Docker image.</li>
     *   <li>Asserts that the container is running.</li>
     *   <li>Asserts that a TCP connection can be established to the OJP port.</li>
     *   <li>Asserts that {@link OjpContainer#getOjpConnectionString()} returns the expected value.</li>
     * </ol>
     */
    @Test
    void testContainerStartsAndIsReachable() throws IOException {
        try (OjpContainer container = new OjpContainer()) {
            container.start();

            assertTrue(container.isRunning(), "OJP container should be running after start()");

            String host = container.getOjpHost();
            int port = container.getOjpPort();

            assertNotNull(host, "OJP host must not be null");
            assertTrue(port > 0, "OJP mapped port must be a positive number");

            // Verify TCP-level connectivity to the OJP server port
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 5_000);
                assertTrue(socket.isConnected(), "Should be able to open a TCP connection to OJP port");
            }

            assertEquals(host + ":" + port, container.getOjpConnectionString(),
                    "getOjpConnectionString() must return 'host:port'");
        }
    }
}
