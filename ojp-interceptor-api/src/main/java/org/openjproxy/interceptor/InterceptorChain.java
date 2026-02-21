package org.openjproxy.interceptor;

/**
 * Represents the chain of interceptors to be executed.
 * 
 * <p>Interceptors must call {@code proceed(context)} to continue the chain.
 * If they don't call proceed, the chain is short-circuited.</p>
 */
public interface InterceptorChain {
    
    /**
     * Proceeds to the next interceptor in the chain.
     * 
     * <p>If this is the last interceptor, proceeds to actual request execution.
     * If the context is marked as short-circuited, returns immediately.</p>
     * 
     * @param context the request context
     * @throws Exception if an error occurs during processing
     */
    void proceed(RequestContext context) throws Exception;
    
    /**
     * Returns whether there are more interceptors in the chain.
     * 
     * @return true if there are more interceptors, false otherwise
     */
    boolean hasNext();
    
    /**
     * Returns the index of the current interceptor.
     * 
     * @return the current index (0-based)
     */
    int getCurrentIndex();
    
    /**
     * Returns the total number of interceptors in the chain.
     * 
     * @return the total count
     */
    int getTotalCount();
}
