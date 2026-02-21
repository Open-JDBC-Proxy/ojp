package org.openjproxy.interceptor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DefaultRequestContext}.
 */
class DefaultRequestContextTest {
    
    @Test
    void testBuilder() {
        DefaultRequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .originalSql("SELECT * FROM users")
            .sqlHash("abc123")
            .connectionHash("conn-1")
            .build();
        
        assertThat(context.getRequestType()).isEqualTo(RequestType.QUERY);
        assertThat(context.getOriginalSql()).isEqualTo("SELECT * FROM users");
        assertThat(context.getCurrentSql()).isEqualTo("SELECT * FROM users");
        assertThat(context.getSqlHash()).isEqualTo("abc123");
        assertThat(context.getConnectionHash()).isEqualTo("conn-1");
        assertThat(context.getCurrentPhase()).isEqualTo(LifecyclePhase.PRE_REQUEST);
        assertThat(context.isShortCircuited()).isFalse();
    }
    
    @Test
    void testSetCurrentSql() {
        DefaultRequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .originalSql("SELECT * FROM users")
            .build();
        
        context.setCurrentSql("SELECT id, name FROM users");
        
        assertThat(context.getOriginalSql()).isEqualTo("SELECT * FROM users");
        assertThat(context.getCurrentSql()).isEqualTo("SELECT id, name FROM users");
    }
    
    @Test
    void testAttributes() {
        DefaultRequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .build();
        
        context.setAttribute("key1", "value1");
        context.setAttribute("key2", 42);
        
        assertThat(context.getAttribute("key1")).isEqualTo("value1");
        assertThat(context.getAttribute("key2")).isEqualTo(42);
        assertThat(context.getAttribute("nonexistent")).isNull();
    }
    
    @Test
    void testPhaseTransition() {
        DefaultRequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .build();
        
        assertThat(context.getCurrentPhase()).isEqualTo(LifecyclePhase.PRE_REQUEST);
        
        context.setCurrentPhase(LifecyclePhase.PRE_EXECUTION);
        assertThat(context.getCurrentPhase()).isEqualTo(LifecyclePhase.PRE_EXECUTION);
        
        context.setCurrentPhase(LifecyclePhase.EXECUTION);
        assertThat(context.getCurrentPhase()).isEqualTo(LifecyclePhase.EXECUTION);
    }
    
    @Test
    void testShortCircuit() {
        DefaultRequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .build();
        
        assertThat(context.isShortCircuited()).isFalse();
        
        context.setShortCircuited(true);
        assertThat(context.isShortCircuited()).isTrue();
    }
    
    @Test
    void testExceptionHandling() {
        DefaultRequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .build();
        
        assertThat(context.getException()).isEmpty();
        
        Exception ex = new RuntimeException("Test error");
        context.setException(ex);
        
        assertThat(context.getException()).isPresent();
        assertThat(context.getException().get()).isSameAs(ex);
    }
    
    @Test
    void testTiming() {
        long startTime = System.currentTimeMillis();
        DefaultRequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.QUERY)
            .startTimeMillis(startTime)
            .build();
        
        assertThat(context.getStartTimeMillis()).isEqualTo(startTime);
        assertThat(context.getEndTimeMillis()).isEmpty();
        
        long endTime = startTime + 100;
        context.setEndTimeMillis(endTime);
        
        assertThat(context.getEndTimeMillis()).isPresent();
        assertThat(context.getEndTimeMillis().get()).isEqualTo(endTime);
    }
    
    @Test
    void testResultHandling() {
        DefaultRequestContext context = DefaultRequestContext.builder()
            .requestType(RequestType.UPDATE)
            .build();
        
        assertThat(context.getResult()).isEmpty();
        
        Integer rowsAffected = 5;
        context.setResult(rowsAffected);
        
        assertThat(context.getResult()).isPresent();
        assertThat(context.getResult().get()).isEqualTo(5);
    }
}
