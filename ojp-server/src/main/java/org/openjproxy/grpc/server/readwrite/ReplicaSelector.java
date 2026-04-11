package org.openjproxy.grpc.server.readwrite;

import javax.sql.DataSource;
import java.util.List;

/**
 * Strategy interface for selecting a replica from a pool of available replicas.
 * Implementations can provide different selection algorithms (round-robin, random, least-connections, etc.).
 * 
 * Thread-safety: Implementations must be thread-safe for concurrent access.
 */
public interface ReplicaSelector {
    
    /**
     * Select a single replica from the available pool using the implementation's strategy.
     * 
     * @param replicas List of available replica datasources
     * @return Selected replica datasource, or null if no replicas available
     */
    DataSource selectReplica(List<DataSource> replicas);
    
    /**
     * Attempt to select a healthy replica by trying all available replicas in rotation.
     * This method provides failover capability by attempting each replica until one succeeds.
     * 
     * <p>The implementation should:
     * <ul>
     *   <li>Try replicas in order based on the selection strategy</li>
     *   <li>Test each replica for basic connectivity/health</li>
     *   <li>Return the first healthy replica found</li>
     *   <li>Return null only if all replicas are unhealthy or unavailable</li>
     * </ul>
     * 
     * @param replicas List of available replica datasources
     * @return First healthy replica found, or null if all replicas are unhealthy
     */
    DataSource selectHealthyReplica(List<DataSource> replicas);
    
    /**
     * Reset the selector's internal state (e.g., round-robin counter).
     * Useful for testing or when replica pool configuration changes.
     */
    void reset();
}
