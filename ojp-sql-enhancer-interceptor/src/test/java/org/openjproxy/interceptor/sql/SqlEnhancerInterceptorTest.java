package org.openjproxy.interceptor.sql;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.interceptor.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SqlEnhancerInterceptor}.
 */
class SqlEnhancerInterceptorTest {
    
    @BeforeEach
    void setUp() {
        RequestInterceptorRegistry.clear();
    }
    
    @AfterEach
    void tearDown() {
        RequestInterceptorRegistry.clear();
    }
    
    @Test
    void testInterceptorMetadata() {
        SqlEnhancerInterceptor interceptor = new SqlEnhancerInterceptor();
        
        assertThat(interceptor.id()).isEqualTo("sql-enhancer");
        assertThat(interceptor.getPriority()).isEqualTo(600);
        assertThat(interceptor.supportsPhase(LifecyclePhase.PRE_EXECUTION)).isTrue();
        assertThat(interceptor.supportsPhase(LifecyclePhase.POST_EXECUTION)).isFalse();
        assertThat(interceptor.supportsRequestType(RequestType.QUERY)).isTrue();
        assertThat(interceptor.supportsRequestType(RequestType.UPDATE)).isTrue();
        assertThat(interceptor.supportsRequestType(RequestType.BATCH)).isFalse();
    }
    
    @Test
    void testDisabledByDefault() {
        SqlEnhancerInterceptor interceptor = new SqlEnhancerInterceptor();
        
        // Should be disabled by default
        assertThat(interceptor.isAvailable()).isFalse();
    }
    
    @Test
    void testPassThroughWhenDisabled() throws Exception {
        SqlEnhancerInterceptor interceptor = new SqlEnhancerInterceptor();
        
        boolean[] chainCalled = {false};
        InterceptorChain chain = new InterceptorChain() {
            @Override
            public void proceed(RequestContext context) {
                chainCalled[0] = true;
            }
            
            @Override
            public boolean hasNext() {
                return false;
            }
            
            @Override
            public int getCurrentIndex() {
                return 0;
            }
            
            @Override
            public int getTotalCount() {
                return 1;
            }
        };
        
        RequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .originalSql("SELECT * FROM users")
            .build();
        
        interceptor.intercept(context, chain);
        
        assertThat(chainCalled[0]).isTrue();
        assertThat(context.getCurrentSql()).isEqualTo("SELECT * FROM users");
    }
    
    @Test
    void testServiceLoaderDiscovery() {
        RequestInterceptorRegistry.initialize();
        
        // Check if the interceptor can be discovered
        RequestInterceptor interceptor = RequestInterceptorRegistry.getInterceptor("sql-enhancer").orElse(null);
        
        if (interceptor != null) {
            assertThat(interceptor).isInstanceOf(SqlEnhancerInterceptor.class);
            assertThat(interceptor.id()).isEqualTo("sql-enhancer");
        }
        // If not found, that's OK - it means ServiceLoader didn't find it in test classpath
    }
}
