package org.openjproxy.testcontainers;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.stream.Stream;

/**
 * TestContainer for OJP (Open J Proxy) server.
 * Provides an easy way to run OJP server in integration tests.
 * 
 * <p>The OJP server acts as a proxy - it doesn't need database configuration at startup.
 * Database connection details are passed through the JDBC URL when your tests connect.</p>
 * 
 * <p>Example usage:</p>
 * <pre>
 * &#64;Container
 * static OJPContainer ojp = new OJPContainer();
 * 
 * // In your test - database config is in the JDBC URL
 * String jdbcUrl = "jdbc:ojp[" + ojp.getHost() + ":" + ojp.getGrpcPort() + "]_" +
 *                  "postgresql://localhost:5432/test";
 * Connection conn = DriverManager.getConnection(jdbcUrl, "user", "pass");
 * </pre>
 */
public class OJPContainer extends GenericContainer<OJPContainer> {
    
    private static final int DEFAULT_GRPC_PORT = 1059;
    private static final int DEFAULT_PROMETHEUS_PORT = 9159;
    
    private boolean telemetryEnabled = true; // Enabled by default
    
    /**
     * Creates an OJP container by building a Docker image from the local ojp-server JAR.
     * This eliminates the need for pre-published Docker images.
     * 
     * <p>Prerequisites: Run 'mvn clean install' to build the ojp-server JAR before running tests.</p>
     */
    public OJPContainer() {
        this(buildImageFromLocalJar());
    }
    
    /**
     * Creates an OJP container with a custom Docker image.
     * 
     * @param dockerImageName the Docker image name (e.g., "myregistry/ojp:1.0.0")
     */
    public OJPContainer(String dockerImageName) {
        super(DockerImageName.parse(dockerImageName));
        commonSetup();
    }
    
    /**
     * Creates an OJP container from a dynamically built image.
     * 
     * @param imageFromDockerfile the image builder
     */
    private OJPContainer(ImageFromDockerfile imageFromDockerfile) {
        super(imageFromDockerfile);
        commonSetup();
    }
    
    /**
     * Common setup for all constructors.
     */
    private void commonSetup() {
        // Expose default gRPC port and Prometheus port
        // Both ports will be mapped to random available ports to avoid conflicts
        withExposedPorts(DEFAULT_GRPC_PORT, DEFAULT_PROMETHEUS_PORT);
        
        // Wait for health check with timeout
        waitingFor(Wait.forHealthcheck().withStartupTimeout(Duration.ofSeconds(60)));
    }
    
    /**
     * Builds a Docker image from the local ojp-server JAR file.
     * 
     * @return ImageFromDockerfile that builds the OJP container image
     * @throws IllegalStateException if the ojp-server JAR cannot be found
     */
    private static ImageFromDockerfile buildImageFromLocalJar() {
        Path ojpServerJar = findOjpServerJar();
        
        return new ImageFromDockerfile()
            .withDockerfileFromBuilder(builder -> builder
                .from("eclipse-temurin:21-jre-alpine")
                .copy("ojp-server.jar", "/app/ojp-server.jar")
                .workDir("/app")
                .expose(DEFAULT_GRPC_PORT, DEFAULT_PROMETHEUS_PORT)
                .entryPoint("java", "-jar", "ojp-server.jar")
                .build())
            .withFileFromPath("ojp-server.jar", ojpServerJar);
    }
    
    /**
     * Finds the ojp-server shaded JAR file in the Maven build output.
     * 
     * @return Path to the ojp-server JAR
     * @throws IllegalStateException if the JAR cannot be found
     */
    private static Path findOjpServerJar() {
        // Try common locations relative to the test module
        Path[] searchPaths = {
            Paths.get("../ojp-server/target"),
            Paths.get("../../ojp-server/target"),
            Paths.get("ojp-server/target"),
            Paths.get("target")
        };
        
        for (Path searchPath : searchPaths) {
            if (Files.exists(searchPath) && Files.isDirectory(searchPath)) {
                try (Stream<Path> files = Files.walk(searchPath, 1)) {
                    Path jarFile = files
                        .filter(path -> path.getFileName().toString().matches("ojp-server-.*-shaded\\.jar"))
                        .findFirst()
                        .orElse(null);
                    
                    if (jarFile != null && Files.exists(jarFile)) {
                        return jarFile.toAbsolutePath();
                    }
                } catch (IOException e) {
                    // Continue searching
                }
            }
        }
        
        throw new IllegalStateException(
            "Cannot find ojp-server-*-shaded.jar. " +
            "Please run 'mvn clean install' to build the OJP server JAR before running tests. " +
            "Searched paths: " + String.join(", ", 
                Stream.of(searchPaths).map(Path::toString).toArray(String[]::new))
        );
    }
    
    /**
     * Get the gRPC connection string for OJP server.
     * Use this to construct your JDBC URL.
     * 
     * @return gRPC connection string (e.g., "localhost:32768")
     */
    public String getGrpcUrl() {
        return getHost() + ":" + getMappedPort(DEFAULT_GRPC_PORT);
    }
    
    /**
     * Get the mapped gRPC port.
     * The port is randomly assigned to avoid conflicts.
     * 
     * @return The host port mapped to the container's gRPC port
     */
    public int getGrpcPort() {
        return getMappedPort(DEFAULT_GRPC_PORT);
    }
    
    /**
     * Build an OJP JDBC URL from the original database JDBC URL.
     * This is a convenience method to construct the proper OJP JDBC URL format.
     * 
     * <p>Example:</p>
     * <pre>
     * String ojpUrl = ojp.buildJdbcUrl("jdbc:postgresql://localhost:5432/test");
     * // Returns: "jdbc:ojp[localhost:32768]_postgresql://localhost:5432/test"
     * </pre>
     * 
     * @param originalJdbcUrl The original database JDBC URL
     * @return OJP-prefixed JDBC URL
     */
    public String buildJdbcUrl(String originalJdbcUrl) {
        // Remove "jdbc:" prefix from original URL
        String dbUrl = originalJdbcUrl.startsWith("jdbc:") 
            ? originalJdbcUrl.substring(5) 
            : originalJdbcUrl;
        
        return "jdbc:ojp[" + getHost() + ":" + getMappedPort(DEFAULT_GRPC_PORT) + "]_" + dbUrl;
    }
    
    /**
     * Enable or disable telemetry/Prometheus metrics.
     * Telemetry is enabled by default.
     * 
     * @param enabled true to enable telemetry, false to disable
     * @return this container instance for method chaining
     */
    public OJPContainer withTelemetryEnabled(boolean enabled) {
        this.telemetryEnabled = enabled;
        withEnv("ojp.opentelemetry.enabled", String.valueOf(enabled));
        return this;
    }
    
    /**
     * Get the Prometheus metrics endpoint URL.
     * The Prometheus port is automatically mapped to a random available port
     * to avoid conflicts when running multiple containers.
     * 
     * @return Prometheus metrics URL (e.g., "http://localhost:54321/metrics")
     * @throws IllegalStateException if telemetry is disabled
     */
    public String getPrometheusUrl() {
        if (!telemetryEnabled) {
            throw new IllegalStateException("Telemetry is disabled. Enable it with withTelemetryEnabled(true)");
        }
        return "http://" + getHost() + ":" + getMappedPort(DEFAULT_PROMETHEUS_PORT) + "/metrics";
    }
    
    /**
     * Get the mapped Prometheus port.
     * The port is randomly assigned to avoid conflicts.
     * 
     * @return The host port mapped to the container's Prometheus port
     */
    public int getPrometheusPort() {
        return getMappedPort(DEFAULT_PROMETHEUS_PORT);
    }
}
