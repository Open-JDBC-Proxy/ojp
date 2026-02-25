
# Open J Proxy

![Release](https://img.shields.io/github/v/release/Open-J-Proxy/ojp?include_prereleases) [![Main CI](https://github.com/Open-J-Proxy/ojp/actions/workflows/main.yml/badge.svg)](https://github.com/Open-J-Proxy/ojp/actions/workflows/main.yml) [![Spring Boot/Micronaut/Quarkus Integration](https://github.com/Open-J-Proxy/ojp-framework-integration/actions/workflows/main.yml/badge.svg)](https://github.com/Open-J-Proxy/ojp-framework-integration/actions/workflows/main.yml) [![License](https://img.shields.io/github/license/Open-J-Proxy/ojp.svg)](https://raw.githubusercontent.com/Open-J-Proxy/ojp/master/LICENSE)

[![security status](https:&#x2F;&#x2F;www.meterian.com/badge/gh/Open-J-Proxy/ojp/security?branch=main)](https:&#x2F;&#x2F;www.meterian.com/report/gh/Open-J-Proxy/ojp) [![stability status](https:&#x2F;&#x2F;www.meterian.com/badge/gh/Open-J-Proxy/ojp/stability?branch=main)](https:&#x2F;&#x2F;www.meterian.com/report/gh/Open-J-Proxy/ojp)

Website 👉 [openjproxy.com](https://openjproxy.com) 

Follow us on LinkedIn 👉 [Open J Proxy](https://www.linkedin.com/company/open-j-proxy) 

---

A type 3 JDBC Driver and Layer 7 Proxy Server to decouple applications from relational database connection management.

_"The only open-source JDBC Type 3 driver globally, this project introduces a transparent Quality-of-Service layer that decouples application performance from database bottlenecks. It's a must-try for any team struggling with data access contention, offering easy-to-implement back-pressure and pooling management." (Bruno Bossola - Java Champion and CTO @ Meterian.io)_  


[![Discord](https://img.shields.io/discord/1385189361565433927?label=Discord&logo=discord)](https://discord.gg/J5DdHpaUzu)


---

<img src="documents/images/ojp_logo.png" alt="OJP Banner" />


[!["Buy Me A Coffee"](https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png)](https://buymeacoffee.com/wqoejbve8z)

---

## Value Proposition

OJP protects your databases from overwhelming connection storms by acting as a smart backpressure mechanism. Instead of every application instance opening and holding connections, OJP orchestrates and optimizes database access through intelligent pooling, query flow control, and multi-database support. With minimal configuration changes, you replace native JDBC drivers gaining connection resilience, and safer scalability. Elastic scaling becomes simpler without putting your database at risk.

---
## Requirements

- **OJP JDBC Driver**: Java 11 or higher
- **OJP Server**: Java 21 or higher

---
## Quick Start

Get OJP running in under 5 minutes:

### 1. Start OJP Server (Docker)

> **⚠️ Important for v0.4.0-beta and later:** JDBC drivers must be downloaded and mounted. See [Chapter 4: Database Drivers](documents/ebook/part2-chapter4-database-drivers.md) for details.

```bash
# Download drivers first
mkdir -p ojp-libs
cd ojp-server
bash download-drivers.sh ../ojp-libs
cd ..

# Run with drivers mounted
docker run --rm -d \
  --network host \
  -v $(pwd)/ojp-libs:/opt/ojp/ojp-libs \
  rrobetti/ojp:0.3.2-beta
```

**Alternative: Runnable JAR (No Docker)**

```bash
# Download OJP Server JAR and open source drivers
cd ojp-server
bash download-drivers.sh  # Downloads H2, PostgreSQL, MySQL, MariaDB to ojp-libs/
java -jar ojp-server-0.3.2-snapshot-shaded.jar
```

📖 See [Executable JAR Setup Guide](documents/runnable-jar/README.md) for details.

### 2. Add OJP JDBC Driver to your project
```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>0.3.2-beta</version>
</dependency>
```

### 3. Update your JDBC URL
Replace your existing connection URL by prefixing with `ojp[host:port]_`:

```java
// Before (PostgreSQL example)
"jdbc:postgresql://user@localhost/mydb"

// After  
"jdbc:ojp[localhost:1059]_postgresql://user@localhost/mydb"

// Oracle example
"jdbc:ojp[localhost:1059]_oracle:thin:@localhost:1521/XEPDB1"

// SQL Server example
"jdbc:ojp[localhost:1059]_sqlserver://localhost:1433;databaseName=mydb"
```
Use the ojp driver: `org.openjproxy.jdbc.Driver`

That's it! Your application now uses intelligent connection pooling through OJP.

**Note**: For detailed driver setup including proprietary databases (Oracle, SQL Server, DB2), see [Chapter 4: Database Drivers](documents/ebook/part2-chapter4-database-drivers.md).

## Alternative Setup: Executable JAR (No Docker)

If Docker is not available in your environment, you can build and run OJP Server as a standalone JAR file:

📖 **[Executable JAR Setup Guide](documents/runnable-jar/README.md)** - Complete instructions for building and running OJP Server as a standalone executable JAR with all dependencies included.

---

## Documentation
### High Level Solution

<img src="documents/designs/ojp_high_level_design.gif" alt="OJP High Level Design" />

* The OJP JDBC driver is used as a replacement for the native JDBC driver(s) previously used with minimal change, the only change required being prefixing the connection URL with `ojp_`. 
* **Open Source**: OJP is an open-source project that is free to use, modify, and distribute.
* The OJP server is deployed as an independent service that serves as a smart proxy between the application(s) and their respective relational database(s), controlling the number of connections open against each database.
* **Smart Connection Management**: The proxy ensures that database connections are allocated only when needed, improving scalability and resource utilization.
* **Elastic Scalability**: OJP allows client applications to scale elastically without increasing the pressure on the database.
* **gRPC Protocol** is used to facilitate the connection between the OJP JDBC Driver and the OJP Server, allowing for efficient data transmission over a multiplexed channel.
* OJP Server uses **HikariCP** connection pools to efficiently manage connections.
* OJP supports **multiple relational databases** - in theory it can support any relational database that provides a JDBC driver implementation.
* OJP simple setup just requires the OJP library in the classpath and the OJP prefix added to the connection URL (e.g., `jdbc:ojp[host:port]_h2:~/test` where `host:port` represents the location of the OJP server).
* **Drop-In External Libraries**: Add proprietary JDBC drivers (Oracle, SQL Server, DB2) and additional libraries (e.g., Oracle UCP) without recompiling - see [Drop-In Driver Documentation](documents/configuration/DRIVERS_AND_LIBS.md). Simply place JARs in the `ojp-libs` directory.
* **SQL Query Enhancement**: ⚠️ **EXPERIMENTAL (NOT RECOMMENDED)** - Optional SQL enhancer with Apache Calcite for query optimization. **Disabled by default.** Has known limitations with traditional JDBC databases (PostgreSQL, MySQL, Oracle, SQL Server). See [configuration documentation](documents/configuration/ojp-server-configuration.md#sql-enhancer-and-schema-loader-settings) for details.

### Further documents
- [Docker Deployment Guide](documents/configuration/DOCKER_DEPLOYMENT.md) - Comprehensive guide for deploying OJP Server with Docker, including JVM parameter configuration, production examples, and troubleshooting.
- [Drop-In External Libraries Support](documents/configuration/DRIVERS_AND_LIBS.md) - Add proprietary database drivers and libraries (Oracle JDBC, Oracle UCP, SQL Server, DB2) without recompiling.
- [SSL/TLS Certificate Configuration Guide](documents/configuration/ssl-tls-certificate-placeholders.md) - Configure SSL/TLS certificates with server-side property placeholders for PostgreSQL, MySQL, Oracle, SQL Server, and DB2.
- [Architectural decision records (ADRs)](documents/ADRs) - Technical decisions and rationale behind OJP's architecture.
- [Get started: Spring Boot, Quarkus and Micronaut](documents/java-frameworks/README.md) - Framework-specific integration guides and examples.
- [Understanding OJP Service Provider Interfaces (SPIs)](documents/Understanding-OJP-SPIs.md) - Guide for Java developers on implementing custom connection pool providers.
- [Connection Pool Configuration](documents/configuration/ojp-jdbc-configuration.md) - OJP JDBC driver setup, connection pool settings, and environment-specific configuration (ojp-dev.properties, ojp-staging.properties, ojp-prod.properties).
- [OJP Server Configuration](documents/configuration/ojp-server-configuration.md) - Server startup options, runtime configuration, and SQL enhancer with schema loading.
- [Multinode Configuration](documents/multinode/README.md) - High availability and load balancing with multiple OJP servers.
- [Slow query segregation feature](documents/designs/SLOW_QUERY_SEGREGATION.md) - Feature that prevent connection starvation by slow queries (or statements).
- [Telemetry and Observability](documents/telemetry/README.md) - OpenTelemetry integration and monitoring setup.
- [OJP Components](documents/OJPComponents.md) - Core modules that define OJP’s architecture, including the server, JDBC driver, and shared gRPC contracts.
- [Targeted Problem and Solution](documents/targeted-problem/README.md) - Explanation of the problem OJP solves and how it addresses it.
- [BigDecimal Wire Format](documents/protocol/BIGDECIMAL_WIRE_FORMAT.md) - Protocol specification for language-neutral BigDecimal serialization.

---

## Vision
Provide a free and open-source solution for a relational database-agnostic proxy connection pool. The project is designed to help efficiently manage database connections in microservices, event-driven architectures, or serverless environments while maintaining high scalability and performance.

---

## Roadmap

See [ROADMAP.md](ROADMAP.md) for planned releases and upcoming features, including the path to 1.0.0 (production ready).

---

## Contributing & Developer Guide

Welcome to OJP! We appreciate your interest in contributing. This guide will help you get started with development.
- [OJP Contributor Recognition Program](documents/contributor-badges/contributor-recognition-program.md) - OJP Contributor Recognition rewards program and badges recognize more than code contributions, check it out!
- [Source code developer setup and local testing](documents/code-contributions/setup_and_testing_ojp_source.md) - Outlines how to get started building OJP source code locally and running tests.

---

## Partners

| Logo                                                                                                                                                                                                                        | Description                                                                                                                                | Website |
|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|---------|
| <a href="https://www.linkedin.com/in/devsjava/" target="_blank" rel="noopener"><img width="120px" height="120px" src="documents/images/comunidade_brasil_jug.jpeg" alt="Comunidade Brasil JUG" /></a>                       | Brazilian Java User Group connecting developers for knowledge sharing and professional networking.                                         | [linkedin.com/in/devsjava](https://www.linkedin.com/in/devsjava/) |
| <a href="https://github.com/switcherapi" target="_blank" rel="noopener"><img width="180px" src="https://github.com/switcherapi/switcherapi-assets/blob/master/logo/switcherapi_grey.png?raw=true" alt="Switcher API" /></a> | Feature management platform for managing features at scale with performance focus.                                                         | [github.com/switcherapi](https://github.com/switcherapi) |
| <a href="https://www.meterian.io/" target="_blank" rel="noopener"><img width="240px" src="https://www.meterian.io/images/brand/meterian_logo_blue.svg" alt="Meterian"  /></a>                                               | Application security platform that identifies vulnerabilities across open-source dependencies and application code.                        | [meterian.io](https://www.meterian.io/) |
| <a href="https://www.youtube.com/@cbrjar" target="_blank" rel="noopener"><img width="600px" src="/documents/images/cyberjar_logo.png" alt="CyberJAR"  /></a>                                                                | YouTube channel for Java developers covering frameworks, containers, and modern JVM topics.                                                | [youtube.com/@cbrjar](https://www.youtube.com/@cbrjar) |
| <a href="https://javachallengers.com/career-diagnosis" target="_blank" rel="noopener"><img width="150px" src="/documents/images/java_challengers_logo.jpeg" alt="Java Challengers" /></a>                                   | Helps developers go beyond coding, mastering Java fundamentals, building career confidence, and preparing for international opportunities. | [javachallengers.com](https://javachallengers.com/career-diagnosis) |
| <a href="https://omnifish.ee" target="_blank" rel="noopener"><img width="130px" src="/documents/images/omnifish_logo.png" alt="OmniFish" /></a>                                                                             | The team behind Eclipse GlassFish, delivering reliable opensource solutions with enterprise support.                                       | [omnifish.ee](https://omnifish.ee/) |
