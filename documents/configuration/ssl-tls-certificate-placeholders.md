# SSL/TLS Certificate Configuration with Property Placeholders

## Overview

OJP Server supports property placeholders in JDBC URLs to allow SSL/TLS certificate paths to be configured server-side rather than in the JDBC connection URL. This is particularly useful when:

- Certificate files reside on the OJP server, not on client machines
- You want to centralize certificate management
- You need to avoid hardcoding file paths in connection URLs
- You're using different certificate paths across environments (dev, staging, production)

**Important**: Some database drivers (notably Oracle) can also read SSL properties directly from JVM system properties without requiring URL parameters or placeholders. See the database-specific sections below for details.

## How It Works

1. **Client Side (JDBC Driver)**: Configure the JDBC URL in `ojp.properties` with placeholders like `${ojp.server.sslrootcert}`
2. **Server Side (OJP Server)**: Configure the actual certificate paths using JVM properties or environment variables
3. **Runtime**: When the server receives the connection request, it automatically resolves all placeholders before establishing the database connection

**Alternative for certain databases**: Some JDBC drivers (e.g., Oracle) natively support reading SSL configuration from JVM system properties, eliminating the need for URL placeholders entirely.

## Placeholder Format

Placeholders use the syntax: `${property.name}`

Example:
```
${ojp.server.sslrootcert}
${ojp.server.trustStore}
${ojp.server.oracle.wallet.location}
```

### Security Validation

**Important**: For security, property names in placeholders are validated against a whitelist pattern to prevent unauthorized access to system properties if a client is compromised.

#### Why Placeholder Validation is Critical

If a client application is compromised by an attacker, they could potentially modify the JDBC URL to inject malicious property placeholders. Without validation, this could lead to:

1. **System Property Exposure**: Attackers could read sensitive system properties like `${java.home}`, `${user.home}`, `${user.name}`, potentially revealing system configuration
2. **Command Injection**: Special characters could be used to execute arbitrary commands on the server
3. **SQL Injection**: Malicious SQL could be injected through certificate path parameters
4. **Path Traversal**: Attackers could access files outside intended directories using patterns like `../../../etc/passwd`
5. **Denial of Service**: Extremely long property names could consume server resources

To prevent these attacks, OJP implements strict whitelist-based validation that only allows property names following secure patterns.

#### Validation Rules

**Allowed property names must:**
- Start with `ojp.server.` or `ojp.client.` (whitelisted prefixes)
- Contain only alphanumeric characters, dots (`.`), hyphens (`-`), and underscores (`_`) in the suffix
- Have a suffix (after the prefix) between 1 and 200 characters in length
- Total property name can be up to 211 characters (e.g., "ojp.server." is 11 characters + 200 character suffix)

#### Attack Prevention Examples

**Examples of valid property names:**
```
${ojp.server.sslrootcert}           ✓ Valid - follows whitelist pattern
${ojp.server.mysql.truststore}      ✓ Valid - uses allowed characters
${ojp.client.config-value}          ✓ Valid - hyphens and underscores allowed
${ojp.server.ssl_root_cert.path}    ✓ Valid - dots and underscores allowed
```

**Examples of blocked attacks:**
```
${java.home}                        ✗ Blocked - System property exposure attempt
${user.home}                        ✗ Blocked - System property exposure attempt
${os.name}                          ✗ Blocked - System property exposure attempt
${ojp.server.cert;rm -rf /}         ✗ Blocked - Command injection with semicolon
${ojp.server.cert|malicious}        ✗ Blocked - Command injection with pipe
${ojp.server.cert&command}          ✗ Blocked - Command injection with ampersand
${ojp.server.cert../../../passwd}   ✗ Blocked - Path traversal attempt
${ojp.server.cert$(exploit)}        ✗ Blocked - Command substitution attempt
${ojp.server.cert;DROP TABLE}       ✗ Blocked - SQL injection attempt
${ojp.server.cert'OR'1'='1}         ✗ Blocked - SQL injection with quotes
```

#### Security Response

If an invalid property name is detected, the server will:
1. **Reject the connection** - The connection attempt is immediately terminated
2. **Throw SecurityException** - A `SecurityException` is thrown with details about the violation
3. **Log the violation** - The security violation is logged with the attempted property name for audit purposes
4. **Prevent further processing** - No database connection is established, protecting the server

This multi-layered approach ensures that even if a client is fully compromised, attackers cannot use property placeholders to attack the OJP server or access unauthorized system resources.

## Configuration Methods

### JVM System Properties

Set properties when starting the OJP server:

```bash
java -jar ojp-server.jar \
  -Dojp.server.sslrootcert=/etc/ojp/certs/ca-cert.pem \
  -Dojp.server.sslcert=/etc/ojp/certs/client-cert.pem \
  -Dojp.server.sslkey=/etc/ojp/certs/client-key.pem
```

### Environment Variables

Set environment variables (property names converted to uppercase with underscores):

```bash
export OJP_SERVER_SSLROOTCERT=/etc/ojp/certs/ca-cert.pem
export OJP_SERVER_SSLCERT=/etc/ojp/certs/client-cert.pem
export OJP_SERVER_SSLKEY=/etc/ojp/certs/client-key.pem

java -jar ojp-server.jar
```

**Note**: JVM properties take precedence over environment variables.

## SSL Configuration Approaches by Database

Different databases support different SSL configuration methods:

| Database | URL Placeholders | JVM Properties (No URL Changes) | Notes |
|----------|------------------|--------------------------------|-------|
| PostgreSQL | ✅ Supported | ❌ Not Supported | Requires SSL parameters in URL |
| MySQL/MariaDB | ✅ Supported | ⚠️ Limited | Standard Java SSL properties work, but driver-specific properties need URL |
| Oracle | ✅ Supported | ✅ **Fully Supported** | Oracle JDBC natively reads `oracle.net.*` JVM properties |
| SQL Server | ✅ Supported | ⚠️ Limited | Standard Java SSL properties work for basic SSL |
| DB2 | ✅ Supported | ❌ Not Supported | Requires SSL parameters in URL |

**Recommendation**: 
- For **Oracle**, prefer JVM-based configuration for simplicity (see Oracle section below)
- For **other databases**, use URL placeholders for explicit control
- For **MySQL/SQL Server**, JVM properties can be used for standard Java SSL (`javax.net.ssl.*`) but driver-specific properties need URL parameters

## Database-Specific Examples

**Important Note**: In all examples below, JDBC URLs are shown for readability but must be written as a single continuous line in actual properties files. Line breaks are not supported in `.properties` files.

### PostgreSQL

PostgreSQL supports SSL/TLS connections with various verification modes.

#### Client Configuration (ojp.properties)

```properties
# Basic SSL with CA certificate verification
ojp.datasource.url=jdbc:ojp[localhost:1059]_postgresql://dbhost:5432/mydb?ssl=true&sslmode=verify-full&sslrootcert=${ojp.server.sslrootcert}

# Full mutual TLS with client certificate
ojp.datasource.url=jdbc:ojp[localhost:1059]_postgresql://dbhost:5432/mydb?ssl=true&sslmode=verify-full&sslrootcert=${ojp.server.sslrootcert}&sslcert=${ojp.server.sslcert}&sslkey=${ojp.server.sslkey}
```

#### Server Configuration

```bash
# JVM properties
java -jar ojp-server.jar \
  -Dojp.server.sslrootcert=/etc/ojp/certs/postgresql/ca-cert.pem \
  -Dojp.server.sslcert=/etc/ojp/certs/postgresql/client-cert.pem \
  -Dojp.server.sslkey=/etc/ojp/certs/postgresql/client-key.pem

# Or environment variables
export OJP_SERVER_SSLROOTCERT=/etc/ojp/certs/postgresql/ca-cert.pem
export OJP_SERVER_SSLCERT=/etc/ojp/certs/postgresql/client-cert.pem
export OJP_SERVER_SSLKEY=/etc/ojp/certs/postgresql/client-key.pem
```

#### PostgreSQL SSL Parameters

| Parameter | Description |
|-----------|-------------|
| `sslrootcert` | Path to CA certificate file |
| `sslcert` | Path to client certificate file |
| `sslkey` | Path to client private key file |
| `sslmode` | SSL mode: `disable`, `require`, `verify-ca`, `verify-full` |

### MySQL / MariaDB

MySQL and MariaDB use Java KeyStore (JKS) files for SSL/TLS.

#### Client Configuration (ojp.properties)

```properties
# SSL with truststore
ojp.datasource.url=jdbc:ojp[localhost:1059]_mysql://dbhost:3306/mydb?useSSL=true&requireSSL=true&trustCertificateKeyStoreUrl=${ojp.server.mysql.truststore}&trustCertificateKeyStorePassword=${ojp.server.mysql.truststorePassword}

# Mutual TLS with client certificate
ojp.datasource.url=jdbc:ojp[localhost:1059]_mysql://dbhost:3306/mydb?useSSL=true&requireSSL=true&trustCertificateKeyStoreUrl=${ojp.server.mysql.truststore}&trustCertificateKeyStorePassword=${ojp.server.mysql.truststorePassword}&clientCertificateKeyStoreUrl=${ojp.server.mysql.keystore}&clientCertificateKeyStorePassword=${ojp.server.mysql.keystorePassword}
```

#### Server Configuration

```bash
# JVM properties
java -jar ojp-server.jar \
  -Dojp.server.mysql.truststore=file:///etc/ojp/certs/mysql/truststore.jks \
  -Dojp.server.mysql.truststorePassword=changeit \
  -Dojp.server.mysql.keystore=file:///etc/ojp/certs/mysql/keystore.jks \
  -Dojp.server.mysql.keystorePassword=changeit

# Or environment variables
export OJP_SERVER_MYSQL_TRUSTSTORE=file:///etc/ojp/certs/mysql/truststore.jks
export OJP_SERVER_MYSQL_TRUSTSTOREPASSWORD=changeit
export OJP_SERVER_MYSQL_KEYSTORE=file:///etc/ojp/certs/mysql/keystore.jks
export OJP_SERVER_MYSQL_KEYSTOREPASSWORD=changeit
```

#### MySQL/MariaDB SSL Parameters

| Parameter | Description |
|-----------|-------------|
| `trustCertificateKeyStoreUrl` | URL to truststore (use `file:///` prefix) |
| `trustCertificateKeyStorePassword` | Truststore password |
| `clientCertificateKeyStoreUrl` | URL to client keystore |
| `clientCertificateKeyStorePassword` | Keystore password |
| `useSSL` | Enable SSL (true/false) |
| `requireSSL` | Require SSL connection (true/false) |

#### MySQL/MariaDB JVM-Based SSL (Standard Java SSL Properties)

MySQL/MariaDB can use standard Java SSL properties for basic SSL configuration, but this only works with the default Java SSL context and not for driver-specific keystores:

**Server Configuration:**
```bash
# Using standard Java SSL properties (limited functionality)
java -jar ojp-server.jar \
  -Djavax.net.ssl.trustStore=/etc/ojp/certs/truststore.jks \
  -Djavax.net.ssl.trustStorePassword=changeit \
  -Djavax.net.ssl.keyStore=/etc/ojp/certs/keystore.jks \
  -Djavax.net.ssl.keyStorePassword=changeit
```

**Client Configuration:**
```properties
# Simple URL - SSL configured via JVM properties
ojp.datasource.url=jdbc:ojp[localhost:1059]_mysql://dbhost:3306/mydb?useSSL=true&requireSSL=true
```

**Limitations**: This approach uses the default Java SSL context. For explicit control over certificate locations or to use multiple different keystores, use URL placeholders instead.

### Oracle

Oracle uses Oracle Wallet for SSL/TLS certificate management.

#### Client Configuration (ojp.properties)

```properties
# Oracle with wallet
ojp.datasource.url=jdbc:ojp[localhost:1059]_oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCPS)(HOST=dbhost)(PORT=2484))(CONNECT_DATA=(SERVICE_NAME=myservice)))

# Alternative format with wallet location in URL
ojp.datasource.url=jdbc:ojp[localhost:1059]_oracle:thin:@dbhost:2484/myservice?oracle.net.wallet_location=${ojp.server.oracle.wallet.location}&oracle.net.ssl_server_dn_match=true
```

#### Server Configuration

```bash
# JVM properties
java -jar ojp-server.jar \
  -Dojp.server.oracle.wallet.location=/etc/ojp/wallet \
  -Doracle.net.tns_admin=/etc/ojp/tns

# Or environment variables
export OJP_SERVER_ORACLE_WALLET_LOCATION=/etc/ojp/wallet
export ORACLE_NET_TNS_ADMIN=/etc/ojp/tns
```

#### Oracle SSL Parameters

| Parameter | Description |
|-----------|-------------|
| `oracle.net.wallet_location` | Path to Oracle Wallet directory |
| `oracle.net.ssl_server_dn_match` | Verify server DN (true/false) |
| `oracle.net.ssl_version` | SSL/TLS version |
| `oracle.net.tns_admin` | Path to TNS configuration directory |

**Note**: Oracle Wallet must be configured using Oracle Wallet Manager (`owm`) or `orapki` utility before use.

#### Oracle JVM-Based SSL Configuration (No URL Parameters)

Oracle JDBC driver supports reading SSL configuration directly from JVM system properties without requiring parameters in the JDBC URL. This approach is useful when:
- SSL properties are standardized across multiple connections
- You want to avoid exposing wallet paths in connection URLs
- Using TCPS protocol in TNS connection descriptors

**Scenario 1: Using JVM Properties Only (No URL Placeholders)**

With this approach, the Oracle JDBC driver automatically picks up SSL properties from JVM arguments at the OJP server startup, and no URL modification is needed.

**Client Configuration (ojp.properties):**
```properties
# Simple Oracle URL without SSL parameters
# SSL configuration is handled entirely by JVM properties on the server
ojp.datasource.url=jdbc:ojp[localhost:1059]_oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCPS)(HOST=dbhost)(PORT=2484))(CONNECT_DATA=(SERVICE_NAME=myservice)))

# Or using TNS alias (requires tnsnames.ora on server)
ojp.datasource.url=jdbc:ojp[localhost:1059]_oracle:thin:@mydb_ssl
```

**Server Configuration:**
```bash
# Start OJP server with Oracle SSL JVM properties
# Oracle JDBC driver will automatically use these properties
java -jar ojp-server.jar \
  -Doracle.net.wallet_location=/etc/ojp/wallet \
  -Doracle.net.tns_admin=/etc/ojp/tns \
  -Doracle.net.ssl_server_dn_match=true \
  -Doracle.net.ssl_version=1.2 \
  -Djavax.net.ssl.trustStore=/etc/ojp/certs/truststore.jks \
  -Djavax.net.ssl.trustStorePassword=changeit
```

**Key Benefits:**
- No placeholders needed in JDBC URL
- Oracle JDBC driver natively reads these properties
- Simplifies client configuration
- All Oracle connections from the server inherit the same SSL settings

**Scenario 2: Hybrid Approach (JVM Properties + URL Placeholders)**

You can combine both methods for flexibility:

**Client Configuration:**
```properties
# Override specific properties in URL while using JVM defaults for others
ojp.datasource.url=jdbc:ojp[localhost:1059]_oracle:thin:@dbhost:2484/myservice?oracle.net.wallet_location=${ojp.server.oracle.custom.wallet}
```

**Server Configuration:**
```bash
# Base SSL configuration via JVM properties
java -jar ojp-server.jar \
  -Doracle.net.ssl_server_dn_match=true \
  -Doracle.net.ssl_version=1.2 \
  -Dojp.server.oracle.custom.wallet=/etc/ojp/custom-wallet
```

**Important Notes:**
1. **Property Precedence**: URL parameters override JVM system properties in Oracle JDBC
2. **TCPS Protocol**: When using `PROTOCOL=TCPS` in connection descriptor, SSL is automatically enabled
3. **TNS Configuration**: If using TNS aliases, ensure `tnsnames.ora` is accessible via `oracle.net.tns_admin`
4. **Wallet Auto-Discovery**: Oracle JDBC can auto-discover wallets in default locations if not explicitly specified


### SQL Server

SQL Server uses Java truststore for SSL/TLS.

#### Client Configuration (ojp.properties)

```properties
# SQL Server with SSL
ojp.datasource.url=jdbc:ojp[localhost:1059]_sqlserver://dbhost:1433;databaseName=mydb;encrypt=true;trustServerCertificate=false;trustStore=${ojp.server.sqlserver.truststore};trustStorePassword=${ojp.server.sqlserver.truststorePassword}

# With client certificate
ojp.datasource.url=jdbc:ojp[localhost:1059]_sqlserver://dbhost:1433;databaseName=mydb;encrypt=true;trustStore=${ojp.server.sqlserver.truststore};trustStorePassword=${ojp.server.sqlserver.truststorePassword};keyStore=${ojp.server.sqlserver.keystore};keyStorePassword=${ojp.server.sqlserver.keystorePassword}
```

#### Server Configuration

```bash
# JVM properties
java -jar ojp-server.jar \
  -Dojp.server.sqlserver.truststore=/etc/ojp/certs/sqlserver/truststore.jks \
  -Dojp.server.sqlserver.truststorePassword=changeit \
  -Dojp.server.sqlserver.keystore=/etc/ojp/certs/sqlserver/keystore.jks \
  -Dojp.server.sqlserver.keystorePassword=changeit

# Or environment variables
export OJP_SERVER_SQLSERVER_TRUSTSTORE=/etc/ojp/certs/sqlserver/truststore.jks
export OJP_SERVER_SQLSERVER_TRUSTSTOREPASSWORD=changeit
export OJP_SERVER_SQLSERVER_KEYSTORE=/etc/ojp/certs/sqlserver/keystore.jks
export OJP_SERVER_SQLSERVER_KEYSTOREPASSWORD=changeit
```

#### SQL Server SSL Parameters

| Parameter | Description |
|-----------|-------------|
| `encrypt` | Enable encryption (true/false) |
| `trustServerCertificate` | Trust server certificate without validation (true/false) |
| `trustStore` | Path to truststore file |
| `trustStorePassword` | Truststore password |
| `keyStore` | Path to keystore file |
| `keyStorePassword` | Keystore password |

#### SQL Server JVM-Based SSL (Standard Java SSL Properties)

SQL Server JDBC driver can use standard Java SSL properties for basic SSL encryption:

**Server Configuration:**
```bash
# Using standard Java SSL properties
java -jar ojp-server.jar \
  -Djavax.net.ssl.trustStore=/etc/ojp/certs/truststore.jks \
  -Djavax.net.ssl.trustStorePassword=changeit
```

**Client Configuration:**
```properties
# Simple URL - SSL configured via JVM properties
ojp.datasource.url=jdbc:ojp[localhost:1059]_sqlserver://dbhost:1433;databaseName=mydb;encrypt=true;trustServerCertificate=false
```

**Limitations**: This uses the default Java SSL context. For explicit truststore/keystore paths or SQL Server-specific SSL properties, use URL placeholders instead.

### DB2

DB2 uses SSL/TLS with Java truststore.

#### Client Configuration (ojp.properties)

```properties
# DB2 with SSL
ojp.datasource.url=jdbc:ojp[localhost:1059]_db2://dbhost:50001/mydb:sslConnection=true;sslTrustStoreLocation=${ojp.server.db2.truststore};sslTrustStorePassword=${ojp.server.db2.truststorePassword};

# With client certificate
ojp.datasource.url=jdbc:ojp[localhost:1059]_db2://dbhost:50001/mydb:sslConnection=true;sslTrustStoreLocation=${ojp.server.db2.truststore};sslTrustStorePassword=${ojp.server.db2.truststorePassword};sslKeyStoreLocation=${ojp.server.db2.keystore};sslKeyStorePassword=${ojp.server.db2.keystorePassword};
```

#### Server Configuration

```bash
# JVM properties
java -jar ojp-server.jar \
  -Dojp.server.db2.truststore=/etc/ojp/certs/db2/truststore.jks \
  -Dojp.server.db2.truststorePassword=changeit \
  -Dojp.server.db2.keystore=/etc/ojp/certs/db2/keystore.jks \
  -Dojp.server.db2.keystorePassword=changeit

# Or environment variables
export OJP_SERVER_DB2_TRUSTSTORE=/etc/ojp/certs/db2/truststore.jks
export OJP_SERVER_DB2_TRUSTSTOREPASSWORD=changeit
export OJP_SERVER_DB2_KEYSTORE=/etc/ojp/certs/db2/keystore.jks
export OJP_SERVER_DB2_KEYSTOREPASSWORD=changeit
```

#### DB2 SSL Parameters

| Parameter | Description |
|-----------|-------------|
| `sslConnection` | Enable SSL (true/false) |
| `sslTrustStoreLocation` | Path to truststore file |
| `sslTrustStorePassword` | Truststore password |
| `sslKeyStoreLocation` | Path to keystore file |
| `sslKeyStorePassword` | Keystore password |

## Best Practices

### 1. Use Descriptive Property Names

Use property names that clearly indicate their purpose:

```bash
# Good
-Dojp.server.postgresql.prod.sslrootcert=/etc/certs/prod/ca.pem
-Dojp.server.mysql.dev.truststore=/etc/certs/dev/truststore.jks

# Avoid generic names
-Dojp.server.cert1=/etc/certs/ca.pem
-Dojp.server.cert2=/etc/certs/truststore.jks
```

### 2. Separate Certificates by Database and Environment

Organize certificates in a directory structure:

```
/etc/ojp/certs/
├── postgresql/
│   ├── dev/
│   │   ├── ca-cert.pem
│   │   ├── client-cert.pem
│   │   └── client-key.pem
│   └── prod/
│       ├── ca-cert.pem
│       ├── client-cert.pem
│       └── client-key.pem
├── mysql/
│   ├── dev/
│   │   ├── truststore.jks
│   │   └── keystore.jks
│   └── prod/
│       ├── truststore.jks
│       └── keystore.jks
└── oracle/
    ├── dev/
    │   └── wallet/
    └── prod/
        └── wallet/
```

### 3. Secure File Permissions

Ensure certificate files have appropriate permissions:

```bash
# Certificate files should be readable only by the OJP server user
# Use find for better portability across shells
find /etc/ojp/certs \( -name '*.pem' -o -name '*.jks' \) -type f -exec chmod 400 {} \;

# Directories should be accessible
find /etc/ojp/certs -type d -exec chmod 500 {} \;
chmod 400 /etc/ojp/certs/**/ca-cert.pem
chmod 400 /etc/ojp/certs/**/client-cert.pem
chmod 400 /etc/ojp/certs/**/client-key.pem
chmod 400 /etc/ojp/certs/**/*.jks

```

### 4. Use Environment Variables in Production

For production deployments, use environment variables instead of JVM properties to avoid exposing sensitive paths in process listings:

```bash
# In systemd service file
[Service]
Environment="OJP_SERVER_SSLROOTCERT=/etc/ojp/certs/prod/ca-cert.pem"
Environment="OJP_SERVER_SSLCERT=/etc/ojp/certs/prod/client-cert.pem"
Environment="OJP_SERVER_SSLKEY=/etc/ojp/certs/prod/client-key.pem"
```

### 5. Document Your Property Names

Maintain a configuration reference document listing all property placeholders used in your environment:

```markdown
| Property Name | Environment Variable | Description | Example Value |
|---------------|---------------------|-------------|---------------|
| ojp.server.sslrootcert | OJP_SERVER_SSLROOTCERT | PostgreSQL CA cert | /etc/ojp/certs/ca.pem |
| ojp.server.mysql.truststore | OJP_SERVER_MYSQL_TRUSTSTORE | MySQL truststore | file:///etc/ojp/certs/truststore.jks |
```

## Troubleshooting

### Error: Unable to Resolve Placeholder

**Error Message:**
```
Unable to resolve placeholder '${ojp.server.sslrootcert}'. 
Please set JVM property '-Dojp.server.sslrootcert=<value>' or environment variable 'OJP_SERVER_SSLROOTCERT'
```

**Solution:**
Ensure the property is set either as a JVM property or environment variable before starting the OJP server.

### File Not Found Errors

**Symptoms:**
- `FileNotFoundException` for certificate files
- SSL handshake failures

**Solutions:**
1. Verify the file paths are correct
2. Check file permissions (OJP server user must have read access)
3. Ensure files exist on the server where OJP is running (not the client)
4. For URL-based paths (e.g., MySQL), ensure correct format: `file:///absolute/path`

### Certificate Validation Errors

**Symptoms:**
- SSL handshake failures
- Certificate verification errors

**Solutions:**
1. Ensure CA certificate matches the server certificate
2. Check certificate expiration dates
3. Verify hostname matches certificate CN/SAN
4. For PostgreSQL, use `sslmode=verify-full` for full validation
5. Check certificate chain is complete

## Security Considerations

### Why Secure Placeholder Naming is Important

When defining property placeholders for SSL/TLS certificate paths, it's crucial to understand that these placeholders are sent from the client to the OJP server as part of the JDBC URL. If a client application is compromised, attackers could manipulate these placeholders to:

- **Access sensitive system information** by injecting system property names
- **Execute arbitrary commands** through shell injection attacks
- **Read unauthorized files** via path traversal techniques
- **Inject malicious SQL** or commands into the server
- **Cause denial of service** by sending extremely long property names

This is why OJP implements strict validation rules for property placeholder names.

### Placeholder Validation

OJP implements strict security validation for property placeholders to protect against malicious attacks if a client is compromised:

1. **Whitelist-based validation**: Only property names starting with `ojp.server.` or `ojp.client.` are allowed
   - This prevents access to system properties like `java.home`, `user.home`, `os.name`
   - Ensures only OJP-specific configuration can be accessed

2. **Character restrictions**: Property names can only contain alphanumeric characters, dots, hyphens, and underscores in the suffix
   - Blocks special characters used in command injection (`;`, `|`, `&`, `$`, etc.)
   - Prevents SQL injection characters (`'`, `"`, `=`, etc.)
   - Stops path traversal characters (`/`, `\`, etc.)

3. **Length limits**: Property name suffix (after prefix) is limited to 200 characters to prevent DoS attacks
   - Prevents memory exhaustion from extremely long property names
   - Limits resource consumption during validation and resolution

4. **Injection prevention**: Special characters like semicolons, pipes, backslashes, and quotes are rejected
   - Blocks command injection: `${ojp.server.cert;rm -rf /}`
   - Blocks SQL injection: `${ojp.server.cert';DROP TABLE}`
   - Blocks shell expansion: `${ojp.server.cert$(malicious)}`

5. **Path traversal protection**: Attempts like `../../../etc/passwd` are blocked
   - Prevents directory traversal attacks
   - Ensures only intended property names are accessed

If an invalid property name is detected, the server throws a `SecurityException`, logs the security violation, and rejects the connection attempt.

### Best Practices for Defining Placeholders

When defining property placeholders in your client configuration:

1. **Use descriptive, clear names**: `ojp.server.postgresql.sslrootcert` instead of `ojp.server.cert1`
2. **Include the database type**: `ojp.server.mysql.truststore`, `ojp.server.oracle.wallet`
3. **Include the environment if needed**: `ojp.server.prod.sslcert`, `ojp.server.dev.sslkey`
4. **Use only allowed characters**: Stick to alphanumeric, dots, hyphens, and underscores
5. **Keep names reasonable length**: Stay well under the 200 character suffix limit
6. **Always use the ojp.server prefix**: This ensures validation passes and clarifies the property scope

**Example of well-defined placeholders:**
```properties
# Good: Clear, descriptive, follows all rules
# (In actual usage, the URL should be on a single line)
ojp.datasource.url=jdbc:ojp[localhost:1059]_postgresql://host:5432/db?sslrootcert=${ojp.server.postgresql.prod.ca-cert}&sslcert=${ojp.server.postgresql.prod.client-cert}&sslkey=${ojp.server.postgresql.prod.client-key}
```

### Certificate Management

1. **Never commit certificates to version control**: Use placeholders in configuration files
2. **Rotate certificates regularly**: Update property values when certificates are rotated
3. **Limit file access**: Use restrictive file permissions (400 or 440)
4. **Use strong passwords**: For keystores and truststores
5. **Monitor certificate expiration**: Set up alerts for expiring certificates
6. **Validate certificates**: Always use certificate validation in production (`sslmode=verify-full` for PostgreSQL, `trustServerCertificate=false` for SQL Server)
7. **Separate environments**: Use different certificates for dev, staging, and production
8. **Audit access**: Log and monitor access to certificate files and property configurations

## Additional Resources

- [PostgreSQL SSL Documentation](https://www.postgresql.org/docs/current/libpq-ssl.html)
- [MySQL SSL/TLS Documentation](https://dev.mysql.com/doc/connector-j/8.0/en/connector-j-reference-using-ssl.html)
- [Oracle Wallet Management](https://docs.oracle.com/en/database/oracle/oracle-database/19/dbseg/using-oracle-wallet-manager.html)
- [SQL Server Encryption](https://docs.microsoft.com/en-us/sql/connect/jdbc/using-ssl-encryption)
- [DB2 SSL Configuration](https://www.ibm.com/docs/en/db2/11.5?topic=connections-configuring-ssl-support)
