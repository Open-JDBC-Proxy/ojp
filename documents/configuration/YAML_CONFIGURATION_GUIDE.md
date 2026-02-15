# YAML Configuration Guide for OJP

OJP supports both traditional Java Properties files and modern YAML configuration files. This guide explains how to use YAML configuration with OJP.

## Table of Contents

- [Overview](#overview)
- [Configuration File Formats](#configuration-file-formats)
- [Configuration File Loading Order](#configuration-file-loading-order)
- [Configuration Precedence](#configuration-precedence)
- [YAML Syntax and Structure](#yaml-syntax-and-structure)
- [Example Configurations](#example-configurations)
- [Migration from Properties to YAML](#migration-from-properties-to-yaml)
- [Best Practices](#best-practices)

## Overview

Starting with version 0.3.2, OJP supports YAML configuration files in addition to the traditional Java Properties format. YAML provides a more readable and structured way to configure OJP, especially for complex configurations.

**Key Benefits of YAML:**
- More readable, hierarchical structure
- Support for nested configuration
- Better for complex configurations
- Easier to comment and document
- Lists and arrays are more natural

**Backward Compatibility:**
- All existing `.properties` files continue to work
- You can use both formats in the same environment
- No code changes required to switch formats

## Configuration File Formats

### Supported File Extensions

- **YAML**: `.yaml`, `.yml`
- **Properties**: `.properties`

### File Naming Conventions

Both formats support environment-specific configuration:

- **Base configuration**: `ojp.yaml` or `ojp.properties`
- **Environment-specific**: `ojp-{environment}.yaml` or `ojp-{environment}.properties`
  - Examples: `ojp-dev.yaml`, `ojp-prod.yaml`, `ojp-staging.yaml`

The environment is determined by:
1. System property: `-Dojp.environment=dev`
2. Environment variable: `OJP_ENVIRONMENT=dev`

## Configuration File Loading Order

OJP attempts to load configuration files in the following order (first found wins):

1. `ojp-{environment}.yaml` (if environment is set)
2. `ojp-{environment}.yml` (if environment is set)
3. `ojp.yaml`
4. `ojp.yml`
5. `ojp-{environment}.properties` (if environment is set)
6. `ojp.properties`

**Example:**
If you set `-Dojp.environment=prod`:
1. OJP first looks for `ojp-prod.yaml`
2. If not found, tries `ojp-prod.yml`
3. If not found, tries `ojp.yaml`
4. If not found, tries `ojp.yml`
5. If not found, tries `ojp-prod.properties`
6. Finally tries `ojp.properties`

## Configuration Precedence

Configuration values are resolved in the following order (highest to lowest priority):

### Server Configuration

1. **JVM System properties** (e.g., `-Dojp.server.port=1059`)
   - Passed via command line arguments to the JVM

2. **Environment variables** (e.g., `OJP_SERVER_PORT=1059`)
   - Convert property names: dots to underscores, lowercase to uppercase
   - Example: `ojp.server.port` → `OJP_SERVER_PORT`

3. **Configuration files** (YAML or Properties)
   - Loaded from classpath

4. **Default values**
   - Hard-coded defaults in the application

### Client/Driver Configuration

**Note:** The JDBC driver uses a different precedence order for backward compatibility:

1. **Environment variables** (highest priority)
2. **JVM System properties**
3. **Configuration files** (YAML or Properties)
4. **Default values** (lowest priority)

### Precedence Examples

**Server Configuration:**
Given the following:
- File `ojp.yaml`: `ojp.server.port: 1059`
- Environment variable: `OJP_SERVER_PORT=2059`
- System property: `-Dojp.server.port=3059`

The actual value used will be `3059` (system property wins).

**Client Configuration:**
Given the following:
- File `ojp.yaml`: `ojp.connection.pool.enabled: true`
- System property: `-Dojp.connection.pool.enabled=false`
- Environment variable: `OJP_CONNECTION_POOL_ENABLED=true`

The actual value used will be `true` (environment variable wins).

## YAML Syntax and Structure

### Basic YAML Syntax

YAML uses indentation (2 or 4 spaces) to denote structure:

```yaml
# Properties format
ojp.server.port=1059
ojp.server.threadPoolSize=200
ojp.server.logLevel=INFO

# Equivalent YAML format
ojp:
  server:
    port: 1059
    threadPoolSize: 200
    logLevel: INFO
```

### Nested Configuration

YAML excels at representing nested configuration:

```yaml
ojp:
  server:
    tls:
      enabled: true
      keystore:
        path: /etc/ojp/ssl/server.jks
        password: changeit
        type: JKS
      truststore:
        path: /etc/ojp/ssl/truststore.jks
        password: changeit
        type: JKS
      clientAuthRequired: true
```

This is equivalent to:
```properties
ojp.server.tls.enabled=true
ojp.server.tls.keystore.path=/etc/ojp/ssl/server.jks
ojp.server.tls.keystore.password=changeit
ojp.server.tls.keystore.type=JKS
ojp.server.tls.truststore.path=/etc/ojp/ssl/truststore.jks
ojp.server.tls.truststore.password=changeit
ojp.server.tls.truststore.type=JKS
ojp.server.tls.clientAuthRequired=true
```

### Lists and Arrays

YAML has native list support:

```yaml
ojp:
  server:
    allowedIps:
      - 192.168.1.0/24
      - 10.0.0.0/8
      - 172.16.0.0/12
```

Equivalent properties format:
```properties
ojp.server.allowedIps=192.168.1.0/24,10.0.0.0/8,172.16.0.0/12
```

### Comments

```yaml
# This is a comment in YAML
ojp:
  server:
    port: 1059  # Inline comment
    # Multi-line comments
    # can span multiple lines
    threadPoolSize: 200
```

### Boolean Values

YAML supports various boolean representations:

```yaml
ojp:
  server:
    tls:
      enabled: true      # or: yes, on, TRUE
      clientAuthRequired: false  # or: no, off, FALSE
```

## Example Configurations

### Server Configuration

See [ojp-server-example.yaml](ojp-server-example.yaml) for a complete server configuration example.

Key sections:
```yaml
ojp:
  server:
    # Basic settings
    port: 1059
    threadPoolSize: 200
    logLevel: INFO
    
    # Circuit breaker
    circuitBreakerTimeout: 60000
    circuitBreakerThreshold: 3
    
    # Slow query segregation
    slowQuerySegregation:
      enabled: true
      slowSlotPercentage: 20
      idleTimeout: 10000
    
    # TLS/mTLS
    tls:
      enabled: true
      keystore:
        path: /etc/ojp/ssl/server.jks
        password: changeit
      clientAuthRequired: true
```

### Client Configuration

See [ojp-client-example.yaml](ojp-client-example.yaml) for a complete client configuration example.

Key sections:
```yaml
ojp:
  client:
    tls:
      enabled: true
      keystore:
        path: /etc/app/tls/client-keystore.jks
        password: clientpass
        type: JKS
      truststore:
        path: /etc/app/tls/client-truststore.jks
        password: trustpass
        type: JKS
  
  grpc:
    maxInboundMessageSize: 16777216  # 16 MB
```

### Multi-Datasource Configuration

```yaml
# Default datasource configuration
ojp:
  connection:
    pool:
      enabled: true
      maxPoolSize: 10
      minIdle: 2

# Application-specific datasource
myapp:
  ojp:
    connection:
      pool:
        enabled: true
        maxPoolSize: 20
        minIdle: 5
        maxWaitMillis: 30000
```

## Migration from Properties to YAML

### Step-by-Step Migration

1. **Keep the properties file** initially for fallback
2. **Create a YAML file** with the same name (e.g., `ojp-prod.yaml`)
3. **Convert property keys** to nested YAML structure
4. **Test the configuration** to ensure it works
5. **Remove the properties file** once confident

### Conversion Rules

1. **Dot notation to nesting:**
   ```properties
   ojp.server.port=1059
   ```
   becomes:
   ```yaml
   ojp:
     server:
       port: 1059
   ```

2. **Comma-separated lists to YAML lists:**
   ```properties
   ojp.server.allowedIps=192.168.1.0/24,10.0.0.0/8
   ```
   becomes:
   ```yaml
   ojp:
     server:
       allowedIps:
         - 192.168.1.0/24
         - 10.0.0.0/8
   ```

3. **Boolean values remain the same:**
   ```properties
   ojp.server.tls.enabled=true
   ```
   becomes:
   ```yaml
   ojp:
     server:
       tls:
         enabled: true
   ```

### Automated Conversion

You can use online tools or scripts to help convert properties to YAML:
- Many IDEs have built-in conversion tools
- Online converters are available (search for "properties to YAML converter")

**Note:** Always validate the converted YAML file before using in production.

## Best Practices

### 1. Use Environment-Specific Files

```
src/main/resources/
├── ojp-dev.yaml       # Development settings
├── ojp-staging.yaml   # Staging settings
└── ojp-prod.yaml      # Production settings
```

### 2. Keep Secrets Out of YAML Files

Don't store passwords directly in YAML files. Use environment variables:

```yaml
# Bad - password in file
ojp:
  server:
    tls:
      keystore:
        password: mysecretpassword

# Good - omit password from YAML, provide via environment variable
ojp:
  server:
    tls:
      keystore:
        # password not specified in YAML file
        # Will be overridden by environment variable OJP_SERVER_TLS_KEYSTORE_PASSWORD
```

Then set the actual value via environment variable or system property:
```bash
# Using environment variable (recommended for production)
export OJP_SERVER_TLS_KEYSTORE_PASSWORD=mysecretpassword

# Or using system property
java -Dojp.server.tls.keystore.password=mysecretpassword -jar app.jar
```

**Note:** OJP does not currently support variable interpolation (like `${VARIABLE}`) within YAML files. Always provide sensitive values through environment variables or system properties, which will override any values in the YAML file.

### 3. Document Your Configuration

Use comments generously:

```yaml
ojp:
  server:
    # Maximum number of concurrent threads
    # Recommended: 2x CPU cores for CPU-bound operations
    #              100-200 for I/O-bound operations
    threadPoolSize: 200
    
    # Enable slow query segregation to prevent slow queries
    # from blocking fast queries
    slowQuerySegregation:
      enabled: true
      # Percentage of threads reserved for slow queries
      slowSlotPercentage: 20
```

### 4. Use Consistent Indentation

Always use 2 spaces (recommended) or 4 spaces consistently:

```yaml
# Good - consistent 2-space indentation
ojp:
  server:
    port: 1059
    
# Bad - mixed indentation
ojp:
    server:
      port: 1059
```

### 5. Validate YAML Syntax

YAML is sensitive to indentation and special characters. Use a YAML validator:
- Online: yamllint.com
- CLI: `yamllint ojp.yaml`
- IDE: Most IDEs have built-in YAML validation

### 6. Version Control Your Configuration

- Keep configuration files in version control
- Use `.gitignore` for files with secrets
- Document any environment-specific setup needed

### 7. Test Configuration Changes

After changing configuration:
1. Validate YAML syntax
2. Test in a non-production environment first
3. Monitor logs for configuration loading messages
4. Verify the expected values are being used

## Troubleshooting

### Configuration Not Loading

Check the logs for messages like:
```
INFO: Loaded environment-specific YAML configuration from ojp-prod.yaml for environment: prod
```

If not loading:
1. Verify the file is in the classpath (`src/main/resources/`)
2. Check file name matches the environment setting
3. Validate YAML syntax
4. Check file permissions

### Wrong Values Being Used

Remember the precedence order:
1. Environment variables (highest priority)
2. System properties
3. Configuration files
4. Defaults (lowest priority)

Check for environment variables or system properties that might be overriding your file values.

### YAML Parsing Errors

Common issues:
- **Indentation**: Use spaces, not tabs
- **Colons**: Need a space after colon (`key: value`, not `key:value`)
- **Quotes**: Use quotes for strings with special characters
- **Boolean values**: Use `true`/`false`, not `yes`/`no` for consistency

## Additional Resources

- [Example Server Configuration](ojp-server-example.yaml)
- [Example Client Configuration](ojp-client-example.yaml)
- [mTLS Configuration Guide](mtls-configuration-guide.md)
- [OJP Server Configuration Documentation](ojp-server-configuration.md)
- [OJP JDBC Driver Configuration Documentation](ojp-jdbc-configuration.md)
