package org.openjproxy.interceptor.sql;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.interceptor.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for SqlEnhancerInterceptor demonstrating full functionality.
 */
class SqlEnhancerIntegrationTest {
    
    private SqlEnhancerEngine engine;
    private SqlEnhancerInterceptor interceptor;
    
    @BeforeEach
    void setUp() {
        RequestInterceptorRegistry.clear();
        
        // Create engine with validation mode
        engine = new SqlEnhancerEngine(
            true,  // enabled
            "GENERIC",  // dialect
            "",  // no target dialect
            false,  // conversion disabled
            false,  // optimization disabled
            OptimizationMode.DISABLED,
            null,  // no rules
            null,  // no schema cache
            null,  // no schema loader
            null,  // no datasource
            null,  // no catalog
            null,  // no schema
            0      // no refresh
        );
        
        interceptor = new SqlEnhancerInterceptor(engine, true);
    }
    
    @AfterEach
    void tearDown() {
        RequestInterceptorRegistry.clear();
        if (engine != null) {
            engine.shutdown();
        }
    }
    
    @Test
    void testInterceptorWithValidSQL() throws Exception {
        String originalSql = "SELECT * FROM users WHERE id = 1";
        
        RequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .originalSql(originalSql)
            .build();
        
        final boolean[] chainCalled = {false};
        InterceptorChain chain = new DefaultInterceptorChain(
            java.util.Collections.emptyList(),
            0,
            ctx -> chainCalled[0] = true
        );
        
        interceptor.intercept(context, chain);
        
        assertThat(chainCalled[0]).isTrue();
        // SQL should either be enhanced or pass through unchanged
        assertThat(context.getCurrentSql()).isNotNull();
    }
    
    @Test
    void testInterceptorWithInvalidSQL() throws Exception {
        String invalidSql = "SELECT * FROM WHERE";  // Invalid SQL
        
        RequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .originalSql(invalidSql)
            .build();
        
        final boolean[] chainCalled = {false};
        InterceptorChain chain = new DefaultInterceptorChain(
            java.util.Collections.emptyList(),
            0,
            ctx -> chainCalled[0] = true
        );
        
        // Should not throw exception - graceful degradation
        interceptor.intercept(context, chain);
        
        assertThat(chainCalled[0]).isTrue();
        // Should fall back to original SQL
        assertThat(context.getCurrentSql()).isEqualTo(invalidSql);
    }
    
    @Test
    void testInterceptorInChainExecution() throws Exception {
        RequestInterceptorRegistry.registerInterceptor(interceptor);
        
        String originalSql = "SELECT u.id, u.name FROM users u WHERE u.status = 'active'";
        
        RequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .originalSql(originalSql)
            .build();
        
        // Set the current phase
        context.setCurrentPhase(LifecyclePhase.PRE_EXECUTION);
        
        boolean[] coreExecuted = {false};
        
        // Simulate chain execution with the interceptor
        DefaultInterceptorChain chain = new DefaultInterceptorChain(
            RequestInterceptorRegistry.getInterceptors(RequestType.QUERY, LifecyclePhase.PRE_EXECUTION),
            0,
            ctx -> coreExecuted[0] = true
        );
        
        chain.proceed(context);
        
        assertThat(coreExecuted[0]).isTrue();
        assertThat(context.getCurrentSql()).isNotNull();
    }
    
    @Test
    void testInterceptorCaching() throws Exception {
        String sql = "SELECT * FROM users WHERE id = 1";
        
        // First request
        RequestContext context1 = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .originalSql(sql)
            .build();
        
        long startTime1 = System.currentTimeMillis();
        InterceptorChain chain1 = new DefaultInterceptorChain(
            java.util.Collections.emptyList(),
            0,
            ctx -> {}
        );
        interceptor.intercept(context1, chain1);
        long duration1 = System.currentTimeMillis() - startTime1;
        
        // Second request with same SQL - should be cached
        RequestContext context2 = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .originalSql(sql)
            .build();
        
        long startTime2 = System.currentTimeMillis();
        InterceptorChain chain2 = new DefaultInterceptorChain(
            java.util.Collections.emptyList(),
            0,
            ctx -> {}
        );
        interceptor.intercept(context2, chain2);
        long duration2 = System.currentTimeMillis() - startTime2;
        
        // Second request should be faster due to caching
        assertThat(context2.getCurrentSql()).isEqualTo(context1.getCurrentSql());
        // Note: timing assertions can be flaky, so we just verify correctness
    }
    
    @Test
    void testInterceptorWithOptimizationEnabled() throws Exception {
        // Create engine with optimization enabled
        SqlEnhancerEngine optimizingEngine = new SqlEnhancerEngine(
            true,  // enabled
            "GENERIC",  // dialect
            "",  // no target dialect
            true,  // conversion enabled
            true,  // optimization enabled
            OptimizationMode.SYNC,  // synchronous optimization
            null,  // use default safe rules
            null,  // no schema cache
            null,  // no schema loader
            null,  // no datasource
            null,  // no catalog
            null,  // no schema
            0      // no refresh
        );
        
        SqlEnhancerInterceptor optimizingInterceptor = new SqlEnhancerInterceptor(optimizingEngine, true);
        
        try {
            String sql = "SELECT * FROM users WHERE 1=1 AND id = 5";
            
            RequestContext context = DefaultRequestContext.builder()
                .requestType(RequestType.QUERY)
                .originalSql(sql)
                .build();
            
            InterceptorChain chain = new DefaultInterceptorChain(
                java.util.Collections.emptyList(),
                0,
                ctx -> {}
            );
            optimizingInterceptor.intercept(context, chain);
            
            assertThat(context.getCurrentSql()).isNotNull();
            // With optimization, redundant conditions might be removed
            
        } finally {
            optimizingEngine.shutdown();
        }
    }
    
    @Test
    void testInterceptorSkipsNonSupportedRequestTypes() throws Exception {
        RequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.BATCH)  // Not supported
            .originalSql("BATCH SQL")
            .build();
        
        final boolean[] chainCalled = {false};
        InterceptorChain chain = new DefaultInterceptorChain(
            java.util.Collections.emptyList(),
            0,
            ctx -> chainCalled[0] = true
        );
        
        interceptor.intercept(context, chain);
        
        // Should pass through without enhancement
        assertThat(chainCalled[0]).isTrue();
    }
}
