package org.openjproxy.interceptor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DefaultInterceptorChain}.
 */
class DefaultInterceptorChainTest {
    
    @Test
    void testEmptyChain() throws Exception {
        List<RequestInterceptor> interceptors = new ArrayList<>();
        boolean[] executed = {false};
        
        DefaultInterceptorChain.CoreLogic coreLogic = context -> executed[0] = true;
        
        DefaultRequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .build();
        
        DefaultInterceptorChain chain = new DefaultInterceptorChain(interceptors, 0, coreLogic);
        chain.proceed(context);
        
        assertThat(executed[0]).isTrue();
    }
    
    @Test
    void testSingleInterceptor() throws Exception {
        List<String> executionOrder = new ArrayList<>();
        
        List<RequestInterceptor> interceptors = new ArrayList<>();
        interceptors.add(new OrderTrackingInterceptor("interceptor-1", executionOrder));
        
        DefaultInterceptorChain.CoreLogic coreLogic = context -> executionOrder.add("core");
        
        DefaultRequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .build();
        
        DefaultInterceptorChain chain = new DefaultInterceptorChain(interceptors, 0, coreLogic);
        chain.proceed(context);
        
        assertThat(executionOrder).containsExactly(
            "interceptor-1-before",
            "core",
            "interceptor-1-after"
        );
    }
    
    @Test
    void testMultipleInterceptors() throws Exception {
        List<String> executionOrder = new ArrayList<>();
        
        List<RequestInterceptor> interceptors = new ArrayList<>();
        interceptors.add(new OrderTrackingInterceptor("interceptor-1", executionOrder));
        interceptors.add(new OrderTrackingInterceptor("interceptor-2", executionOrder));
        interceptors.add(new OrderTrackingInterceptor("interceptor-3", executionOrder));
        
        DefaultInterceptorChain.CoreLogic coreLogic = context -> executionOrder.add("core");
        
        DefaultRequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .build();
        
        DefaultInterceptorChain chain = new DefaultInterceptorChain(interceptors, 0, coreLogic);
        chain.proceed(context);
        
        assertThat(executionOrder).containsExactly(
            "interceptor-1-before",
            "interceptor-2-before",
            "interceptor-3-before",
            "core",
            "interceptor-3-after",
            "interceptor-2-after",
            "interceptor-1-after"
        );
    }
    
    @Test
    void testShortCircuit() throws Exception {
        List<String> executionOrder = new ArrayList<>();
        
        List<RequestInterceptor> interceptors = new ArrayList<>();
        interceptors.add(new OrderTrackingInterceptor("interceptor-1", executionOrder));
        interceptors.add(new ShortCircuitInterceptor("short-circuit", executionOrder));
        interceptors.add(new OrderTrackingInterceptor("interceptor-3", executionOrder));
        
        DefaultInterceptorChain.CoreLogic coreLogic = context -> executionOrder.add("core");
        
        DefaultRequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .build();
        
        DefaultInterceptorChain chain = new DefaultInterceptorChain(interceptors, 0, coreLogic);
        chain.proceed(context);
        
        // Should not reach interceptor-3 or core after short-circuit
        assertThat(executionOrder).containsExactly(
            "interceptor-1-before",
            "short-circuit",
            "interceptor-1-after"
        );
    }
    
    @Test
    void testExceptionPropagation() {
        List<RequestInterceptor> interceptors = new ArrayList<>();
        interceptors.add(new ExceptionThrowingInterceptor());
        
        DefaultRequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .build();
        
        DefaultInterceptorChain chain = new DefaultInterceptorChain(interceptors, 0, null);
        
        assertThatThrownBy(() -> chain.proceed(context))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("Test exception");
    }
    
    @Test
    void testHasNext() {
        List<RequestInterceptor> interceptors = new ArrayList<>();
        interceptors.add(new NoOpInterceptor("test-1"));
        interceptors.add(new NoOpInterceptor("test-2"));
        
        DefaultInterceptorChain chain = new DefaultInterceptorChain(interceptors, 0, null);
        assertThat(chain.hasNext()).isTrue();
        assertThat(chain.getCurrentIndex()).isEqualTo(0);
        assertThat(chain.getTotalCount()).isEqualTo(2);
        
        DefaultInterceptorChain chain2 = new DefaultInterceptorChain(interceptors, 2, null);
        assertThat(chain2.hasNext()).isFalse();
    }
    
    /**
     * Interceptor that tracks execution order.
     */
    static class OrderTrackingInterceptor implements RequestInterceptor {
        private final String id;
        private final List<String> executionOrder;
        
        OrderTrackingInterceptor(String id, List<String> executionOrder) {
            this.id = id;
            this.executionOrder = executionOrder;
        }
        
        @Override
        public String id() {
            return id;
        }
        
        @Override
        public void intercept(RequestContext context, InterceptorChain chain) throws Exception {
            executionOrder.add(id + "-before");
            try {
                chain.proceed(context);
            } finally {
                executionOrder.add(id + "-after");
            }
        }
    }
    
    /**
     * Interceptor that short-circuits the chain.
     */
    static class ShortCircuitInterceptor implements RequestInterceptor {
        private final String id;
        private final List<String> executionOrder;
        
        ShortCircuitInterceptor(String id, List<String> executionOrder) {
            this.id = id;
            this.executionOrder = executionOrder;
        }
        
        @Override
        public String id() {
            return id;
        }
        
        @Override
        public void intercept(RequestContext context, InterceptorChain chain) {
            executionOrder.add(id);
            context.setShortCircuited(true);
            // Don't call chain.proceed()
        }
    }
    
    /**
     * Interceptor that throws an exception.
     */
    static class ExceptionThrowingInterceptor implements RequestInterceptor {
        @Override
        public String id() {
            return "exception-thrower";
        }
        
        @Override
        public void intercept(RequestContext context, InterceptorChain chain) throws Exception {
            throw new RuntimeException("Test exception");
        }
    }
    
    /**
     * Simple no-op interceptor.
     */
    static class NoOpInterceptor implements RequestInterceptor {
        private final String id;
        
        NoOpInterceptor(String id) {
            this.id = id;
        }
        
        @Override
        public String id() {
            return id;
        }
        
        @Override
        public void intercept(RequestContext context, InterceptorChain chain) throws Exception {
            chain.proceed(context);
        }
    }
}
