
# Open J Proxy

![Release](https://img.shields.io/github/v/release/Open-J-Proxy/ojp?include_prereleases)
[![Main CI](https://github.com/Open-J-Proxy/ojp/actions/workflows/main.yml/badge.svg)](https://github.com/Open-J-Proxy/ojp/actions/workflows/main.yml)
[![Spring Boot/Micronaut/Quarkus Integration](https://github.com/Open-J-Proxy/ojp-framework-integration/actions/workflows/main.yml/badge.svg)](https://github.com/Open-J-Proxy/ojp-framework-integration/actions/workflows/main.yml)

A JDBC Driver and Layer 7 Proxy Server to decouple applications from relational database connection management.

[![Discord](https://img.shields.io/discord/1385189361565433927?label=Discord&logo=discord)](https://discord.gg/J5DdHpaUzu)


---

<img src="documents/images/ojp_logo.png" alt="OJP Banner" />


[!["Buy Me A Coffee"](https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png)](https://buymeacoffee.com/wqoejbve8z)

---

## Value Proposition

OJP protects your databases from overwhelming connection storms by acting as a smart backpressure mechanism. Instead of every application instance opening and holding connections, OJP orchestrates and optimizes database access through intelligent pooling, query flow control, and multi-database support. With minimal configuration changes, you replace native JDBC drivers gaining connection resilience, and safer scalability. Elastic scaling becomes simpler without putting your database at risk.

---
## Quick Start

Get OJP running in under 5 minutes:

### 1. Start OJP Server
```bash
docker run --rm -d --network host rrobetti/ojp:0.2.0-beta
```

### 2. Add OJP JDBC Driver to your project
```xml
<dependency>
    <groupId>org.openjproxy</groupId>
    <artifactId>ojp-jdbc-driver</artifactId>
    <version>0.2.0-beta</version>
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

### Further documents
- [Architectural decision records (ADRs)](documents/ADRs) - Technical decisions and rationale behind OJP's architecture.
- [Get started: Spring Boot, Quarkus and Micronaut](documents/java-frameworks) - Framework-specific integration guides and examples.
- [Connection Pool Configuration](documents/configuration/ojp-jdbc-configuration.md) - OJP JDBC driver setup and connection pool settings.
- [OJP Server Configuration](documents/configuration/ojp-server-configuration.md) - Server startup options and runtime configuration.
- [Multinode Configuration](documents/multinode/README.md) - High availability and load balancing with multiple OJP servers.
- [Slow query segregation feature](documents/designs/SLOW_QUERY_SEGREGATION.md) - Feature that prevent connection starvation by slow queries (or statements).
- [Telemetry and Observability](documents/telemetry/README.md) - OpenTelemetry integration and monitoring setup.
- [OJP Components](documents/OJPComponents.md) - Core modules that define OJP’s architecture, including the server, JDBC driver, and shared gRPC contracts.
- [OJP Integration Tests](../ojp-framework-integration/README.md) - Integration tests of OJP with the main Java frameworks
- [Targeted Problem and Solution](documents/targeted-problem/README.md) - Explanation of the problem OJP solves and how it addresses it.
- [BigDecimal Wire Format](documents/protocol/BIGDECIMAL_WIRE_FORMAT.md) - Protocol specification for language-neutral BigDecimal serialization.

---

## Vision
Provide a free and open-source solution for a relational database-agnostic proxy connection pool. The project is designed to help efficiently manage database connections in microservices, event-driven architectures, or serverless environments while maintaining high scalability and performance.

---

## Contributing & Developer Guide

Welcome to OJP! We appreciate your interest in contributing. This guide will help you get started with development.
- [OJP Contributor Recognition Program](documents/contributor-badges/contributor-recognition-program.md) - OJP Contributor Recognition rewards program and badges recognize more than code contributions, check it out!
- [Source code developer setup and local testing](documents/code-contributions/setup_and_testing_ojp_source.md) - Outlines how to get started building OJP source code locally and running tests.

---

## Partners
<a href=https://www.linkedin.com/in/devsjava/>
<img width="150px" height="150px" src="documents/images/comunidade_brasil_jug.jpeg" alt="Comunidade Brasil JUG" />
</a>
<a href=https://github.com/switcherapi>
<img width="180px" src="documents/images/switcherapi_grey.png" alt="Comunidade Brasil JUG" />
</a>
