package org.openjproxy.config;

/**
 * Exception thrown when TLS/mTLS configuration fails.
 * 
 * <p>This exception is thrown when there are errors loading keystores,
 * truststores, or creating SSL contexts for secure communication.</p>
 */
public class TlsConfigurationException extends Exception {
    
    /**
     * Constructs a new TLS configuration exception with the specified detail message.
     * 
     * @param message The detail message
     */
    public TlsConfigurationException(String message) {
        super(message);
    }
    
    /**
     * Constructs a new TLS configuration exception with the specified detail message and cause.
     * 
     * @param message The detail message
     * @param cause The cause of the exception
     */
    public TlsConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
    
    /**
     * Constructs a new TLS configuration exception with the specified cause.
     * 
     * @param cause The cause of the exception
     */
    public TlsConfigurationException(Throwable cause) {
        super(cause);
    }
}