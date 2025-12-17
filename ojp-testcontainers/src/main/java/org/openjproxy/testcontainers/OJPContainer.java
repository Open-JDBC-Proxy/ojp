package org.openjproxy.testcontainers;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

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
    
    private static final String DEFAULT_IMAGE_NAME = "rrobetti/ojp";
    private static final String DEFAULT_TAG = "0.3.1-snapshot";
    private static final int DEFAULT_GRPC_PORT = 1059;
    private static final int DEFAULT_PROMETHEUS_PORT = 9159;
    
    private boolean telemetryEnabled = true; // Enabled by default
    
    /**
     * Creates an OJP container with the default image.
     */
    public OJPContainer() {
        this(DEFAULT_IMAGE_NAME + ":" + DEFAULT_TAG);
    }
    
    /**
     * Creates an OJP container with a custom Docker image.
     * 
     * @param dockerImageName the Docker image name (e.g., "myregistry/ojp:1.0.0")
     */
    public OJPContainer(String dockerImageName) {
        super(DockerImageName.parse(dockerImageName));
        
        // Expose default gRPC port and Prometheus port
        // Both ports will be mapped to random available ports to avoid conflicts
        withExposedPorts(DEFAULT_GRPC_PORT, DEFAULT_PROMETHEUS_PORT);
        
        // Wait for health check
        waitingFor(Wait.forHealthcheck());
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
