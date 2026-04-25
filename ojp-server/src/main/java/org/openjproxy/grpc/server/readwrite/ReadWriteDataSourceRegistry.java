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

    // Map of primary datasource name → sticky session timeout in seconds (0 = disabled)
    private final Map<String, Integer> stickyTimeoutMap = new ConcurrentHashMap<>();

    /**
     * Registers a mapping from connection hash to primary datasource name.
     * This is used to associate client connections with their primary datasource.
     *
     * @param connectionHash the unique connection identifier
     * @param primaryName    the primary datasource name
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
     * @param primaryName       the primary datasource name
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
     *
     * @param primaryName the primary datasource name
     */
    public void clearReplicas(String primaryName) {
        replicaMap.remove(primaryName);
        log.debug("Cleared all replicas for primary: {}", primaryName);
    }

    /**
     * Removes a primary mapping for a connection hash.
     *
     * @param connectionHash the connection hash
     */
    public void removePrimaryMapping(String connectionHash) {
        primaryMappings.remove(connectionHash);
        log.debug("Removed primary mapping for connection: {}", connectionHash);
    }

    /**
     * Registers the sticky session timeout (in seconds) for a given primary datasource.
     * A value of 0 disables sticky session behaviour.
     *
     * @param primaryName          the primary datasource name
     * @param stickyTimeoutSeconds the duration in seconds to route reads to primary after a write
     */
    public void registerStickyTimeout(String primaryName, int stickyTimeoutSeconds) {
        if (primaryName == null) {
            throw new IllegalArgumentException("primaryName must not be null");
        }
        stickyTimeoutMap.put(primaryName, stickyTimeoutSeconds);
        log.debug("Registered sticky session timeout for primary '{}': {}s", primaryName, stickyTimeoutSeconds);
    }

    /**
     * Returns the sticky session timeout in seconds for the given primary datasource.
     * Returns 0 if no timeout has been registered (sticky sessions disabled).
     *
     * @param primaryName the primary datasource name
     * @return the sticky session timeout in seconds, or 0 if not configured
     */
    public int getStickySessionSeconds(String primaryName) {
        return stickyTimeoutMap.getOrDefault(primaryName, 0);
    }

    /**
     * Clears all registered datasources and mappings.
     * This is primarily for testing and cleanup purposes.
     */
    public void clear() {
        replicaMap.clear();
        primaryMappings.clear();
        stickyTimeoutMap.clear();
        log.debug("Cleared all registry data");
    }
}
