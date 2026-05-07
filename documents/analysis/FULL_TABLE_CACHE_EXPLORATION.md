# Full-Table Caching in OJP — Exploration Analysis

> ## ⚠️ Status: Exploration Only (Not Scheduled for Development)
>
> This document is a technical exploration to evaluate feasibility and trade-offs.
> It is **not** an implementation plan, **not** a committed roadmap item, and **not scheduled** for active development.

## 1) Context

OJP currently implements query-result caching (exact SQL + parameter key matching) with table-triggered invalidation on write operations.

Relevant implementation areas:
- `/home/runner/work/ojp/ojp/ojp-server/src/main/java/org/openjproxy/grpc/server/cache/QueryResultCache.java`
- `/home/runner/work/ojp/ojp/ojp-server/src/main/java/org/openjproxy/grpc/server/cache/QueryCacheKey.java`
- `/home/runner/work/ojp/ojp/ojp-server/src/main/java/org/openjproxy/grpc/server/cache/QueryCacheHelper.java`
- `/home/runner/work/ojp/ojp/ojp-server/src/main/java/org/openjproxy/grpc/server/cache/SqlTableExtractor.java`

Current behavior is local-cache oriented (per node), and not a full table-aware in-memory execution layer.

## 2) Problem Being Explored

Explore what would be required for:
1. Caching full tables in OJP memory.
2. Serving reads directly from memory when query predicates target cached table data.
3. Potentially answering joins in-memory when cached tables participate.
4. Returning results immediately without hitting the database when possible.

## 3) Current-State Fit and Gaps

### What already exists
- Fast in-memory cache primitives via Caffeine.
- Query rule matching and TTL configuration.
- Basic table extraction for write invalidation.
- SQL parsing/optimization components (Calcite-based SQL enhancer, marked experimental).

### Major gaps for full-table caching
- No table-snapshot lifecycle model (load/refresh/versioning).
- No in-memory SQL execution engine for table snapshots.
- No robust cross-node table-cache coherence.
- No external-change capture strategy (writes outside OJP path can make cache stale).
- No explicit transaction/isolation semantics for serving table-cache reads.
- Query execution flow currently acquires DB connection before cache hit decision in query action path.

## 4) Architectural Options Explored

### Option A: Extend current result-cache only (no table engine)
Not enough for the requested behavior. This only accelerates repeated identical queries.

### Option B: Add dedicated table-snapshot cache + OJP-side query routing
- Keep existing result-cache as Tier-1 (exact hit).
- Add table cache as Tier-2 (table/row/index structures).
- Route eligible queries to in-memory evaluation before DB access.
- Fall back to DB for unsupported/unsafe cases.

This is the cleanest conceptual fit with current OJP architecture.

### Option C: Embedded in-memory database as cache query engine (H2/Derby/others)
- Can execute SQL against cached table snapshots.
- Reduces need to build custom executor from scratch.
- Introduces SQL dialect compatibility and schema-sync complexity.

## 5) In-Memory Engine Feasibility (H2/Derby/Others)

### H2
Pros:
- Mature embedded engine.
- SQL execution and join support available.
- Already present in OJP test ecosystem.

Cons:
- Dialect/behavior differences vs production databases.
- Additional schema/type mapping and synchronization burden.
- Consistency controls remain OJP’s responsibility.

### Derby
Pros:
- Embedded and stable.

Cons:
- Less attractive ecosystem/performance profile compared to H2 for this use case.

### Calcite path (already in repo, but experimental)
Pros:
- Planner-level flexibility and semantic query analysis potential.

Cons:
- Existing OJP investigation documents known production limitations and type-mapping mismatch risk with traditional JDBC backends.

## 6) How This Could Fit with Existing Cache Solution

Recommended conceptual layering:
- **Tier-1:** current query-result cache (exact SQL + params).
- **Tier-2:** full-table snapshot cache (new component).
- **Router:** pre-DB decision logic:
  - if query fully answerable from Tier-2, return from memory;
  - else fallback to DB and optionally populate Tier-1.

Invalidation/refresh model (required):
- OJP-write invalidation integration (existing hook points can be reused).
- External-write strategy (CDC, notifications, polling, or conservative TTL).
- Per-table versioning to avoid stale joins across mixed table versions.

## 7) Risk Summary

High-risk areas:
- Correctness under concurrent updates and transaction isolation boundaries.
- Cross-node consistency in multinode deployments.
- Memory growth and eviction behavior for large/variable table sizes.
- SQL compatibility if using embedded SQL engines.

## 8) Suggested Exploration Path (Non-Commitment)

If this exploration is revisited later, lowest-risk sequence would be:
1. Prototype reference-table snapshots only (small, low-churn tables).
2. Support single-table predicate reads from memory.
3. Add join support for explicitly whitelisted tables.
4. Add cluster coherence and external-change integration.

Again: this sequence is exploratory guidance only, not a scheduled implementation plan.

## 9) Explicit Non-Roadmap Note

This document records technical exploration findings only.
It does **not** imply approval, prioritization, staffing, milestone commitment, or delivery target.

## 10) Additional Exploration Note: App-Managed H2 Mirror Datasource

An alternative model is to load full tables (or subsets) into an in-memory H2 database and expose that through a separate datasource for applications that explicitly accept staleness.

### Why this can be attractive
- Flexible SQL capability for cached data (joins, aggregations, complex predicates).
- Application-level control of refresh interval and access pattern.
- Reduced load on the primary database for high-read reference data.

### Main concerns
- **Dual-write confusion:** if applications mutate H2 data, those writes are not authoritative unless explicitly synchronized back to source systems.
- **Consistency drift:** staleness is expected, but must be bounded and observable per table/subset.
- **Behavior mismatch:** H2 semantics and type behavior can differ from production databases.
- **Multinode divergence:** each node may hold different snapshots unless refresh/coherence is coordinated.
- **Memory/warmup overhead:** large datasets may increase startup and runtime memory pressure.

### Guardrails if this path is prototyped
- Treat mirrored tables as **read-optimized snapshots**, not source-of-truth tables.
- If writable tables are allowed, classify them clearly as **ephemeral scratch space**.
- Require explicit metadata: refresh cadence, last-refresh timestamp, and staleness budget.
- Start with small/medium, low-churn, high-read tables before wider scope.
- Define deterministic fallback behavior to the primary database on cache cold/expired conditions.
