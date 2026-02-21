package org.openjproxy.interceptor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RequestInterceptorRegistry}.
 */
class RequestInterceptorRegistryTest {
    
    @BeforeEach
    void setUp() {
        RequestInterceptorRegistry.clear();
    }
    
    @AfterEach
    void tearDown() {
        RequestInterceptorRegistry.clear();
    }
    
    @Test
    void testInitialize() {
        RequestInterceptorRegistry.initialize();
        assertThat(RequestInterceptorRegistry.getCount()).isGreaterThanOrEqualTo(0);
    }
    
    @Test
    void testRegisterInterceptor() {
        TestInterceptor interceptor = new TestInterceptor("test-1", 100);
        RequestInterceptorRegistry.registerInterceptor(interceptor);
        
        assertThat(RequestInterceptorRegistry.getCount()).isEqualTo(1);
        assertThat(RequestInterceptorRegistry.getInterceptor("test-1")).isPresent();
    }
    
    @Test
    void testGetInterceptorsSortedByPriority() {
        RequestInterceptorRegistry.registerInterceptor(new TestInterceptor("low", 10));
        RequestInterceptorRegistry.registerInterceptor(new TestInterceptor("high", 100));
        RequestInterceptorRegistry.registerInterceptor(new TestInterceptor("medium", 50));
        
        List<RequestInterceptor> interceptors = RequestInterceptorRegistry.getInterceptors();
        
        assertThat(interceptors).hasSize(3);
        assertThat(interceptors.get(0).id()).isEqualTo("high");
        assertThat(interceptors.get(1).id()).isEqualTo("medium");
        assertThat(interceptors.get(2).id()).isEqualTo("low");
    }
    
    @Test
    void testFilterByRequestType() {
        RequestInterceptorRegistry.registerInterceptor(
            new TestInterceptor("query-only", 100, RequestType.QUERY, null));
        RequestInterceptorRegistry.registerInterceptor(
            new TestInterceptor("update-only", 100, RequestType.UPDATE, null));
        RequestInterceptorRegistry.registerInterceptor(
            new TestInterceptor("all-types", 100, null, null));
        
        List<RequestInterceptor> queryInterceptors = 
            RequestInterceptorRegistry.getInterceptors(RequestType.QUERY, LifecyclePhase.PRE_REQUEST);
        
        assertThat(queryInterceptors).hasSize(2);
        assertThat(queryInterceptors).extracting("id")
            .containsExactlyInAnyOrder("query-only", "all-types");
    }
    
    @Test
    void testFilterByPhase() {
        RequestInterceptorRegistry.registerInterceptor(
            new TestInterceptor("pre-exec-only", 100, null, LifecyclePhase.PRE_EXECUTION));
        RequestInterceptorRegistry.registerInterceptor(
            new TestInterceptor("post-exec-only", 100, null, LifecyclePhase.POST_EXECUTION));
        RequestInterceptorRegistry.registerInterceptor(
            new TestInterceptor("all-phases", 100, null, null));
        
        List<RequestInterceptor> preExecInterceptors = 
            RequestInterceptorRegistry.getInterceptors(RequestType.QUERY, LifecyclePhase.PRE_EXECUTION);
        
        assertThat(preExecInterceptors).hasSize(2);
        assertThat(preExecInterceptors).extracting("id")
            .containsExactlyInAnyOrder("pre-exec-only", "all-phases");
    }
    
    @Test
    void testClear() {
        RequestInterceptorRegistry.registerInterceptor(new TestInterceptor("test", 100));
        assertThat(RequestInterceptorRegistry.getCount()).isEqualTo(1);
        
        RequestInterceptorRegistry.clear();
        assertThat(RequestInterceptorRegistry.getCount()).isEqualTo(0);
    }
    
    /**
     * Test interceptor implementation.
     */
    static class TestInterceptor implements RequestInterceptor {
        private final String id;
        private final int priority;
        private final RequestType supportedType;
        private final LifecyclePhase supportedPhase;
        
        TestInterceptor(String id, int priority) {
            this(id, priority, null, null);
        }
        
        TestInterceptor(String id, int priority, RequestType supportedType, LifecyclePhase supportedPhase) {
            this.id = id;
            this.priority = priority;
            this.supportedType = supportedType;
            this.supportedPhase = supportedPhase;
        }
        
        @Override
        public String id() {
            return id;
        }
        
        @Override
        public int getPriority() {
            return priority;
        }
        
        @Override
        public boolean supportsRequestType(RequestType requestType) {
            return supportedType == null || supportedType == requestType;
        }
        
        @Override
        public boolean supportsPhase(LifecyclePhase phase) {
            return supportedPhase == null || supportedPhase == phase;
        }
        
        @Override
        public void intercept(RequestContext context, InterceptorChain chain) throws Exception {
            chain.proceed(context);
        }
    }
}
