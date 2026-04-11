package org.openjproxy.grpc.server.readwrite;

import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing primary and replica datasource mappings in read/write splitting.
 * 
 * <p>This class maintains the mapping between primary datasources and their associated
 * read replicas. It provides thread-safe operations for registering datasources and
 * retrieving replica lists for routing decisions.
 * 
 * <p>Thread Safety: All operations are thread-safe using ConcurrentHashMap.
 */
@Slf4j
public class ReadWriteDataSourceRegistry {
    
    // Map of primary datasource name → replica list
    private final Map<String, List<DataSource>> replicaMap = new ConcurrentHashMap<>();
    
    // Map of connection hash → primary datasource name (for session affinity)
    private final Map<String, String> primaryMappings = new ConcurrentHashMap<>();
    
    /**
     * Registers a mapping from connection hash to primary datasource name.
     * This is used to associate client connections with their primary datasource.
     * 
     * @param connectionHash the unique connection identifier
     * @param primaryName the primary datasource name
     */
    public void registerPrimaryMapping(String connectionHash, String primaryName) {
        if (connectionHash == null || primaryName == null) {
            throw new IllegalArgumentException("connectionHash and primaryName must not be null");
        }
        primaryMappings.put(connectionHash, primaryName);
        log.debug("Registered primary mapping: {} -> {}", connectionHash, primaryName);
    }
    
    /**
     * Registers a replica datasource for a given primary.
     * Multiple replicas can be registered for the same primary.
     * 
     * @param primaryName the primary datasource name
     * @param replicaDataSource the replica datasource to register
     */
    public void registerReplica(String primaryName, DataSource replicaDataSource) {
        if (primaryName == null || replicaDataSource == null) {
            throw new IllegalArgumentException("primaryName and replicaDataSource must not be null");
        }
        replicaMap.computeIfAbsent(primaryName, k -> new ArrayList<>())
                  .add(replicaDataSource);
        log.debug("Registered replica for primary: {}", primaryName);
    }
    
    /**
     * Gets the list of replica datasources for a given primary.
     * 
     * @param primaryName the primary datasource name
     * @return unmodifiable list of replica datasources, empty list if no replicas registered
     */
    public List<DataSource> getReplicas(String primaryName) {
        List<DataSource> replicas = replicaMap.get(primaryName);
        if (replicas == null) {
            return Collections.emptyList();
        }
        // Return unmodifiable view to prevent external modification
        return Collections.unmodifiableList(replicas);
    }
    
    /**
     * Gets the primary datasource name for a given connection hash.
     * 
     * @param connectionHash the connection hash
     * @return the primary datasource name, or null if not found
     */
    public String getPrimaryName(String connectionHash) {
        return primaryMappings.get(connectionHash);
    }
    
    /**
     * Checks if a primary datasource has any replicas registered.
     * 
     * @param primaryName the primary datasource name
     * @return true if at least one replica is registered, false otherwise
     */
    public boolean hasReplicas(String primaryName) {
        List<DataSource> replicas = replicaMap.get(primaryName);
        return replicas != null && !replicas.isEmpty();
    }
    
    /**
     * Gets the count of replicas for a given primary.
     * 
     * @param primaryName the primary datasource name
     * @return the number of replicas, 0 if none registered
     */
    public int getReplicaCount(String primaryName) {
        List<DataSource> replicas = replicaMap.get(primaryName);
        return replicas != null ? replicas.size() : 0;
    }
    
    /**
     * Removes all registered replicas for a given primary.
     * This is useful for dynamic reconfiguration or cleanup.
     * 
     * @param primaryName the primary datasource name
     */
    public void clearReplicas(String primaryName) {
        replicaMap.remove(primaryName);
        log.debug("Cleared all replicas for primary: {}", primaryName);
    }
    
    /**
     * Removes a primary mapping for a connection hash.
     * This is typically called when a connection is closed.
     * 
     * @param connectionHash the connection hash
     */
    public void removePrimaryMapping(String connectionHash) {
        primaryMappings.remove(connectionHash);
        log.debug("Removed primary mapping for connection: {}", connectionHash);
    }
    
    /**
     * Clears all registered datasources and mappings.
     * This is primarily for testing and cleanup purposes.
     */
    public void clear() {
        replicaMap.clear();
        primaryMappings.clear();
        log.debug("Cleared all registry data");
    }
}
