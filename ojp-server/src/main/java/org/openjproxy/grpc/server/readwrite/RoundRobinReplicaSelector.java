package org.openjproxy.grpc.server.readwrite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round-robin replica selector that distributes load evenly across replicas.
 * 
 * <p>Thread-safe implementation using atomic counter for concurrent access.
 * Each call to selectReplica() rotates to the next replica in the list.
 * 
 * <p>Health checking: selectHealthyReplica() validates each replica by attempting
 * a connection test before returning. Unhealthy replicas are skipped.
 */
public class RoundRobinReplicaSelector implements ReplicaSelector {
    
    private static final Logger log = LoggerFactory.getLogger(RoundRobinReplicaSelector.class);
    
    private final AtomicInteger counter = new AtomicInteger(0);
    private final int connectionTestTimeoutMs;
    
    /**
     * Create a round-robin selector with default connection test timeout (5 seconds).
     */
    public RoundRobinReplicaSelector() {
        this(5000);
    }
    
    /**
     * Create a round-robin selector with specified connection test timeout.
     * 
     * @param connectionTestTimeoutMs Timeout in milliseconds for testing replica connectivity
     */
    public RoundRobinReplicaSelector(int connectionTestTimeoutMs) {
        this.connectionTestTimeoutMs = connectionTestTimeoutMs;
    }
    
    @Override
    public DataSource selectReplica(List<DataSource> replicas) {
        if (replicas == null || replicas.isEmpty()) {
            return null;
        }
        
        if (replicas.size() == 1) {
            return replicas.get(0);
        }
        
        // Atomic increment and wrap around using modulo
        int index = Math.abs(counter.getAndIncrement() % replicas.size());
        return replicas.get(index);
    }
    
    @Override
    public DataSource selectHealthyReplica(List<DataSource> replicas) {
        if (replicas == null || replicas.isEmpty()) {
            return null;
        }
        
        int replicaCount = replicas.size();
        int startIndex = Math.abs(counter.get() % replicaCount);
        
        // Try all replicas starting from current position
        for (int i = 0; i < replicaCount; i++) {
            int index = (startIndex + i) % replicaCount;
            DataSource replica = replicas.get(index);
            
            if (isHealthy(replica, index)) {
                // Increment counter for next call
                counter.incrementAndGet();
                return replica;
            }
        }
        
        // All replicas unhealthy
        log.warn("All {} replicas failed health check", replicaCount);
        return null;
    }
    
    @Override
    public void reset() {
        counter.set(0);
    }
    
    /**
     * Test if a replica datasource is healthy by attempting a connection.
     * 
     * @param dataSource Datasource to test
     * @param index Replica index for logging
     * @return true if datasource is healthy, false otherwise
     */
    private boolean isHealthy(DataSource dataSource, int index) {
        try (Connection conn = dataSource.getConnection()) {
            // Validate connection with timeout
            if (!conn.isValid(connectionTestTimeoutMs / 1000)) {
                log.warn("Replica {} failed health check (isValid returned false)", index);
                return false;
            }
            return true;
        } catch (SQLException e) {
            log.warn("Replica {} failed health check: {}", index, e.getMessage());
            return false;
        }
    }
}
