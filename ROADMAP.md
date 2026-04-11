# OJP Roadmap

This document outlines the planned releases and key milestones for the Open J Proxy project.

---

## 🚀 Version 0.4.0-beta — Mid-March 2026

**Theme: Service Provider Interfaces & Query Enhancement**

- Full implementation of OJP Service Provider Interfaces (SPIs), enabling custom connection pool providers and extensibility hooks
- Spring Boot integration via spring-boot-starter-ojp with automatic datasource configuration
- Official TestContainers integration module for reproducible integration testing
- Mutual TLS (mTLS) support between the JDBC driver and OJP server
- Expanded observability: additional OpenTelemetry metrics and distributed tracing spans
- Improved developer experience: refined configuration, better error messages, and expanded documentation
- Enhanced test coverage and integration testing infrastructure
- Experimental integration with [Apache Calcite](https://calcite.apache.org/) for SQL query optimization (disabled by default)


---

## 🔄 Version 0.5.0-beta — June/July 2026

**Theme: Read/Write Segregation & Caching**

- Read/write segregation support: route read queries to replicas and write queries to primary nodes automatically
- Query result caching layer to reduce database load for repeated read operations
- Configuration-driven cache invalidation and TTL policies
- Expanded multinode support leveraging read/write topology awareness

---

## 🎯 Version 1.0.0 — September/October 2026

**Theme: Production Ready**

- First stable, production-grade release — no longer beta
- Full SPI ecosystem with stable public APIs
- Performance benchmarks and tuning guides
- Comprehensive documentation covering all features, deployment patterns, and upgrade paths
- Long-term support (LTS) commitment begins

---

## 💡 Future Considerations (post 1.0.0)

Items under consideration for future releases:

- Native reactive/non-blocking driver support
- gRPC streaming improvements for high-throughput workloads
- Kubernetes operator for automated OJP cluster management
- Support for additional connection pool providers via SPI

---

> 📣 Want to influence the roadmap? Open a [GitHub Discussion](https://github.com/Open-J-Proxy/ojp/discussions) or join us on [Discord](https://discord.gg/J5DdHpaUzu).
