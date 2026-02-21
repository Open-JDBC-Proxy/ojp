package org.openjproxy.grpc.server.interceptor;

import org.openjproxy.interceptor.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Executes the interceptor chain for a request through all lifecycle phases.
 * 
 * <p>This class coordinates the execution of interceptors across the different
 * phases of request processing, handling phase transitions and exception propagation.</p>
 */
public class InterceptorChainExecutor {
    
    private static final Logger log = LoggerFactory.getLogger(InterceptorChainExecutor.class);
    
    private InterceptorChainExecutor() {
        // Utility class
    }
    
    /**
     * Executes a request through the full interceptor chain.
     * 
     * @param context the request context
     * @param coreLogic the core business logic to execute
     * @throws Exception if an error occurs during processing
     */
    public static void execute(RequestContext context, CoreLogic coreLogic) throws Exception {
        try {
            // Execute PRE_REQUEST phase
            executePhase(context, LifecyclePhase.PRE_REQUEST);
            
            if (!context.isShortCircuited()) {
                try {
                    // Execute PRE_EXECUTION phase
                    executePhase(context, LifecyclePhase.PRE_EXECUTION);
                    
                    if (!context.isShortCircuited()) {
                        // Execute RESOURCE_ACQUISITION phase
                        executePhase(context, LifecyclePhase.RESOURCE_ACQUISITION);
                        
                        if (!context.isShortCircuited()) {
                            // Execute EXECUTION phase with core logic
                            context.setCurrentPhase(LifecyclePhase.EXECUTION);
                            List<RequestInterceptor> executionInterceptors = 
                                RequestInterceptorRegistry.getInterceptors(
                                    context.getRequestType(), 
                                    LifecyclePhase.EXECUTION
                                );
                            
                            DefaultInterceptorChain chain = new DefaultInterceptorChain(
                                executionInterceptors,
                                0,
                                coreLogic::execute
                            );
                            chain.proceed(context);
                            
                            // Execute POST_EXECUTION phase
                            if (!context.isShortCircuited()) {
                                executePhase(context, LifecyclePhase.POST_EXECUTION);
                            }
                        }
                        
                        // Execute RESOURCE_RELEASE phase (always, even if short-circuited)
                        executePhase(context, LifecyclePhase.RESOURCE_RELEASE);
                    }
                    
                } catch (Exception e) {
                    // Execute EXCEPTION_HANDLING phase
                    context.setException(e);
                    executePhase(context, LifecyclePhase.EXCEPTION_HANDLING);
                    
                    // Re-throw the exception if it wasn't handled/transformed
                    if (context.getException().isPresent()) {
                        throw context.getException().get();
                    }
                    
                } finally {
                    // Execute POST_REQUEST phase (always runs)
                    context.setEndTimeMillis(System.currentTimeMillis());
                    executePhase(context, LifecyclePhase.POST_REQUEST);
                }
            }
        } catch (Exception e) {
            // Log and re-throw
            log.error("Error executing interceptor chain for request type {}: {}", 
                    context.getRequestType(), e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * Executes interceptors for a specific phase.
     * 
     * @param context the request context
     * @param phase the lifecycle phase
     * @throws Exception if an error occurs
     */
    private static void executePhase(RequestContext context, LifecyclePhase phase) throws Exception {
        if (context.isShortCircuited() && 
            phase != LifecyclePhase.RESOURCE_RELEASE && 
            phase != LifecyclePhase.POST_REQUEST) {
            // Skip this phase if short-circuited, except for cleanup phases
            return;
        }
        
        context.setCurrentPhase(phase);
        List<RequestInterceptor> interceptors = RequestInterceptorRegistry.getInterceptors(
            context.getRequestType(),
            phase
        );
        
        if (!interceptors.isEmpty()) {
            log.debug("Executing {} interceptors for phase {} in request type {}", 
                    interceptors.size(), phase, context.getRequestType());
            
            DefaultInterceptorChain chain = new DefaultInterceptorChain(
                interceptors,
                0,
                null // No core logic for non-execution phases
            );
            chain.proceed(context);
        }
    }
    
    /**
     * Functional interface for core business logic.
     */
    @FunctionalInterface
    public interface CoreLogic {
        /**
         * Executes the core business logic.
         * 
         * @param context the request context
         * @throws Exception if an error occurs
         */
        void execute(RequestContext context) throws Exception;
    }
}
