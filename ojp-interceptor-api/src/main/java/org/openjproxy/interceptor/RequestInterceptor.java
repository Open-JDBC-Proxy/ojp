package org.openjproxy.interceptor;

/**
 * Service Provider Interface (SPI) for request lifecycle interceptors.
 * 
 * <p>Interceptors can hook into various phases of request processing to provide
 * cross-cutting concerns like monitoring, transformation, circuit breaking,
 * and resource management. This pattern is inspired by Servlet Filters and
 * implements a Chain of Responsibility approach.</p>
 * 
 * <p>Implementations should be registered via the standard Java
 * {@link java.util.ServiceLoader} mechanism by creating a file named
 * {@code META-INF/services/org.openjproxy.interceptor.RequestInterceptor}
 * containing the fully qualified class name of the implementation.</p>
 * 
 * <p>Interceptors are invoked in priority order (highest first) and can:
 * <ul>
 *   <li>Inspect and modify the request context</li>
 *   <li>Short-circuit the chain by not calling chain.proceed()</li>
 *   <li>Wrap the execution with try-catch-finally logic</li>
 *   <li>Transform SQL or results</li>
 *   <li>Acquire and release resources</li>
 *   <li>Record metrics and handle errors</li>
 * </ul>
 * 
 * <p>Example implementation:
 * <pre>{@code
 * public class MyInterceptor implements RequestInterceptor {
 *     @Override
 *     public String id() {
 *         return "my-interceptor";
 *     }
 *     
 *     @Override
 *     public int getPriority() {
 *         return 100;
 *     }
 *     
 *     @Override
 *     public void intercept(RequestContext context, InterceptorChain chain) 
 *             throws Exception {
 *         // Pre-processing
 *         long start = System.currentTimeMillis();
 *         
 *         try {
 *             // Proceed with the chain
 *             chain.proceed(context);
 *             
 *             // Post-processing on success
 *             long duration = System.currentTimeMillis() - start;
 *             recordSuccess(context, duration);
 *         } catch (Exception e) {
 *             // Handle failure
 *             recordFailure(context, e);
 *             throw e; // Re-throw to propagate
 *         } finally {
 *             // Cleanup
 *             releaseResources();
 *         }
 *     }
 * }
 * }</pre>
 * 
 * @see RequestContext
 * @see InterceptorChain
 */
public interface RequestInterceptor {
    
    /**
     * Returns the unique identifier for this interceptor.
     * 
     * @return the interceptor ID, never null or empty
     */
    String id();
    
    /**
     * Returns the priority of this interceptor for ordering.
     * Higher values indicate higher priority (executed first).
     * 
     * <p>Recommended ranges:
     * <ul>
     *   <li>1000+: Critical infrastructure (authentication, rate limiting)</li>
     *   <li>500-999: Request transformation (SQL enhancement, query rewriting)</li>
     *   <li>100-499: Resource management (circuit breaker, slow query segregation)</li>
     *   <li>0-99: Monitoring and logging</li>
     *   <li>Negative: Post-processing and cleanup</li>
     * </ul>
     * 
     * @return the interceptor priority (default: 0)
     */
    default int getPriority() {
        return 0;
    }
    
    /**
     * Checks if this interceptor is available and should be used.
     * 
     * @return true if available, false otherwise
     */
    default boolean isAvailable() {
        return true;
    }
    
    /**
     * Checks if this interceptor supports the given request type.
     * 
     * @param requestType the type of request (QUERY, UPDATE, TRANSACTION, etc.)
     * @return true if this interceptor should handle this request type
     */
    default boolean supportsRequestType(RequestType requestType) {
        return true; // By default, support all types
    }
    
    /**
     * Checks if this interceptor should run for the given lifecycle phase.
     * 
     * @param phase the lifecycle phase
     * @return true if this interceptor should run in this phase
     */
    default boolean supportsPhase(LifecyclePhase phase) {
        return true; // By default, support all phases
    }
    
    /**
     * Intercepts the request processing at various lifecycle phases.
     * 
     * <p>Implementations must call {@code chain.proceed(context)} to continue
     * the chain, or can choose to short-circuit by not calling it.</p>
     * 
     * @param context the request context containing all request information
     * @param chain the interceptor chain to continue processing
     * @throws Exception if an error occurs during interception
     */
    void intercept(RequestContext context, InterceptorChain chain) throws Exception;
}
