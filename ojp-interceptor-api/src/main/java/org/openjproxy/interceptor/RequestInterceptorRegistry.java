package org.openjproxy.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry for discovering and managing RequestInterceptor implementations.
 * 
 * <p>Uses Java's ServiceLoader mechanism to automatically discover interceptors
 * on the classpath and in the external ojp-libs directory.</p>
 */
public final class RequestInterceptorRegistry {
    
    private static final Logger log = LoggerFactory.getLogger(RequestInterceptorRegistry.class);
    
    private static final Map<String, RequestInterceptor> interceptors = new ConcurrentHashMap<>();
    private static volatile boolean initialized = false;
    private static final Object initLock = new Object();
    
    private RequestInterceptorRegistry() {
        // Utility class
    }
    
    /**
     * Discovers and registers all available RequestInterceptor implementations.
     */
    public static void initialize() {
        if (!initialized) {
            synchronized (initLock) {
                if (!initialized) {
                    loadInterceptors();
                    initialized = true;
                }
            }
        }
    }
    
    private static void loadInterceptors() {
        ServiceLoader<RequestInterceptor> loader = ServiceLoader.load(RequestInterceptor.class);
        
        for (RequestInterceptor interceptor : loader) {
            try {
                if (interceptor.isAvailable()) {
                    String id = interceptor.id();
                    if (id == null || id.trim().isEmpty()) {
                        log.warn("Skipping interceptor with null or empty id: {}", 
                                interceptor.getClass().getName());
                        continue;
                    }
                    
                    RequestInterceptor existing = interceptors.put(id, interceptor);
                    if (existing != null) {
                        log.warn("Interceptor '{}' from {} replaced by {}", 
                                id, existing.getClass().getName(), interceptor.getClass().getName());
                    } else {
                        log.info("Registered RequestInterceptor: {} (priority: {})", 
                                id, interceptor.getPriority());
                    }
                } else {
                    log.debug("Interceptor {} is not available, skipping", 
                            interceptor.getClass().getName());
                }
            } catch (Exception e) {
                log.error("Failed to register interceptor: {}", 
                        interceptor.getClass().getName(), e);
            }
        }
        
        log.info("Loaded {} request interceptors: {}", interceptors.size(), interceptors.keySet());
    }
    
    /**
     * Forces a reload of all interceptors.
     * This is primarily useful for testing.
     */
    public static void reload() {
        synchronized (initLock) {
            interceptors.clear();
            initialized = false;
            initialize();
        }
    }
    
    /**
     * Gets all registered interceptors sorted by priority (highest first).
     * 
     * @return list of interceptors
     */
    public static List<RequestInterceptor> getInterceptors() {
        initialize();
        return interceptors.values().stream()
                .sorted(Comparator.comparingInt(RequestInterceptor::getPriority).reversed())
                .collect(Collectors.toList());
    }
    
    /**
     * Gets interceptors filtered by request type and phase, sorted by priority.
     * 
     * @param requestType the request type to filter by
     * @param phase the lifecycle phase to filter by
     * @return list of matching interceptors
     */
    public static List<RequestInterceptor> getInterceptors(RequestType requestType, 
                                                           LifecyclePhase phase) {
        initialize();
        return interceptors.values().stream()
                .filter(i -> i.supportsRequestType(requestType))
                .filter(i -> i.supportsPhase(phase))
                .sorted(Comparator.comparingInt(RequestInterceptor::getPriority).reversed())
                .collect(Collectors.toList());
    }
    
    /**
     * Gets a specific interceptor by ID.
     * 
     * @param id the interceptor ID
     * @return optional containing the interceptor, or empty if not found
     */
    public static Optional<RequestInterceptor> getInterceptor(String id) {
        initialize();
        return Optional.ofNullable(interceptors.get(id));
    }
    
    /**
     * Registers an interceptor manually (useful for testing).
     * 
     * @param interceptor the interceptor to register
     */
    public static void registerInterceptor(RequestInterceptor interceptor) {
        if (interceptor != null && interceptor.id() != null) {
            interceptors.put(interceptor.id(), interceptor);
            log.debug("Manually registered interceptor: {}", interceptor.id());
        }
    }
    
    /**
     * Clears all registered interceptors (useful for testing).
     */
    public static void clear() {
        synchronized (initLock) {
            interceptors.clear();
            initialized = false;
        }
    }
    
    /**
     * Returns the number of registered interceptors.
     * 
     * @return the count of interceptors
     */
    public static int getCount() {
        initialize();
        return interceptors.size();
    }
}
