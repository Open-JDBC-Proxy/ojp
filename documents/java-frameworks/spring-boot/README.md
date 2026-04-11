# Spring Boot

OJP provides a Spring Boot Starter (`spring-boot-starter-ojp`) that auto-configures the OJP JDBC driver
in Spring Boot 4.x projects, making integration as simple as adding a dependency and setting a single
connection URL property.

> **Requirements:** Spring Boot 4.x (Java 17+). The starter also works with Spring Boot 3.x.
> For Java 11 projects, follow the [manual configuration](#manual-configuration-spring-boot-3x--java-11) guide below.

---

## Quickstart with the Spring Boot Starter (Recommended)

### 1. Add the OJP starter

Replace any existing `spring-boot-starter-jdbc` in your `pom.xml` with the OJP starter:

```xml
<!-- Add the OJP Spring Boot Starter (replaces spring-boot-starter-jdbc) -->
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>spring-boot-starter-ojp</artifactId>
    <version>0.4.1-beta</version>
</dependency>
```

> **Why replace the JDBC starter?** OJP manages connection pooling centrally on the proxy server.
> The OJP Spring Boot Starter includes the necessary Spring JDBC support without a local connection
> pool, and automatically configures `SimpleDriverDataSource` to create connections on demand.

### 2. Set your connection URL in `application.properties`

```properties
# OJP connection URL: jdbc:ojp[<ojp-host>:<ojp-port>]_<actual-driver-url>
spring.datasource.url=jdbc:ojp[localhost:1059]_postgresql://user@localhost/mydb
spring.datasource.username=user
spring.datasource.password=secret
```

That is all! The starter automatically sets:
- `spring.datasource.driver-class-name=org.openjproxy.jdbc.Driver`
- `spring.datasource.type=org.springframework.jdbc.datasource.SimpleDriverDataSource`

### 3. Optional: Fine-tune the OJP server-side connection pool

OJP's connection pool lives on the proxy server. You can tune it directly from `application.properties`:

```properties
# OJP server-side connection pool settings (forwarded to the OJP server via gRPC)
ojp.connection.pool.maximum-pool-size=20
ojp.connection.pool.minimum-idle=5
ojp.connection.pool.connection-timeout=30000
ojp.connection.pool.idle-timeout=600000
ojp.connection.pool.max-lifetime=1800000

# gRPC transport settings (increase for large LOB data)
ojp.grpc.max-inbound-message-size=16777216
```

> **Tip — Named datasource:** To use a named datasource pool configuration on the server, embed the
> name directly in the JDBC URL using parentheses:
> `spring.datasource.url=jdbc:ojp[localhost:1059(myApp)]_postgresql://...`

### 4. Start the OJP Server

The OJP proxy server must be running and reachable at the host/port in the JDBC URL.

```bash
# Docker (recommended)
docker run -d --network host \
  -v $(pwd)/ojp-libs:/opt/ojp/ojp-libs \
  rrobetti/ojp:latest
```

For full server setup options see the [Server Configuration Guide](../../configuration/ojp-server-configuration.md).

---

## Manual Configuration (Spring Boot 3.x / Java 11)

If you cannot use the starter (e.g., Java 11 projects), follow these steps:

### 1. Add the JDBC driver dependency

```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>0.4.1-beta</version>
</dependency>
```

### 2. Remove the local connection pool

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
    <exclusions>
        <!--When using OJP proxied connection pool the local pool needs to be removed -->
        <exclusion>
            <groupId>com.zaxxer</groupId>
            <artifactId>HikariCP</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

### 3. Configure `application.properties`

```properties
spring.datasource.url=jdbc:ojp[localhost:1059]_h2:~/test
spring.datasource.driver-class-name=org.openjproxy.jdbc.Driver
# SimpleDriverDataSource creates and closes connections on demand without local pooling
spring.datasource.type=org.springframework.jdbc.datasource.SimpleDriverDataSource
```

The OJP URL pattern is `jdbc:ojp[host:port]_<actualDriver>://...`. You just need to prepend
`ojp[host:port]_` immediately after `jdbc:` in your existing URL.

---

## Troubleshooting

### Logging Configuration

As of OJP version 0.3.2, the logging implementation has been updated to be compatible with Spring Boot's default logging framework, Logback.

**What Changed:**
- **OJP JDBC Driver**: No longer bundles any SLF4J implementation. It only uses the SLF4J API with `provided` scope, allowing the consuming application to choose the logging implementation.
- **OJP Server**: Uses Logback as the logging implementation with configurable options.

**Benefits:**
- ✅ No more logging conflicts when using OJP JDBC driver with Spring Boot
- ✅ Seamless integration with Spring Boot's existing logging configuration
- ✅ The consuming application (like your Spring Boot app) provides the logging implementation
- ✅ Consistent logging across your entire application

**OJP Server Logging Configuration:**

For detailed information about configuring OJP Server logging (log levels, file locations, rotation policies), see the [OJP Server Configuration Guide](../../configuration/ojp-server-configuration.md#logging-settings).

**For older versions (0.3.2-beta and earlier):**

If you're using an older version of OJP, you may encounter a conflict because the OJP JDBC driver bundled SLF4J Simple, which conflicts with Spring Boot's default Logback implementation.

The error typically looks like this:

```shell
SLF4J(W): Class path contains multiple SLF4J providers.
SLF4J(W): Found provider [org.slf4j.simple.SimpleServiceProvider@75412c2f]
SLF4J(W): Found provider [ch.qos.logback.classic.spi.LogbackServiceProvider@282ba1e]
Exception in thread "main" java.lang.IllegalStateException: LoggerFactory is not a Logback LoggerContext...
```

**Solution for older versions:**

Option 1 (Recommended): Upgrade to OJP 0.3.2 or later, which has this issue resolved.

Option 2: If you must use an older version, you can work around the issue by adding a JVM argument:
```shell
JAVA_OPTS="-Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider"
```

