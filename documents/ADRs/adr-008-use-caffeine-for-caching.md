# ADR 008: Use Caffeine for Query Result Caching

In the context of the OJP query result caching implementation,  
facing the need to cache query results with TTL-based expiration, bounded size, thread-safe concurrent access, and high performance,  

we decided for using **Caffeine** as the caching library  
and neglected Google Guava Cache, EhCache 3, Infinispan, Redis, custom ConcurrentHashMap, and Apache Commons JCS,  

to achieve near-optimal hit rates (within 1% of theoretical maximum), sub-5ms P95 latency, and production-proven reliability,  
accepting the dependency on Caffeine (~900KB JAR),  

because Caffeine delivers 3x faster performance than alternatives (280M vs 93M reads/sec vs Guava), uses Window TinyLFU eviction for optimal hit rates, provides built-in Micrometer metrics integration (aligning with OJP's existing observability), and is battle-tested in Spring Boot, Hibernate, Cassandra, and Elasticsearch.

## Alternatives Considered

### 1. Google Guava Cache
**Rejected**: 3x slower than Caffeine; Google officially recommends Caffeine; in maintenance mode since 2014

### 2. EhCache 3
**Rejected**: Over-engineered for local caching (5MB+ with dependencies); complex XML configuration; designed for distributed caching

### 3. Infinispan
**Rejected**: Massive overkill (10MB+ data grid) for local caching use case; adds unnecessary complexity

### 4. Redis
**Rejected**: External dependency adds network latency (1-5ms); requires separate infrastructure; operational complexity

### 5. Custom ConcurrentHashMap
**Rejected**: Weeks of development to implement TTL, eviction, thread-safe stats; would be suboptimal compared to Caffeine's proven algorithms

### 6. Apache Commons JCS
**Rejected**: Abandoned project (last release 2017); 5-10x slower than Caffeine in benchmarks

## Implementation

Phase 3 wraps Caffeine with `QueryResultCache` class:
- Configuration via `ojp.properties` (maxSize, defaultTtl)
- Automatic Micrometer metrics integration (Phase 10)
- Thread-safe operations with minimal custom code

| Status      | APPROVED        |  
|-------------|-----------------| 
| Proposer(s) | Rogerio Robetti | 
| Proposal date | 24/03/2026      | 
| Approver(s) | Rogerio Robetti |
| Approval date | 24/03/2026      | 
