package org.openjproxy.datasource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service registry for connection pool PROVIDERS.
 *
 * <p>This class provides methods to discover and manage {@link ConnectionPoolProvider}
 * implementations using the Java ServiceLoader mechanism. It caches discovered PROVIDERS
 * and provides convenient methods for creating DataSources.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * // Get all available PROVIDERS
 * Map<String, ConnectionPoolProvider> PROVIDERS = ConnectionPoolProviderRegistry.getProviders();
 *
 * // Get a specific provider
 * Optional<ConnectionPoolProvider> dbcp = ConnectionPoolProviderRegistry.getProvider("dbcp");
 *
 * // Get the default (highest priority) provider
 * Optional<ConnectionPoolProvider> defaultProvider = ConnectionPoolProviderRegistry.getDefaultProvider();
 *
 * // Create a DataSource using the default provider
 * PoolConfig config = PoolConfig.builder()
 *     .url("jdbc:h2:mem:test")
 *     .build();
 * DataSource ds = ConnectionPoolProviderRegistry.createDataSource(config);
 * }</pre>
 */
public final class ConnectionPoolProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConnectionPoolProviderRegistry.class);

    private static final Map<String, ConnectionPoolProvider> PROVIDERS = new ConcurrentHashMap<>();
    private static volatile boolean initialized = false;
    private static final Object INIT_LOCK = new Object();

    private ConnectionPoolProviderRegistry() {
        // Utility class
    }

    /**
     * Discovers and registers all available ConnectionPoolProvider implementations.
     * This method is called automatically when PROVIDERS are first accessed.
     */
    public static void initialize() {
        if (!initialized) {
            synchronized (INIT_LOCK) {
                if (!initialized) {
                    loadProviders();
                    initialized = true;
                }
            }
        }
    }

    private static void loadProviders() {
        ServiceLoader<ConnectionPoolProvider> loader = ServiceLoader.load(ConnectionPoolProvider.class);

        for (ConnectionPoolProvider provider : loader) {
            try {
                if (provider.isAvailable()) {
                    String id = provider.id();
                    if (id == null || id.trim().isEmpty()) {
                        log.warn("Skipping provider with null or empty id: {}", provider.getClass().getName());
                        continue;
                    }

                    ConnectionPoolProvider existing = PROVIDERS.put(id, provider);
                    if (existing != null) {
                        log.warn("Provider '{}' from {} replaced by {}",
                                id, existing.getClass().getName(), provider.getClass().getName());
                    } else {
                        log.info("Registered ConnectionPoolProvider: {} (priority: {})", id, provider.getPriority());
                    }
                } else {
                    log.debug("Provider {} is not available, skipping", provider.getClass().getName());
                }
            } catch (Exception e) {
                log.error("Failed to register provider: {}", provider.getClass().getName(), e);
            }
        }

        log.info("Loaded {} connection pool PROVIDERS: {}", PROVIDERS.size(), PROVIDERS.keySet());
    }

    /**
     * Forces a reload of all PROVIDERS.
     * This is primarily useful for testing.
     */
    public static void reload() {
        synchronized (INIT_LOCK) {
            PROVIDERS.clear();
            initialized = false;
            initialize();
        }
    }

    /**
     * Gets all registered connection pool PROVIDERS.
     *
     * @return an unmodifiable map of provider IDs to PROVIDERS
     */
    public static Map<String, ConnectionPoolProvider> getProviders() {
        initialize();
        return Map.copyOf(PROVIDERS);
    }

    /**
     * Gets a specific provider by its ID.
     *
     * @param id the provider ID
     * @return an Optional containing the provider, or empty if not found
     */
    public static Optional<ConnectionPoolProvider> getProvider(String id) {
        initialize();
        return Optional.ofNullable(PROVIDERS.get(id));
    }

    /**
     * Gets the default provider (the one with highest priority).
     *
     * @return an Optional containing the default provider, or empty if none registered
     */
    public static Optional<ConnectionPoolProvider> getDefaultProvider() {
        initialize();
        return PROVIDERS.values().stream()
                .filter(ConnectionPoolProvider::isAvailable)
                .max(Comparator.comparingInt(ConnectionPoolProvider::getPriority));
    }

    /**
     * Creates a DataSource using the specified provider.
     *
     * @param providerId the provider ID to use
     * @param config the pool configuration
     * @return the created DataSource
     * @throws SQLException if DataSource creation fails
     * @throws IllegalArgumentException if the provider is not found
     */
    public static DataSource createDataSource(String providerId, PoolConfig config) throws SQLException {
        ConnectionPoolProvider provider = getProvider(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider: " + providerId));

        log.debug("Creating DataSource using provider '{}' for URL: {}", providerId, config.getUrl());
        return provider.createDataSource(config);
    }

    /**
     * Creates a DataSource using the default (highest priority) provider.
     *
     * @param config the pool configuration
     * @return the created DataSource
     * @throws SQLException if DataSource creation fails
     * @throws IllegalStateException if no PROVIDERS are available
     */
    public static DataSource createDataSource(PoolConfig config) throws SQLException {
        ConnectionPoolProvider provider = getDefaultProvider()
                .orElseThrow(() -> new IllegalStateException("No connection pool PROVIDERS available"));

        log.debug("Creating DataSource using default provider '{}' for URL: {}", provider.id(), config.getUrl());
        return provider.createDataSource(config);
    }

    /**
     * Closes a DataSource using the appropriate provider.
     *
     * @param providerId the provider ID that created the DataSource
     * @param dataSource the DataSource to close
     * @throws Exception if closing fails
     */
    public static void closeDataSource(String providerId, DataSource dataSource) throws Exception {
        ConnectionPoolProvider provider = getProvider(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider: " + providerId));

        log.debug("Closing DataSource using provider '{}'", providerId);
        provider.closeDataSource(dataSource);
    }

    /**
     * Gets statistics for a DataSource from the appropriate provider.
     *
     * @param providerId the provider ID that created the DataSource
     * @param dataSource the DataSource to get statistics for
     * @return statistics map
     */
    public static Map<String, Object> getStatistics(String providerId, DataSource dataSource) {
        return getProvider(providerId)
                .map(provider -> provider.getStatistics(dataSource))
                .orElse(Map.of());
    }

    /**
     * Gets a list of available provider IDs.
     *
     * @return list of available provider IDs sorted by priority (highest first)
     */
    public static java.util.List<String> getAvailableProviderIds() {
        initialize();
        return PROVIDERS.values().stream()
                .filter(ConnectionPoolProvider::isAvailable)
                .sorted(Comparator.comparingInt(ConnectionPoolProvider::getPriority).reversed())
                .map(ConnectionPoolProvider::id)
                .collect(Collectors.toList());
    }

    /**
     * Registers a provider manually (useful for testing).
     *
     * @param provider the provider to register
     */
    public static void registerProvider(ConnectionPoolProvider provider) {
        if (provider != null && provider.id() != null) {
            PROVIDERS.put(provider.id(), provider);
            log.debug("Manually registered provider: {}", provider.id());
        }
    }

    /**
     * Clears all registered PROVIDERS (useful for testing).
     */
    public static void clear() {
        synchronized (INIT_LOCK) {
            PROVIDERS.clear();
            initialized = false;
        }
    }
}
