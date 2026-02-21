package org.openjproxy.grpc.server.interceptor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjproxy.interceptor.*;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link InterceptorChainExecutor}.
 */
class InterceptorChainExecutorTest {
    
    @BeforeEach
    void setUp() {
        RequestInterceptorRegistry.clear();
    }
    
    @AfterEach
    void tearDown() {
        RequestInterceptorRegistry.clear();
    }
    
    @Test
    void testExecuteWithNoInterceptors() throws Exception {
        boolean[] executed = {false};
        
        RequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .originalSql("SELECT * FROM users")
            .build();
        
        InterceptorChainExecutor.execute(context, ctx -> executed[0] = true);
        
        assertThat(executed[0]).isTrue();
    }
    
    @Test
    void testExecuteWithSingleInterceptor() throws Exception {
        List<String> executionLog = new ArrayList<>();
        
        RequestInterceptorRegistry.registerInterceptor(
            new LoggingInterceptor("test", 100, executionLog)
        );
        
        RequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .originalSql("SELECT * FROM users")
            .build();
        
        InterceptorChainExecutor.execute(context, ctx -> executionLog.add("CORE"));
        
        assertThat(executionLog).containsExactly(
            "test:PRE_REQUEST:before",
            "test:PRE_REQUEST:after",
            "test:PRE_EXECUTION:before",
            "test:PRE_EXECUTION:after",
            "test:RESOURCE_ACQUISITION:before",
            "test:RESOURCE_ACQUISITION:after",
            "test:EXECUTION:before",
            "CORE",
            "test:EXECUTION:after",
            "test:POST_EXECUTION:before",
            "test:POST_EXECUTION:after",
            "test:RESOURCE_RELEASE:before",
            "test:RESOURCE_RELEASE:after",
            "test:POST_REQUEST:before",
            "test:POST_REQUEST:after"
        );
    }
    
    @Test
    void testExecuteWithMultipleInterceptors() throws Exception {
        List<String> executionLog = new ArrayList<>();
        
        RequestInterceptorRegistry.registerInterceptor(
            new LoggingInterceptor("high", 100, executionLog)
        );
        RequestInterceptorRegistry.registerInterceptor(
            new LoggingInterceptor("low", 10, executionLog)
        );
        
        RequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .originalSql("SELECT * FROM users")
            .build();
        
        InterceptorChainExecutor.execute(context, ctx -> executionLog.add("CORE"));
        
        // High priority interceptor should execute first
        assertThat(executionLog.get(0)).isEqualTo("high:PRE_REQUEST:before");
        assertThat(executionLog.get(1)).isEqualTo("low:PRE_REQUEST:before");
    }
    
    @Test
    void testPhaseTransitions() throws Exception {
        List<LifecyclePhase> phaseLog = new ArrayList<>();
        
        RequestInterceptorRegistry.registerInterceptor(new RequestInterceptor() {
            @Override
            public String id() {
                return "phase-logger";
            }
            
            @Override
            public void intercept(RequestContext context, InterceptorChain chain) throws Exception {
                phaseLog.add(context.getCurrentPhase());
                chain.proceed(context);
            }
        });
        
        RequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .originalSql("SELECT * FROM users")
            .build();
        
        InterceptorChainExecutor.execute(context, ctx -> {});
        
        assertThat(phaseLog).containsExactly(
            LifecyclePhase.PRE_REQUEST,
            LifecyclePhase.PRE_EXECUTION,
            LifecyclePhase.RESOURCE_ACQUISITION,
            LifecyclePhase.EXECUTION,
            LifecyclePhase.POST_EXECUTION,
            LifecyclePhase.RESOURCE_RELEASE,
            LifecyclePhase.POST_REQUEST
        );
    }
    
    @Test
    void testExceptionHandling() throws Exception {
        List<String> executionLog = new ArrayList<>();
        
        RequestInterceptorRegistry.registerInterceptor(new RequestInterceptor() {
            @Override
            public String id() {
                return "exception-handler";
            }
            
            @Override
            public boolean supportsPhase(LifecyclePhase phase) {
                return phase == LifecyclePhase.EXCEPTION_HANDLING;
            }
            
            @Override
            public void intercept(RequestContext context, InterceptorChain chain) throws Exception {
                executionLog.add("EXCEPTION_HANDLER");
                chain.proceed(context);
            }
        });
        
        RequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .originalSql("SELECT * FROM users")
            .build();
        
        assertThatThrownBy(() -> {
            InterceptorChainExecutor.execute(context, ctx -> {
                throw new RuntimeException("Test error");
            });
        }).isInstanceOf(RuntimeException.class).hasMessage("Test error");
        
        assertThat(executionLog).contains("EXCEPTION_HANDLER");
    }
    
    /**
     * Test interceptor that logs execution.
     */
    static class LoggingInterceptor implements RequestInterceptor {
        private final String id;
        private final int priority;
        private final List<String> log;
        
        LoggingInterceptor(String id, int priority, List<String> log) {
            this.id = id;
            this.priority = priority;
            this.log = log;
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
        public void intercept(RequestContext context, InterceptorChain chain) throws Exception {
            log.add(id + ":" + context.getCurrentPhase() + ":before");
            try {
                chain.proceed(context);
            } finally {
                log.add(id + ":" + context.getCurrentPhase() + ":after");
            }
        }
    }
}
