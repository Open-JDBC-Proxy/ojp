package org.openjproxy.datasource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.XADataSource;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service registry for XA connection pool providers.
 * 
 * <p>This class provides methods to discover and manage {@link XAConnectionPoolProvider}
 * implementations using the Java ServiceLoader mechanism. It caches discovered providers
 * and provides convenient methods for creating XA DataSources.</p>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * // Get all available XA providers
 * Map<String, XAConnectionPoolProvider> providers = XAConnectionPoolProviderRegistry.getProviders();
 * 
 * // Get a specific provider
 * Optional<XAConnectionPoolProvider> narayana = XAConnectionPoolProviderRegistry.getProvider("narayana");
 * 
 * // Create an XADataSource using the default provider
 * PoolConfig config = PoolConfig.builder()
 *     .url("jdbc:postgresql://localhost:5432/mydb")
 *     .build();
 * XADataSource xaDS = XAConnectionPoolProviderRegistry.createXADataSource(config);
 * }</pre>
 */
public final class XAConnectionPoolProviderRegistry {
    
    private static final Logger log = LoggerFactory.getLogger(XAConnectionPoolProviderRegistry.class);
    
    private static final Map<String, XAConnectionPoolProvider> providers = new ConcurrentHashMap<>();
    private static volatile boolean initialized = false;
    private static final Object initLock = new Object();
    
    private XAConnectionPoolProviderRegistry() {
        // Utility class
    }
    
    /**
     * Discovers and registers all available XAConnectionPoolProvider implementations.
     * This method is called automatically when providers are first accessed.
     */
    public static void initialize() {
        if (!initialized) {
            synchronized (initLock) {
                if (!initialized) {
                    loadProviders();
                    initialized = true;
                }
            }
        }
    }
    
    private static void loadProviders() {
        ServiceLoader<XAConnectionPoolProvider> loader = ServiceLoader.load(XAConnectionPoolProvider.class);
        
        for (XAConnectionPoolProvider provider : loader) {
            try {
                if (provider.isAvailable()) {
                    String id = provider.id();
                    if (id == null || id.trim().isEmpty()) {
                        log.warn("Skipping XA provider with null or empty id: {}", provider.getClass().getName());
                        continue;
                    }
                    
                    XAConnectionPoolProvider existing = providers.put(id, provider);
                    if (existing != null) {
                        log.warn("XA Provider '{}' from {} replaced by {}", 
                                id, existing.getClass().getName(), provider.getClass().getName());
                    } else {
                        log.info("Registered XAConnectionPoolProvider: {} (priority: {})", id, provider.getPriority());
                    }
                } else {
                    log.debug("XA Provider {} is not available, skipping", provider.getClass().getName());
                }
            } catch (Exception e) {
                log.error("Failed to register XA provider: {}", provider.getClass().getName(), e);
            }
        }
        
        log.info("Loaded {} XA connection pool providers: {}", providers.size(), providers.keySet());
    }
    
    /**
     * Forces a reload of all providers.
     * This is primarily useful for testing.
     */
    public static void reload() {
        synchronized (initLock) {
            providers.clear();
            initialized = false;
            initialize();
        }
    }
    
    /**
     * Gets all registered XA connection pool providers.
     * 
     * @return an unmodifiable map of provider IDs to providers
     */
    public static Map<String, XAConnectionPoolProvider> getProviders() {
        initialize();
        return Map.copyOf(providers);
    }
    
    /**
     * Gets a specific XA provider by its ID.
     * 
     * @param id the provider ID
     * @return an Optional containing the provider, or empty if not found
     */
    public static Optional<XAConnectionPoolProvider> getProvider(String id) {
        initialize();
        return Optional.ofNullable(providers.get(id));
    }
    
    /**
     * Gets the default XA provider (the one with highest priority).
     * 
     * @return an Optional containing the default provider, or empty if none registered
     */
    public static Optional<XAConnectionPoolProvider> getDefaultProvider() {
        initialize();
        return providers.values().stream()
                .filter(XAConnectionPoolProvider::isAvailable)
                .max(Comparator.comparingInt(XAConnectionPoolProvider::getPriority));
    }
    
    /**
     * Creates an XADataSource using the specified provider.
     * 
     * @param providerId the provider ID to use
     * @param config the pool configuration
     * @return the created XADataSource
     * @throws SQLException if XADataSource creation fails
     * @throws IllegalArgumentException if the provider is not found
     */
    public static XADataSource createXADataSource(String providerId, PoolConfig config) throws SQLException {
        XAConnectionPoolProvider provider = getProvider(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown XA provider: " + providerId));
        
        log.debug("Creating XADataSource using provider '{}' for URL: {}", providerId, config.getUrl());
        return provider.createXADataSource(config);
    }
    
    /**
     * Creates an XADataSource using the default (highest priority) provider.
     * 
     * @param config the pool configuration
     * @return the created XADataSource
     * @throws SQLException if XADataSource creation fails
     * @throws IllegalStateException if no providers are available
     */
    public static XADataSource createXADataSource(PoolConfig config) throws SQLException {
        XAConnectionPoolProvider provider = getDefaultProvider()
                .orElseThrow(() -> new IllegalStateException("No XA connection pool providers available"));
        
        log.debug("Creating XADataSource using default provider '{}' for URL: {}", provider.id(), config.getUrl());
        return provider.createXADataSource(config);
    }
    
    /**
     * Closes an XADataSource using the appropriate provider.
     * 
     * @param providerId the provider ID that created the XADataSource
     * @param xaDataSource the XADataSource to close
     * @throws Exception if closing fails
     */
    public static void closeXADataSource(String providerId, XADataSource xaDataSource) throws Exception {
        XAConnectionPoolProvider provider = getProvider(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown XA provider: " + providerId));
        
        log.debug("Closing XADataSource using provider '{}'", providerId);
        provider.closeXADataSource(xaDataSource);
    }
    
    /**
     * Gets statistics for an XADataSource from the appropriate provider.
     * 
     * @param providerId the provider ID that created the XADataSource
     * @param xaDataSource the XADataSource to get statistics for
     * @return statistics map
     */
    public static Map<String, Object> getXAStatistics(String providerId, XADataSource xaDataSource) {
        return getProvider(providerId)
                .map(provider -> provider.getXAStatistics(xaDataSource))
                .orElse(Map.of());
    }
    
    /**
     * Gets a list of available XA provider IDs.
     * 
     * @return list of available provider IDs sorted by priority (highest first)
     */
    public static java.util.List<String> getAvailableProviderIds() {
        initialize();
        return providers.values().stream()
                .filter(XAConnectionPoolProvider::isAvailable)
                .sorted(Comparator.comparingInt(XAConnectionPoolProvider::getPriority).reversed())
                .map(XAConnectionPoolProvider::id)
                .collect(java.util.stream.Collectors.toList());
    }
    
    /**
     * Registers a provider manually (useful for testing).
     * 
     * @param provider the provider to register
     */
    public static void registerProvider(XAConnectionPoolProvider provider) {
        if (provider != null && provider.id() != null) {
            providers.put(provider.id(), provider);
            log.debug("Manually registered XA provider: {}", provider.id());
        }
    }
    
    /**
     * Clears all registered providers (useful for testing).
     */
    public static void clear() {
        synchronized (initLock) {
            providers.clear();
            initialized = false;
        }
    }
}
