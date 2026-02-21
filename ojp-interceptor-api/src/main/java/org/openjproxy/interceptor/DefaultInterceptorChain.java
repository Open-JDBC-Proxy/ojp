package org.openjproxy.interceptor;

import java.util.List;

/**
 * Default implementation of {@link InterceptorChain}.
 * 
 * <p>This class manages the progression through a list of interceptors.</p>
 */
public class DefaultInterceptorChain implements InterceptorChain {
    
    private final List<RequestInterceptor> interceptors;
    private final int currentIndex;
    private final CoreLogic coreLogic;
    
    /**
     * Creates a new interceptor chain.
     * 
     * @param interceptors the list of interceptors to execute
     * @param currentIndex the current index in the chain
     * @param coreLogic the core business logic to execute after all interceptors
     */
    public DefaultInterceptorChain(List<RequestInterceptor> interceptors, int currentIndex, CoreLogic coreLogic) {
        this.interceptors = interceptors;
        this.currentIndex = currentIndex;
        this.coreLogic = coreLogic;
    }
    
    @Override
    public void proceed(RequestContext context) throws Exception {
        // Check if short-circuited
        if (context.isShortCircuited()) {
            return;
        }
        
        // If we've reached the end of interceptors, execute core logic
        if (currentIndex >= interceptors.size()) {
            if (coreLogic != null) {
                coreLogic.execute(context);
            }
            return;
        }
        
        // Get the next interceptor and invoke it
        RequestInterceptor interceptor = interceptors.get(currentIndex);
        DefaultInterceptorChain nextChain = new DefaultInterceptorChain(
            interceptors, 
            currentIndex + 1, 
            coreLogic
        );
        interceptor.intercept(context, nextChain);
    }
    
    @Override
    public boolean hasNext() {
        return currentIndex < interceptors.size();
    }
    
    @Override
    public int getCurrentIndex() {
        return currentIndex;
    }
    
    @Override
    public int getTotalCount() {
        return interceptors.size();
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
