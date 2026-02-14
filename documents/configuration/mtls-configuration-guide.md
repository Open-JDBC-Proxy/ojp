# mTLS Configuration Guide for OJP

This guide explains how to configure mutual TLS (mTLS) for secure communication between OJP JDBC driver (client) and OJP Server.

## Table of Contents
- [Overview](#overview)
- [Configuration Approaches](#configuration-approaches)
- [Client Configuration](#client-configuration)
- [Server Configuration](#server-configuration)
- [Certificate Management](#certificate-management)
- [Example Configurations](#example-configurations)
- [Troubleshooting](#troubleshooting)

## Overview

OJP supports three modes of operation:
1. **Plaintext** (default) - No encryption
2. **Server TLS** - Server authentication only
3. **mTLS** (mutual TLS) - Both server and client authentication

### Security Modes

| Mode | Server Certificate | Client Certificate | Use Case |
|------|-------------------|-------------------|----------|
| Plaintext | ❌ | ❌ | Development only |
| Server TLS | ✅ | ❌ | Encrypted communication |
| mTLS | ✅ | ✅ | Maximum security - both sides authenticated |

## Configuration Approaches

OJP supports two approaches for TLS configuration:

### 1. Explicit Keystore/Truststore Paths

Configure specific paths to certificate files on the filesystem.

**Pros:**
- Full control over certificates
- Different certificates per application
- Easy to rotate certificates

**Cons:**
- Requires managing certificate files
- Path configuration needed

### 2. JVM Default Keystores

Use the JVM's default SSL context and certificate stores.

**Pros:**
- No explicit configuration needed
- Centralized certificate management
- Works with corporate PKI infrastructure

**Cons:**
- Less granular control
- System-wide impact

## Client Configuration

### Using System Properties

```java
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

public class DataSourceConfig {
    
    public DataSource createSecureDataSource() {
        // Enable TLS with explicit certificate paths
        System.setProperty("ojp.client.tls.enabled", "true");
        System.setProperty("ojp.client.tls.keystore.path", "/etc/app/tls/ojp-client-keystore.jks");
        System.setProperty("ojp.client.tls.keystore.password", "changeit");
        System.setProperty("ojp.client.tls.truststore.path", "/etc/app/tls/ojp-client-truststore.jks");
        System.setProperty("ojp.client.tls.truststore.password", "changeit");
        
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:ojp://ojp-server.internal.company.com:1059/postgresql://dbhost:5432/mydb");
        config.setUsername("app_user");
        config.setPassword("app_password");
        config.setMaximumPoolSize(20);
        
        return new HikariDataSource(config);
    }
}
```

### Using ojp.properties File

Create a file named `ojp.properties` in your application's classpath:

```properties
# Client mTLS configuration with explicit paths
ojp.client.tls.enabled=true
ojp.client.tls.keystore.path=/etc/app/tls/ojp-client-keystore.jks
ojp.client.tls.keystore.password=changeit
ojp.client.tls.truststore.path=/etc/app/tls/ojp-client-truststore.jks
ojp.client.tls.truststore.password=changeit

# Optional: Keystore types (default: JKS)
ojp.client.tls.keystore.type=JKS
ojp.client.tls.truststore.type=JKS

# Connection settings
ojp.connection.url=jdbc:ojp://ojp-server.internal.company.com:1059/postgresql://dbhost:5432/mydb
```

### Using JVM Default Truststore

For server TLS only (no client certificate):

```properties
# Enable TLS but rely on JVM default truststore
ojp.client.tls.enabled=true
# Don't specify keystore path - no client certificate
# Don't specify truststore path - use JVM default
```

The client will use the JVM's default truststore (typically `$JAVA_HOME/lib/security/cacerts`) to verify the server certificate.

### Client Configuration Properties

| Property | Required | Default | Description | Since |
|----------|----------|---------|-------------|-------|
| `ojp.client.tls.enabled` | No | `false` | Enable/disable TLS | 0.4.0-beta |
| `ojp.client.tls.keystore.path` | For mTLS | - | Path to client certificate keystore | 0.4.0-beta |
| `ojp.client.tls.keystore.password` | For mTLS | - | Password for client keystore | 0.4.0-beta |
| `ojp.client.tls.truststore.path` | No | JVM default | Path to truststore for server verification | 0.4.0-beta |
| `ojp.client.tls.truststore.password` | No | - | Password for truststore | 0.4.0-beta |
| `ojp.client.tls.keystore.type` | No | `JKS` | Keystore type (JKS, PKCS12, etc.) | 0.4.0-beta |
| `ojp.client.tls.truststore.type` | No | `JKS` | Truststore type | 0.4.0-beta |

## Server Configuration

### Using JVM System Properties

```bash
java -Dojp.server.tls.enabled=true \
     -Dojp.server.tls.keystore.path=/etc/ojp/tls/server-keystore.jks \
     -Dojp.server.tls.keystore.password=changeit \
     -Dojp.server.tls.truststore.path=/etc/ojp/tls/server-truststore.jks \
     -Dojp.server.tls.truststore.password=changeit \
     -Dojp.server.tls.clientAuthRequired=true \
     -jar ojp-server.jar
```

### Using Environment Variables

```bash
export OJP_SERVER_TLS_ENABLED=true
export OJP_SERVER_TLS_KEYSTORE_PATH=/etc/ojp/tls/server-keystore.jks
export OJP_SERVER_TLS_KEYSTORE_PASSWORD=changeit
export OJP_SERVER_TLS_TRUSTSTORE_PATH=/etc/ojp/tls/server-truststore.jks
export OJP_SERVER_TLS_TRUSTSTORE_PASSWORD=changeit
export OJP_SERVER_TLS_CLIENTAUTHREQUIRED=true

java -jar ojp-server.jar
```

### Server TLS Only (No Client Authentication)

```bash
java -Dojp.server.tls.enabled=true \
     -Dojp.server.tls.keystore.path=/etc/ojp/tls/server-keystore.jks \
     -Dojp.server.tls.keystore.password=changeit \
     -Dojp.server.tls.clientAuthRequired=false \
     -jar ojp-server.jar
```

### Server Configuration Properties

| Property | Required | Default | Description | Since |
|----------|----------|---------|-------------|-------|
| `ojp.server.tls.enabled` | No | `false` | Enable/disable TLS | 0.4.0-beta |
| `ojp.server.tls.keystore.path` | Yes (if TLS enabled) | - | Path to server certificate keystore | 0.4.0-beta |
| `ojp.server.tls.keystore.password` | Yes | - | Password for server keystore | 0.4.0-beta |
| `ojp.server.tls.truststore.path` | For mTLS | JVM default | Path to truststore for client verification | 0.4.0-beta |
| `ojp.server.tls.truststore.password` | No | - | Password for truststore | 0.4.0-beta |
| `ojp.server.tls.keystore.type` | No | `JKS` | Keystore type | 0.4.0-beta |
| `ojp.server.tls.truststore.type` | No | `JKS` | Truststore type | 0.4.0-beta |
| `ojp.server.tls.clientAuthRequired` | No | `false` | Require client certificates (mTLS) | 0.4.0-beta |

## Certificate Management

### Generating Self-Signed Certificates for Testing

**⚠️ WARNING: Self-signed certificates are for testing only! Use proper CA-signed certificates in production.**

#### 1. Generate Server Certificate

```bash
# Generate server keystore with self-signed certificate
keytool -genkeypair -alias ojp-server \
        -keyalg RSA -keysize 2048 \
        -validity 365 \
        -keystore server-keystore.jks \
        -storepass changeit \
        -keypass changeit \
        -dname "CN=ojp-server.internal.company.com, OU=IT, O=Company, L=City, ST=State, C=US"

# Export server certificate
keytool -exportcert -alias ojp-server \
        -file server-cert.pem \
        -keystore server-keystore.jks \
        -storepass changeit \
        -rfc
```

#### 2. Generate Client Certificate

```bash
# Generate client keystore with self-signed certificate
keytool -genkeypair -alias ojp-client \
        -keyalg RSA -keysize 2048 \
        -validity 365 \
        -keystore client-keystore.jks \
        -storepass changeit \
        -keypass changeit \
        -dname "CN=ojp-client, OU=Applications, O=Company, L=City, ST=State, C=US"

# Export client certificate
keytool -exportcert -alias ojp-client \
        -file client-cert.pem \
        -keystore client-keystore.jks \
        -storepass changeit \
        -rfc
```

#### 3. Create Truststores

```bash
# Create server truststore and import client certificate
keytool -importcert -alias ojp-client \
        -file client-cert.pem \
        -keystore server-truststore.jks \
        -storepass changeit \
        -noprompt

# Create client truststore and import server certificate
keytool -importcert -alias ojp-server \
        -file server-cert.pem \
        -keystore client-truststore.jks \
        -storepass changeit \
        -noprompt
```

### Using CA-Signed Certificates (Production)

In production, use certificates signed by your organization's Certificate Authority:

1. Generate a Certificate Signing Request (CSR)
2. Submit CSR to your CA
3. Receive signed certificate from CA
4. Import signed certificate into keystore
5. Import CA certificate chain into truststore

```bash
# Generate CSR
keytool -certreq -alias ojp-server \
        -file server.csr \
        -keystore server-keystore.jks \
        -storepass changeit

# After receiving signed certificate from CA, import it
keytool -importcert -alias ojp-server \
        -file server-signed.crt \
        -keystore server-keystore.jks \
        -storepass changeit
```

## Example Configurations

### Example 1: Development Environment (Server TLS Only)

**Server:**
```bash
java -Dojp.server.tls.enabled=true \
     -Dojp.server.tls.keystore.path=/opt/ojp/certs/server-keystore.jks \
     -Dojp.server.tls.keystore.password=devpassword \
     -jar ojp-server.jar
```

**Client (ojp.properties):**
```properties
ojp.client.tls.enabled=true
ojp.client.tls.truststore.path=/opt/app/certs/client-truststore.jks
ojp.client.tls.truststore.password=devpassword
```

### Example 2: Production Environment (Full mTLS)

**Server:**
```bash
java -Dojp.server.tls.enabled=true \
     -Dojp.server.tls.keystore.path=/etc/ojp/ssl/server.jks \
     -Dojp.server.tls.keystore.password=${SERVER_KEYSTORE_PASSWORD} \
     -Dojp.server.tls.truststore.path=/etc/ojp/ssl/truststore.jks \
     -Dojp.server.tls.truststore.password=${SERVER_TRUSTSTORE_PASSWORD} \
     -Dojp.server.tls.clientAuthRequired=true \
     -jar ojp-server.jar
```

**Client (System Properties):**
```java
System.setProperty("ojp.client.tls.enabled", "true");
System.setProperty("ojp.client.tls.keystore.path", "/etc/app/ssl/client.jks");
System.setProperty("ojp.client.tls.keystore.password", System.getenv("CLIENT_KEYSTORE_PASSWORD"));
System.setProperty("ojp.client.tls.truststore.path", "/etc/app/ssl/truststore.jks");
System.setProperty("ojp.client.tls.truststore.password", System.getenv("CLIENT_TRUSTSTORE_PASSWORD"));
```

### Example 3: Using JVM Default Keystores

**Server:**
```bash
# Import server certificate into JVM keystore
keytool -importkeystore \
        -srckeystore /etc/ojp/ssl/server.jks \
        -destkeystore $JAVA_HOME/lib/security/cacerts \
        -srcstorepass changeit \
        -deststorepass changeit

java -Dojp.server.tls.enabled=true \
     -Dojp.server.tls.keystore.path=/etc/ojp/ssl/server.jks \
     -Dojp.server.tls.keystore.password=changeit \
     -jar ojp-server.jar
```

**Client:**
```properties
# Client trusts server via JVM default truststore
ojp.client.tls.enabled=true
# No explicit truststore path - uses JVM default
```

## Troubleshooting

### Common Issues

#### 1. Connection Refused / Timeout

**Symptoms:** Client cannot connect to server

**Solutions:**
- Verify server is running with TLS enabled
- Check firewall rules allow traffic on configured port
- Verify client is connecting to correct host/port
- Check server logs for binding errors

#### 2. Certificate Validation Failed

**Symptoms:** `javax.net.ssl.SSLHandshakeException: PKIX path building failed`

**Solutions:**
- Verify server certificate is in client truststore
- Check certificate hasn't expired: `keytool -list -v -keystore truststore.jks`
- Verify hostname matches certificate CN/SAN
- Ensure certificate chain is complete

#### 3. Client Certificate Required

**Symptoms:** `javax.net.ssl.SSLHandshakeException: Received fatal alert: bad_certificate`

**Solutions:**
- Server requires client certificate (mTLS) but client didn't provide one
- Configure client keystore path and password
- Verify client certificate is in server truststore

#### 4. Password Incorrect

**Symptoms:** `java.io.IOException: Keystore was tampered with, or password was incorrect`

**Solutions:**
- Verify keystore/truststore passwords are correct
- Check for special characters in passwords that need escaping
- Ensure password environment variables are set correctly

### Debug Logging

Enable SSL debug logging to troubleshoot connection issues:

```bash
# Enable SSL debug logging
java -Djavax.net.debug=ssl,handshake \
     -Dojp.server.tls.enabled=true \
     -jar ojp-server.jar
```

### Testing TLS Configuration

Use OpenSSL to test server TLS configuration:

```bash
# Test server TLS
openssl s_client -connect ojp-server.internal.company.com:1059

# Test with client certificate (mTLS)
openssl s_client -connect ojp-server.internal.company.com:1059 \
        -cert client-cert.pem \
        -key client-key.pem
```

## Security Best Practices

1. **Use Strong Passwords:** Never use default passwords like "changeit" in production
2. **Certificate Rotation:** Regularly rotate certificates before expiration
3. **Restrict File Permissions:** Protect keystore files with proper filesystem permissions (e.g., `chmod 600`)
4. **Use Secrets Management:** Store passwords in secure secrets management systems (e.g., HashiCorp Vault, AWS Secrets Manager)
5. **Monitor Expiration:** Set up alerts for certificate expiration
6. **Use CA-Signed Certificates:** Always use properly signed certificates in production
7. **Enable mTLS:** Use mutual TLS for maximum security in production environments
8. **Audit Access:** Log and monitor TLS connection attempts
9. **Keep Software Updated:** Regularly update OJP and Java to get security patches
10. **Test Configuration:** Thoroughly test TLS configuration in staging before production deployment

## Questions & Evaluation

### Does Using JVM Truststore/Keystore Suffice for mTLS?

**Yes, but with considerations:**

#### Advantages:
- ✅ Simpler configuration - no explicit paths needed
- ✅ Centralized certificate management
- ✅ Automatic integration with corporate PKI
- ✅ Works well in containerized environments with shared base images

#### Disadvantages:
- ❌ Less granular control per application
- ❌ Harder to isolate certificate issues
- ❌ System-wide impact when updating certificates
- ❌ May conflict with other applications' certificate requirements

#### Recommendation:

| Environment | Recommended Approach |
|-------------|---------------------|
| Development | Explicit paths (easier debugging) |
| Testing/Staging | Explicit paths (isolated testing) |
| Production (Single App) | Either approach works |
| Production (Multi-tenant) | Explicit paths (isolation) |
| Kubernetes/Container | JVM default (simpler deployment) |

For most production scenarios, **explicit keystore/truststore paths** provide better security isolation and operational flexibility.

## Support

For issues or questions:
- GitHub Issues: https://github.com/Open-J-Proxy/ojp/issues
- Documentation: https://github.com/Open-J-Proxy/ojp/tree/main/documents
