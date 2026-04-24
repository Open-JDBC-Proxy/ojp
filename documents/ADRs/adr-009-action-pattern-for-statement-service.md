# ADR 009: Action Pattern for StatementServiceImpl

In the context of the OJP project,  
facing a 2,528-line `StatementServiceImpl` God class that made unit testing, parallel development, and code review difficult,  

we decided to decompose it into stateless, singleton **Action classes** — one per gRPC operation — coordinated by a shared **ActionContext** data-holder,  
and neglected keeping logic inline, restructuring through inheritance, or introducing a dependency-injection framework,  

to achieve independent testability and focused code reviews with no merge conflicts on a shared file,  
accepting a larger number of source files and the need for contributors to follow the stateless-singleton contract,  

because stateless singletons are naturally thread-safe, carry zero per-request allocation overhead, and keep the hot path free from GC pressure.

## Context

`StatementServiceImpl` grew to 2,528 lines with 21 public methods spanning connection management, SQL execution, transaction control, XA distributed transactions, and LOB streaming. Every unit test required wiring the entire class, concurrent PRs conflicted on the same file, and reviewers had to navigate unrelated logic in a single compilation unit.

## Decision

`StatementServiceImpl` becomes a thin delegator. Each gRPC method is extracted into a focused Action class that holds no state and receives all context via parameters. A single `ActionContext` object, created once at startup, carries all shared infrastructure (datasource maps, session manager, circuit breaker, server configuration, XA registries).

Four Action interfaces cover every method shape in the service: standard unary RPCs, bidirectional streaming, server-initialisation operations, and internal helpers that return a value. All implementations follow the stateless-singleton pattern.

## Consequences

**Positive**: each Action is independently testable; contributors work on separate classes without merge conflicts; code reviews are focused on ~75–150 line units; singletons eliminate per-request allocation on the hot path.

**Negative**: 30+ source files instead of one; contributors must respect the stateless contract; `ActionContext` must be kept lean to avoid becoming a second God object.

## Alternatives Considered

- **Private helper methods in `StatementServiceImpl`** — reduces line count superficially but keeps all logic in one compilation unit.
- **Inheritance / abstract base class** — Java's single-inheritance constraint limits flexibility and obscures control flow.
- **Dependency injection (Spring/Guice)** — heavyweight dependency for a problem solved by the stateless-singleton pattern.

## Related Decisions

- **ADR-006**: SPI pattern — same "stateless, discoverable handler" philosophy at the pool-provider level.

## References

- [Action Pattern Migration Guide](../designs/STATEMENTSERVICE_ACTION_PATTERN_MIGRATION.md)
- [Architecture Chapter 2, Section 2.5](../ebook/part1-chapter2-architecture.md)
- Reference implementation: [PR #214](https://github.com/Open-J-Proxy/ojp/pull/214)

| Status        | APPROVED           |
|---------------|--------------------|
| Proposer(s)   | Rogerio Robetti    |
| Proposal date | 01/01/2026         |
| Approver(s)   | Rogerio Robetti    |
| Approval date | 01/01/2026         |
