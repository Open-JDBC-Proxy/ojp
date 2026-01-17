package org.openjproxy.testcontainers;

import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OjpContainer.
 * These tests verify the container configuration and behavior.
 */
class OjpContainerTest {

    @Test
    void testDefaultConstructor() {
        try (OjpContainer container = new OjpContainer()) {
            assertNotNull(container);
            assertEquals(1, container.getExposedPorts().size());
            assertTrue(container.getExposedPorts().contains(1059));
        }
    }

    @Test
    void testConstructorWithImageTag() {
        try (OjpContainer container = new OjpContainer("0.3.1-beta")) {
            assertNotNull(container);
            assertEquals(1, container.getExposedPorts().size());
            assertTrue(container.getExposedPorts().contains(1059));
        }
    }

    @Test
    void testConstructorWithDockerImageName() {
        DockerImageName imageName = DockerImageName.parse("rrobetti/ojp:0.3.1-beta");
        try (OjpContainer container = new OjpContainer(imageName)) {
            assertNotNull(container);
            assertEquals(1, container.getExposedPorts().size());
            assertTrue(container.getExposedPorts().contains(1059));
        }
    }

    @Test
    void testIncompatibleImageNameThrowsException() {
        DockerImageName incompatibleImage = DockerImageName.parse("postgres:15");
        assertThrows(IllegalStateException.class, () -> {
            new OjpContainer(incompatibleImage);
        });
    }

    @Test
    void testStartAndGetConnectionInfo() {
        try (OjpContainer container = new OjpContainer()) {
            container.start();

            // Test host
            assertNotNull(container.getOjpHost());
            assertFalse(container.getOjpHost().isEmpty());

            // Test port
            assertNotNull(container.getOjpPort());
            assertTrue(container.getOjpPort() > 0);

            // Test connection string
            String connectionString = container.getOjpConnectionString();
            assertNotNull(connectionString);
            assertTrue(connectionString.contains(":"));
            assertEquals(container.getOjpHost() + ":" + container.getOjpPort(), connectionString);
        }
    }

    @Test
    void testContainerIsReady() {
        try (OjpContainer container = new OjpContainer()) {
            container.start();
            assertTrue(container.isRunning());
        }
    }
}
