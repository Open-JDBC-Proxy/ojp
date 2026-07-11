# OJP Off-Heap / Custom Memory Management Adoption Analysis

**Date:** 2026-07-11  
**Status:** 📋 Analysis / Recommendation  
**Scope:** OJP server + JDBC driver memory strategy (DirectByteBuffer, FFM API, mapped files, binary layouts)

---

## Executive Summary

Short answer: **OJP should not do broad off-heap adoption now**.

A selective path is justified only after OJP proves (with production-like measurements) that:
1. GC/allocation pressure is a real bottleneck, and
2. that bottleneck is in components OJP controls (not mainly JDBC drivers/database/network).

### High-confidence conclusions

- **Already using an efficient baseline:** OJP’s transport stack is gRPC over Netty, which already uses high-performance native I/O paths and direct buffers in practice via Netty transport choices in server/client setup.
- **Current cache strategy is on-heap by design:** OJP intentionally chose Caffeine for fast, bounded, local cache behavior (ADR-008). Caffeine does not provide native off-heap storage.
- **FFM API scope mismatch across modules:** OJP server can use modern Java APIs (Java 25), but OJP JDBC driver must remain Java 11 compatible; this makes cross-project off-heap standardization costly.
- **Best first step is measurement maturity:** OJP already exposes rich latency/pool telemetry, but the project should add explicit GC/allocation/native-memory observability before any off-heap migration.

---

## OJP Context That Matters for This Decision

1. **Runtime split constraints**
   - Driver runtime target: Java 11+
   - Server runtime target: Java 25+
   - This means FFM-native code can be realistically introduced only server-side without compatibility workarounds.

2. **Latency-sensitive architecture is explicit**
   - OJP’s design priorities are low latency, backpressure, and admission control under load.
   - Telemetry docs already emphasize p95/p99 analysis for SQL and connection-acquisition latency.

3. **Memory-efficiency is already being optimized in-code**
   - The action-pattern migration explicitly requires singleton actions to avoid per-request allocation pressure on hot paths.

4. **Caching design is explicit and bounded**
   - Query caching is implemented via Caffeine with entry count and byte-size bounds, TTL, and invalidation.
   - Cached entries store protobuf result objects directly for conversion avoidance.

---

## Technique-by-Technique Evaluation for OJP

## 1) Direct `ByteBuffer` / direct buffer pools

### Fit for OJP
- **Where it could help:** custom binary payload staging in server hot paths (if profiling shows heap-byte[] churn).
- **Where OJP already benefits:** gRPC/Netty stack handles transport buffers efficiently; broad manual direct-buffer rewrites in application code may duplicate what Netty already optimizes.

### Benefits
- Lower heap allocation rate for specific byte-heavy paths.
- Potential p99 improvement for serialization-heavy flows.

### Risks / complexity
- Lifecycle mistakes can cause native memory pressure (`Direct buffer memory` OOM behavior).
- Requires stricter native memory observability and limits (`-XX:MaxDirectMemorySize` discipline).

### Recommendation
- **Adopt only for narrowly profiled hotspots**, not platform-wide.

---

## 2) Foreign Function & Memory API (`MemorySegment`, `Arena`)

### Fit for OJP
- **Best fit:** server-internal modules that are Java 25-only and where explicit lifetime control is valuable (e.g., temporary binary staging or metadata compaction).
- **Poor fit:** JDBC driver module (Java 11 compatibility requirement), and broad business-path code.

### Benefits
- Explicit memory ownership/lifetime (deterministic release with `Arena` scopes).
- Cleaner lifecycle model than ad-hoc JNI/Unsafe code.

### Risks / complexity
- New mental model for contributors (ownership scopes, segment validity).
- Harder debugging and testing burden if introduced in request-critical paths.
- Split-brain implementation risk between server (can use FFM) and driver (cannot).

### Recommendation
- **Do not adopt as a general OJP memory model.**
- Consider only as a server-side, isolated optimization behind small adapters after hard evidence.

---

## 3) Compact binary layouts (offset-based records)

### Fit for OJP
- Could help where OJP keeps large homogeneous in-memory structures (e.g., large metadata/reference datasets), but this is not the current dominant shape of OJP’s architecture.
- OJP request processing is heavily JDBC/protobuf/gRPC object oriented; a broad switch would increase complexity significantly.

### Benefits
- Better memory density and predictable access pattern for specific datasets.

### Risks / complexity
- Higher maintainability risk (offset bugs, schema evolution burden, readability loss).
- Harder onboarding and correctness verification.

### Recommendation
- **Avoid broad adoption.** Use only in tightly bounded utility components if proven beneficial.

---

## 4) Memory-mapped files (`MappedByteBuffer` / mapped segments)

### Fit for OJP
- Useful only if OJP introduces large read-mostly local datasets (routing/rules/indexes) that cannot fit comfortably as heap object graphs.
- Not a clear fit for current core OJP request path, which is primarily live RPC + DB interaction.

### Benefits
- Efficient random access for large local datasets.
- OS page cache advantages for read-heavy workloads.

### Risks / complexity
- File lifecycle/unmapping caveats, platform-specific operational behavior.
- Operational complexity for cache invalidation, warmup, corruption handling.

### Recommendation
- **Not recommended now** for core OJP flow. Revisit only with a concrete large local dataset use case.

---

## Impact vs Risk/Complexity Ranking (OJP-specific)

| Candidate | Potential Impact | Risk / Complexity | Confidence | Decision |
|---|---|---|---|---|
| Add GC/allocation/native-memory observability first | High | Low-Medium | High | **Do first** |
| Targeted direct-buffer optimization in one proven hotspot | Medium | Medium | Medium | **Pilot after instrumentation** |
| Server-only FFM pilot behind adapter | Medium | High | Medium | **Optional later pilot** |
| Binary-layout rewrite of hot domain structures | Medium | High | Medium-Low | **Defer** |
| Memory-mapped files for core OJP path | Low-Medium | High | Medium | **Defer / likely no** |
| Broad off-heap rewrite across OJP | Unknown upside | Very High | High (for risk) | **Reject** |

---

## Recommended Implementation Order

1. **Phase 0 — Measurement hardening (mandatory gate)**
   - Add/standardize metrics for allocation rate, GC pause distribution, and native/direct memory pools.
   - Define a repeatable benchmark profile with p95/p99/p99.9 gates.

2. **Phase 1 — Baseline optimizations before off-heap**
   - Continue reducing avoidable allocations on hot paths (same philosophy already used in singleton action pattern).
   - Validate gains first with current on-heap design.

3. **Phase 2 — Single hotspot pilot with direct buffers**
   - Choose one measured hotspot with byte-heavy churn.
   - Hide implementation behind a narrow interface and keep rollback trivial.

4. **Phase 3 — Optional server-only FFM pilot**
   - Only if Phase 2 shows measurable p99 benefit and maintainability remains acceptable.
   - Keep FFM strictly inside Java-25 server internals; never leak this into driver-facing contracts.

5. **Phase 4 — Re-evaluate binary layout / mapped files**
   - Only with a concrete, validated workload requiring large local read-mostly datasets.

---

## Final Recommendation

For OJP today, the best engineering decision is:

- **Do not pursue broad off-heap adoption.**
- **Invest first in measurement and targeted hotspot optimization.**
- **If off-heap is adopted, keep it surgical, server-only, and adapter-isolated.**

This approach preserves OJP’s current strengths (simplicity, maintainability, Java 11+ driver compatibility, proven telemetry model) while still leaving a safe path for advanced memory techniques where they can deliver real p99 gains.

---

## Sources

### OJP repository sources
- `/home/runner/work/ojp/ojp/README.md` (runtime split, architecture goals)
- `/home/runner/work/ojp/ojp/ojp-server/pom.xml` (server Java 25 target)
- `/home/runner/work/ojp/ojp/pom.xml` (parent Java 11 target baseline)
- `/home/runner/work/ojp/ojp/documents/telemetry/README.md` (p95/p99 and operational metrics)
- `/home/runner/work/ojp/ojp/documents/ADRs/adr-008-use-caffeine-for-caching.md` (Caffeine decision context)
- `/home/runner/work/ojp/ojp/ojp-server/src/main/java/org/openjproxy/grpc/server/cache/QueryResultCache.java`
- `/home/runner/work/ojp/ojp/ojp-server/src/main/java/org/openjproxy/grpc/server/cache/CachedQueryResult.java`
- `/home/runner/work/ojp/ojp/documents/designs/STATEMENTSERVICE_ACTION_PATTERN_MIGRATION.md` (allocation/GC pressure rationale)
- `/home/runner/work/ojp/ojp/ojp-server/src/main/java/org/openjproxy/grpc/server/GrpcServer.java`
- `/home/runner/work/ojp/ojp/ojp-grpc-commons/src/main/java/org/openjproxy/grpc/GrpcChannelFactory.java`

### External references
- OpenJDK JEP 454 (Foreign Function & Memory API): https://openjdk.org/jeps/454
- Oracle JDK API docs for `Arena` / FFM package (Java 25): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/package-summary.html
- Oracle `ByteBuffer` direct-buffer docs (Java SE): https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/nio/ByteBuffer.html
- Caffeine FAQ / project guidance: https://github.com/ben-manes/caffeine/wiki/Frequently-Asked-Questions
- Caffeine off-heap discussion: https://github.com/ben-manes/caffeine/issues/27
- Netty direct-vs-heap buffer FAQ: https://netty.io/wiki/faq.html#wiki-faq-direct-vs-heap-buffer
