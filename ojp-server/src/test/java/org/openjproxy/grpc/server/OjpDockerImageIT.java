package org.openjproxy.grpc.server;

import org.junit.jupiter.api.Test;
import org.openjproxy.testcontainers.OjpContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the locally-built Docker image (jlink-based) starts without
 * missing class errors. Runs after the docker build in pre-integration-test phase.
 */
class OjpDockerImageIT {

    @Test
    void localImageStartsSuccessfully() {
        String version = System.getProperty("ojp.image.version");
        DockerImageName image = DockerImageName.parse("rrobetti/ojp:" + version)
                .asCompatibleSubstituteFor("rrobetti/ojp:0.4.2-beta");

        try (OjpContainer container = new OjpContainer(image)) {
            container.start();
            assertTrue(container.isRunning(), "OJP container should be running");
        }
    }
}
