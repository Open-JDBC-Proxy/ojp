package org.openjproxy.interceptor.sql;

import com.openjproxy.grpc.SessionInfo;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.interceptor.*;

/**
 * RequestInterceptor implementation that performs SQL enhancement using Apache Calcite.
 * 
 * <p>This interceptor hooks into the PRE_EXECUTION phase to transform SQL queries
 * before they are executed against the database. It provides:</p>
 * <ul>
 *   <li>SQL validation and parsing</li>
 *   <li>Query optimization using rule-based transformations</li>
 *   <li>SQL dialect translation</li>
 *   <li>Caching for fast repeated queries</li>
 * </ul>
 * 
 * <p>The interceptor is disabled by default and must be explicitly enabled via configuration.</p>
 */
@Slf4j
public class SqlEnhancerInterceptor implements RequestInterceptor {
    
    private final SqlEnhancerEngine engine;
    private final boolean enabled;
    
    /**
     * Creates a new SqlEnhancerInterceptor with the specified configuration.
     * 
     * @param engine the SQL enhancer engine
     * @param enabled whether the interceptor is enabled
     */
    public SqlEnhancerInterceptor(SqlEnhancerEngine engine, boolean enabled) {
        this.engine = engine;
        this.enabled = enabled;
        log.info("SQL Enhancer Interceptor initialized (enabled: {})", enabled);
    }
    
    /**
     * No-arg constructor for ServiceLoader.
     * Reads configuration from system properties.
     */
    public SqlEnhancerInterceptor() {
        boolean isEnabled = Boolean.parseBoolean(
            System.getProperty("ojp.sql.enhancer.enabled", "false")
        );
        
        if (isEnabled) {
            String mode = System.getProperty("ojp.sql.enhancer.mode", "VALIDATE");
            String dialect = System.getProperty("ojp.sql.enhancer.dialect", "GENERIC");
            
            SqlEnhancerMode enhancerMode = SqlEnhancerMode.fromString(mode);
            
            this.engine = new SqlEnhancerEngine(
                true,
                dialect,
                "",  // No target dialect by default
                enhancerMode.isConversionEnabled(),
                enhancerMode.isOptimizationEnabled(),
                OptimizationMode.SYNC,
                null,  // Use default rules
                null,  // No schema cache
                null,  // No schema loader
                null,  // No datasource
                null,  // No catalog
                null,  // No schema
                0      // No refresh
            );
            this.enabled = true;
            log.info("SQL Enhancer Interceptor initialized from system properties (mode: {}, dialect: {})", 
                    mode, dialect);
        } else {
            this.engine = null;
            this.enabled = false;
            log.debug("SQL Enhancer Interceptor disabled");
        }
    }
    
    @Override
    public String id() {
        return "sql-enhancer";
    }
    
    @Override
    public int getPriority() {
        // Medium priority (500-999 range for transformation)
        return 600;
    }
    
    @Override
    public boolean isAvailable() {
        return enabled && engine != null;
    }
    
    @Override
    public boolean supportsRequestType(RequestType requestType) {
        // Support QUERY and UPDATE types
        return requestType == RequestType.QUERY || requestType == RequestType.UPDATE;
    }
    
    @Override
    public boolean supportsPhase(LifecyclePhase phase) {
        // Only run during PRE_EXECUTION phase
        return phase == LifecyclePhase.PRE_EXECUTION;
    }
    
    @Override
    public void intercept(RequestContext context, InterceptorChain chain) throws Exception {
        if (!enabled || engine == null || !engine.isEnabled()) {
            // Pass through if disabled
            chain.proceed(context);
            return;
        }
        
        String originalSql = context.getCurrentSql();
        if (originalSql == null || originalSql.trim().isEmpty()) {
            chain.proceed(context);
            return;
        }
        
        try {
            // Enhance the SQL
            SqlEnhancementResult result = engine.enhance(originalSql);
            
            if (result != null && result.isModified()) {
                String enhancedSql = result.getEnhancedSql();
                log.debug("SQL enhanced from: {} to: {}", originalSql, enhancedSql);
                
                // Update the SQL in the context
                context.setCurrentSql(enhancedSql);
                
                // Store the enhancement result in context attributes for later use
                context.setAttribute("sql.enhancement.result", result);
                context.setAttribute("sql.enhancement.modified", true);
            } else {
                log.debug("SQL not modified: {}", originalSql);
                context.setAttribute("sql.enhancement.modified", false);
            }
            
        } catch (Exception e) {
            log.warn("Failed to enhance SQL: {}. Error: {}. Using original SQL.", 
                    originalSql, e.getMessage());
            // Don't fail the request, just log and continue with original SQL
            context.setAttribute("sql.enhancement.error", e.getMessage());
        }
        
        // Continue the chain
        chain.proceed(context);
    }
}
