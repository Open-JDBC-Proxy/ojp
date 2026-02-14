package org.openjproxy.config;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Configuration class for gRPC client settings.
 * <p>
 * Loads values such as maximum inbound message sizes and TLS configuration
 * from a properties file named {@code ojp.properties} located in the classpath,
 * or from system properties.
 * </p>
 * <p>
 * If properties are missing, default values are applied.
 * </p>
 */
public class GrpcClientConfig {
    /** Default message size in bytes (16MB) */
    private static final String DEFAULT_SIZE = "16777216";

    private int maxInboundMessageSize;
    private TlsConfig tlsConfig;

    /**
     * Constructs a new {@code GrpcClientConfig} using the provided {@link Properties}.
     * <p>
     * If expected keys are missing, default values are used.
     * System properties take precedence over properties file values.
     * </p>
     *
     * @param props the {@link Properties} object containing configuration values
     */
    public GrpcClientConfig(Properties props) {
        this.maxInboundMessageSize = Integer.parseInt(
                getProperty("ojp.grpc.maxInboundMessageSize", props, DEFAULT_SIZE));
        
        // Load TLS configuration from system properties or properties file
        boolean tlsEnabled = Boolean.parseBoolean(
                getProperty("ojp.client.tls.enabled", props, "false"));
        String keystorePath = getProperty("ojp.client.tls.keystore.path", props, null);
        String keystorePassword = getProperty("ojp.client.tls.keystore.password", props, null);
        String truststorePath = getProperty("ojp.client.tls.truststore.path", props, null);
        String truststorePassword = getProperty("ojp.client.tls.truststore.password", props, null);
        String keystoreType = getProperty("ojp.client.tls.keystore.type", props, TlsConfig.DEFAULT_KEYSTORE_TYPE);
        String truststoreType = getProperty("ojp.client.tls.truststore.type", props, TlsConfig.DEFAULT_TRUSTSTORE_TYPE);
        
        this.tlsConfig = new TlsConfig(tlsEnabled, keystorePath, keystorePassword,
                truststorePath, truststorePassword, keystoreType, truststoreType);
    }
    
    /**
     * Gets a property value, checking system properties first, then the properties file.
     * 
     * @param key The property key
     * @param props The properties file
     * @param defaultValue The default value if not found
     * @return The property value (trimmed)
     */
    private String getProperty(String key, Properties props, String defaultValue) {
        // System properties take precedence
        String value = System.getProperty(key);
        if (value != null) {
            return value.trim();
        }
        
        // Fall back to properties file
        value = props.getProperty(key, defaultValue);
        return value != null ? value.trim() : null;
    }

    /**
     * Returns the maximum allowed inbound message size (in bytes).
     *
     * @return the max inbound message size
     */
    public int getMaxInboundMessageSize() {
        return this.maxInboundMessageSize;
    }
    
    /**
     * Returns the TLS configuration.
     *
     * @return the TLS configuration
     */
    public TlsConfig getTlsConfig() {
        return this.tlsConfig;
    }

    /**
     * Loads the gRPC client configuration from a {@code ojp.properties} file
     * located in the classpath.
     *
     * @return a new instance of {@code GrpcClientConfig} with loaded values
     * @throws IOException if the file is not found or cannot be read
     */
    public static GrpcClientConfig load() throws IOException {
        try (InputStream in = GrpcClientConfig.class.getClassLoader().getResourceAsStream("ojp.properties")) {
            if (in == null) {
                throw new FileNotFoundException("Could not find ojp.properties in classpath");
            }
            Properties props = new Properties();
            props.load(in);
            return new GrpcClientConfig(props);
        }
    }
}
