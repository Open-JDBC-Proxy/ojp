package org.openjproxy.testcontainers;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * TestContainer for OJP (Open JDBC Proxy) Server.
 * <p>
 * This container provides a ready-to-use OJP Server instance for integration testing.
 * The OJP Server acts as a smart proxy between applications and relational databases,
 * providing intelligent connection pooling and management.
 * </p>
 *
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * @Container
 * public static OjpContainer ojpContainer = new OjpContainer();
 *
 * @Test
 * public void testWithOjp() {
 *     String connectionString = ojpContainer.getOjpConnectionString();
 *     // Use connectionString in your JDBC URL:
 *     // "jdbc:ojp[" + connectionString + "]_postgresql://localhost:5432/mydb"
 * }
 * }</pre>
 *
 * <h2>Custom Docker Image:</h2>
 * <pre>{@code
 * OjpContainer ojpContainer = new OjpContainer(
 *     DockerImageName.parse("rrobetti/ojp:0.3.2-snapshot")
 * );
 * }</pre>
 *
 * @see <a href="https://github.com/Open-J-Proxy/ojp">OJP GitHub Repository</a>
 */
public class OjpContainer extends GenericContainer<OjpContainer> {

    private static final DockerImageName DEFAULT_IMAGE_NAME = 
        DockerImageName.parse("rrobetti/ojp:0.3.1-beta");
    
    /**
     * Default OJP Server port (internal container port).
     * The actual mapped port on the host is randomly assigned by TestContainers.
     */
    private static final int OJP_PORT = 1059;

    /**
     * Creates an OjpContainer with the default Docker image.
     */
    public OjpContainer() {
        this(DEFAULT_IMAGE_NAME);
    }

    /**
     * Creates an OjpContainer with a custom Docker image.
     *
     * @param dockerImageName the Docker image name to use
     */
    public OjpContainer(DockerImageName dockerImageName) {
        super(dockerImageName);
        dockerImageName.assertCompatibleWith(DEFAULT_IMAGE_NAME);
        
        // Expose the OJP port
        addExposedPorts(OJP_PORT);
        
        // Wait for the container to be ready
        // OJP should start and be ready to accept connections
        waitingFor(Wait.forListeningPort());
    }

    /**
     * Creates an OjpContainer with a custom Docker image tag.
     *
     * @param imageTag the Docker image tag (e.g., "0.3.1-beta")
     */
    public OjpContainer(String imageTag) {
        this(DockerImageName.parse("rrobetti/ojp:" + imageTag));
    }

    /**
     * Get the host and port to connect to OJP.
     * <p>
     * Use this in your JDBC URL:
     * {@code "jdbc:ojp[" + getOjpConnectionString() + "]_postgresql://..."}
     * </p>
     *
     * @return Connection string in format "host:port"
     */
    public String getOjpConnectionString() {
        return getHost() + ":" + getMappedPort(OJP_PORT);
    }

    /**
     * Get the mapped port for OJP.
     *
     * @return The mapped port number
     */
    public Integer getOjpPort() {
        return getMappedPort(OJP_PORT);
    }

    /**
     * Get the OJP Server host.
     *
     * @return The container host
     */
    public String getOjpHost() {
        return getHost();
    }
}
