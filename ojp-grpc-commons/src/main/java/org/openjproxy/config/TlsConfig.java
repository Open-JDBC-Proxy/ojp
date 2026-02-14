package org.openjproxy.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * Configuration class for TLS/mTLS settings.
 * 
 * <p>This class handles TLS configuration for both client and server,
 * supporting both explicit keystore/truststore paths and JVM default stores.</p>
 */
public class TlsConfig {
    
    private final boolean enabled;
    private final String keystorePath;
    private final String keystorePassword;
    private final String truststorePath;
    private final String truststorePassword;
    private final String keystoreType;
    private final String truststoreType;
    
    /**
     * Default keystore type (JKS - Java KeyStore)
     */
    public static final String DEFAULT_KEYSTORE_TYPE = "JKS";
    
    /**
     * Default truststore type (JKS - Java KeyStore)
     */
    public static final String DEFAULT_TRUSTSTORE_TYPE = "JKS";
    
    /**
     * Constructor for TLS configuration.
     * 
     * @param enabled Whether TLS is enabled
     * @param keystorePath Path to keystore file (can be null to use JVM default)
     * @param keystorePassword Password for keystore
     * @param truststorePath Path to truststore file (can be null to use JVM default)
     * @param truststorePassword Password for truststore
     * @param keystoreType Keystore type (JKS, PKCS12, etc.)
     * @param truststoreType Truststore type (JKS, PKCS12, etc.)
     */
    public TlsConfig(boolean enabled, String keystorePath, String keystorePassword,
                     String truststorePath, String truststorePassword,
                     String keystoreType, String truststoreType) {
        this.enabled = enabled;
        this.keystorePath = keystorePath;
        this.keystorePassword = keystorePassword;
        this.truststorePath = truststorePath;
        this.truststorePassword = truststorePassword;
        this.keystoreType = keystoreType != null ? keystoreType : DEFAULT_KEYSTORE_TYPE;
        this.truststoreType = truststoreType != null ? truststoreType : DEFAULT_TRUSTSTORE_TYPE;
    }
    
    /**
     * @return true if TLS is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * @return Path to keystore file
     */
    public String getKeystorePath() {
        return keystorePath;
    }
    
    /**
     * @return Password for keystore
     */
    public String getKeystorePassword() {
        return keystorePassword;
    }
    
    /**
     * @return Path to truststore file
     */
    public String getTruststorePath() {
        return truststorePath;
    }
    
    /**
     * @return Password for truststore
     */
    public String getTruststorePassword() {
        return truststorePassword;
    }
    
    /**
     * @return Keystore type
     */
    public String getKeystoreType() {
        return keystoreType;
    }
    
    /**
     * @return Truststore type
     */
    public String getTruststoreType() {
        return truststoreType;
    }
    
    /**
     * Checks if explicit keystore path is configured.
     * 
     * @return true if keystore path is specified
     */
    public boolean hasKeystorePath() {
        return keystorePath != null && !keystorePath.trim().isEmpty();
    }
    
    /**
     * Checks if explicit truststore path is configured.
     * 
     * @return true if truststore path is specified
     */
    public boolean hasTruststorePath() {
        return truststorePath != null && !truststorePath.trim().isEmpty();
    }
    
    /**
     * Loads the keystore from the configured path.
     * 
     * @return KeyStore instance
     * @throws IOException if the file cannot be read
     * @throws KeyStoreException if keystore cannot be loaded
     * @throws NoSuchAlgorithmException if the algorithm used to check keystore integrity is not available
     * @throws CertificateException if any certificates in the keystore could not be loaded
     */
    public KeyStore loadKeyStore() throws IOException, KeyStoreException, NoSuchAlgorithmException, CertificateException {
        if (!hasKeystorePath()) {
            throw new IllegalStateException("Keystore path not configured");
        }
        
        KeyStore keyStore = KeyStore.getInstance(keystoreType);
        try (FileInputStream fis = new FileInputStream(new File(keystorePath))) {
            keyStore.load(fis, keystorePassword != null ? keystorePassword.toCharArray() : null);
        }
        return keyStore;
    }
    
    /**
     * Loads the truststore from the configured path.
     * 
     * @return KeyStore instance
     * @throws IOException if the file cannot be read
     * @throws KeyStoreException if truststore cannot be loaded
     * @throws NoSuchAlgorithmException if the algorithm used to check truststore integrity is not available
     * @throws CertificateException if any certificates in the truststore could not be loaded
     */
    public KeyStore loadTrustStore() throws IOException, KeyStoreException, NoSuchAlgorithmException, CertificateException {
        if (!hasTruststorePath()) {
            throw new IllegalStateException("Truststore path not configured");
        }
        
        KeyStore trustStore = KeyStore.getInstance(truststoreType);
        try (FileInputStream fis = new FileInputStream(new File(truststorePath))) {
            trustStore.load(fis, truststorePassword != null ? truststorePassword.toCharArray() : null);
        }
        return trustStore;
    }
    
    /**
     * Creates a KeyManagerFactory from the configured keystore.
     * 
     * @return KeyManagerFactory instance
     * @throws TlsConfigurationException if keystore cannot be loaded or KeyManagerFactory cannot be initialized
     */
    public KeyManagerFactory createKeyManagerFactory() throws TlsConfigurationException {
        try {
            KeyStore keyStore = loadKeyStore();
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, keystorePassword != null ? keystorePassword.toCharArray() : null);
            return keyManagerFactory;
        } catch (Exception e) {
            throw new TlsConfigurationException("Failed to create KeyManagerFactory from keystore: " + keystorePath, e);
        }
    }
    
    /**
     * Creates a TrustManagerFactory from the configured truststore.
     * 
     * @return TrustManagerFactory instance
     * @throws TlsConfigurationException if truststore cannot be loaded or TrustManagerFactory cannot be initialized
     */
    public TrustManagerFactory createTrustManagerFactory() throws TlsConfigurationException {
        try {
            KeyStore trustStore = loadTrustStore();
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            return trustManagerFactory;
        } catch (Exception e) {
            throw new TlsConfigurationException("Failed to create TrustManagerFactory from truststore: " + truststorePath, e);
        }
    }
    
    /**
     * Creates a TLS configuration with all settings disabled.
     * 
     * @return TlsConfig with TLS disabled
     */
    public static TlsConfig disabled() {
        return new TlsConfig(false, null, null, null, null, null, null);
    }
}
