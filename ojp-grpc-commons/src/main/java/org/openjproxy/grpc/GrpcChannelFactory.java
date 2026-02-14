package org.openjproxy.grpc;

import org.openjproxy.config.GrpcClientConfig;
import org.openjproxy.config.TlsConfig;
import org.openjproxy.config.TlsConfigurationException;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
/**
 * Factory class for creating and configuring gRPC {@link ManagedChannel} instances.
 * 
 * <p>This class reads configuration from {@link GrpcClientConfig} and provides overloaded methods
 * to create channels with default or custom settings. It supports both plaintext and TLS/mTLS connections.</p>
 * 
 * <p>By default, it uses a maximum inbound message size of 16MB.</p>
 */
public class GrpcChannelFactory {
    private static final Logger logger = LoggerFactory.getLogger(GrpcChannelFactory.class);
    
    /** Default maximum inbound message size (16MB) */
    private static int maxInboundMessageSize = 16777216;

    /** gRPC client configuration loaded from properties file */
    static GrpcClientConfig grpcConfig;

    /**
     * Constructor that initializes gRPC client configuration.
     */
    public GrpcChannelFactory() {
        initializeGrpcConfig();
    }

    /**
     * Initializes the gRPC client configuration from external properties.
     * 
     * <p>If loading fails, it falls back to an empty configuration.</p>
     */
    public static void initializeGrpcConfig() {
        try {
            grpcConfig = GrpcClientConfig.load();
        } catch (IOException e) {
            logger.warn("Could not load ojp.properties, using defaults: {}", e.getMessage());
            grpcConfig = new GrpcClientConfig(new java.util.Properties());
        }

        maxInboundMessageSize = grpcConfig.getMaxInboundMessageSize();
    }

    /**
     * Creates a new {@link ManagedChannel} for the given host and port with specified
     * inbound message size limits.
     *
     * @param host The gRPC server host
     * @param port The gRPC server port
     * @param maxInboundSize Maximum allowed inbound message size in bytes
     * @return A configured {@link ManagedChannel} instance
     */
    public static ManagedChannel createChannel(String host, int port, int maxInboundSize) {
        if (grpcConfig == null) {
            initializeGrpcConfig();
        }
        
        TlsConfig tlsConfig = grpcConfig.getTlsConfig();
        
        if (tlsConfig.isEnabled()) {
            return createSecureChannel(host, port, maxInboundSize, tlsConfig);
        } else {
            return ManagedChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .maxInboundMessageSize(maxInboundSize)
                    .build();
        }
    }

    /**
     * Creates a new {@link ManagedChannel} for the given host and port using default message size limits.
     *
     * @param host The gRPC server host
     * @param port The gRPC server port
     * @return A configured {@link ManagedChannel} instance
     */
    public static ManagedChannel createChannel(String host, int port) {
        return createChannel(host, port, maxInboundMessageSize);
    }

    /**
     * Creates a new {@link ManagedChannel} for the given target string (e.g., "dns:///localhost:50051").
     * Uses default message size limits.
     *
     * @param target A target string in the form "dns:///host:port" or "host:port"
     * @return A configured {@link ManagedChannel} instance
     */
    public static ManagedChannel createChannel(String target) {
        if (grpcConfig == null) {
            initializeGrpcConfig();
        }
        
        TlsConfig tlsConfig = grpcConfig.getTlsConfig();
        
        if (tlsConfig.isEnabled()) {
            return createSecureChannel(target, maxInboundMessageSize, tlsConfig);
        } else {
            return ManagedChannelBuilder.forTarget(target)
                    .usePlaintext()
                    .maxInboundMessageSize(maxInboundMessageSize)
                    .build();
        }
    }
    
    /**
     * Creates a secure channel with TLS/mTLS enabled.
     * 
     * @param host The gRPC server host
     * @param port The gRPC server port
     * @param maxInboundSize Maximum allowed inbound message size in bytes
     * @param tlsConfig TLS configuration
     * @return A configured secure {@link ManagedChannel} instance
     */
    private static ManagedChannel createSecureChannel(String host, int port, int maxInboundSize, TlsConfig tlsConfig) {
        try {
            SslContext sslContext = buildSslContext(tlsConfig);
            
            return NettyChannelBuilder.forAddress(host, port)
                    .sslContext(sslContext)
                    .maxInboundMessageSize(maxInboundSize)
                    .build();
        } catch (Exception e) {
            logger.error("Failed to create secure gRPC channel: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create secure gRPC channel", e);
        }
    }
    
    /**
     * Creates a secure channel with TLS/mTLS enabled for the given target.
     * 
     * @param target A target string in the form "dns:///host:port" or "host:port"
     * @param maxInboundSize Maximum allowed inbound message size in bytes
     * @param tlsConfig TLS configuration
     * @return A configured secure {@link ManagedChannel} instance
     */
    private static ManagedChannel createSecureChannel(String target, int maxInboundSize, TlsConfig tlsConfig) {
        try {
            SslContext sslContext = buildSslContext(tlsConfig);
            
            return NettyChannelBuilder.forTarget(target)
                    .sslContext(sslContext)
                    .maxInboundMessageSize(maxInboundSize)
                    .build();
        } catch (Exception e) {
            logger.error("Failed to create secure gRPC channel: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create secure gRPC channel", e);
        }
    }
    
    /**
     * Builds an SSL context for gRPC channel based on TLS configuration.
     * 
     * <p>This method supports three scenarios:</p>
     * <ul>
     *   <li>mTLS (mutual TLS): Both keystore and truststore are configured</li>
     *   <li>Server TLS only: Only truststore is configured</li>
     *   <li>JVM default: No explicit paths, uses JVM's default SSL context</li>
     * </ul>
     * 
     * @param tlsConfig TLS configuration
     * @return SslContext for the gRPC channel
     * @throws TlsConfigurationException if SSL context cannot be created
     */
    private static SslContext buildSslContext(TlsConfig tlsConfig) throws TlsConfigurationException {
        try {
            SslContextBuilder sslContextBuilder = GrpcSslContexts.forClient();
            
            // Configure truststore (for verifying server certificate)
            if (tlsConfig.hasTruststorePath()) {
                logger.info("Configuring client with custom truststore");
                TrustManagerFactory trustManagerFactory = tlsConfig.createTrustManagerFactory();
                sslContextBuilder.trustManager(trustManagerFactory);
            } else {
                logger.info("Using JVM default truststore for server certificate verification");
                // Use system default truststore
            }
            
            // Configure keystore (for client certificate - mTLS)
            if (tlsConfig.hasKeystorePath()) {
                logger.info("Configuring client with mTLS using keystore");
                KeyManagerFactory keyManagerFactory = tlsConfig.createKeyManagerFactory();
                sslContextBuilder.keyManager(keyManagerFactory);
            } else {
                logger.info("No client certificate configured - server TLS only (not mTLS)");
            }
            
            return sslContextBuilder.build();
        } catch (TlsConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new TlsConfigurationException("Failed to build SSL context for gRPC client", e);
        }
    }
}
