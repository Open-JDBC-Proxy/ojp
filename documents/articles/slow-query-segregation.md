# Slow Query Segregation in Open J Proxy — What's Changed

## What Is Slow Query Segregation?

Database queries are not all the same. A user-facing lookup by primary key completes in a few milliseconds. A reporting query that aggregates a year of transactions might run for thirty seconds. When both kinds of queries share the same connection pool, the reporting queries win by default — not because they are more important, but because they hold slots longer and are simply harder to displace.

Open J Proxy's Slow Query Segregation (SQS) feature addresses this at the control plane level, before any query touches the connection pool. It partitions the pool's capacity into two semaphore-guarded lanes — a **fast lane** and a **slow lane** — and learns which query shapes belong in each. A flood of long-running analytics queries cannot squeeze out the user-facing lookups, because the semaphores prevent it.

The full introduction to the concept, including the highway-traffic analogy and the original design rationale, was published in the Open J Proxy article [**"The Slow Query Segregation Strategy: Keep Your Fast Operations Fast (and Your Slow Ones Under Control)"**](https://www.linkedin.com/pulse/slow-query-segregation-strategy-keep-your-fast-operations-wfkte/) on LinkedIn. If you are new to SQS, start there. This article picks up where that one left off and covers everything that has evolved since the first release.

---

## What Evolved Since the First Version

### Classification Mode: From RELATIVE_AVERAGE to RELATIVE_FAST_BASELINE

The original SQS implementation classified queries as slow by comparing their average execution time to the **average of all queries** — fast and slow combined. This caused a subtle problem: when a burst of slow queries arrived, their execution times dragged the overall average up, which would push some of those slow queries back below the threshold, which would lower the average again, causing query classifications to flip back and forth. The technical term for this is **mode-flapping**, and it defeated the purpose of the feature during exactly the moments when clear segregation was most needed.

Open J Proxy 0.5.0-beta replaces this mode with a new default: `RELATIVE_FAST_BASELINE`. Instead of averaging all queries, the server computes the threshold using only the queries that are currently in the **fast lane**. Slow-classified queries are excluded from the baseline calculation entirely. This makes the baseline stable during overload: a surge of slow queries cannot move the goalposts that determine whether further queries are considered slow.

The baseline is recomputed at configurable intervals (default: every 10 seconds) by taking all currently-fast query shapes and finding the value at a configurable percentile (default: the 50th percentile — the median). That percentile value becomes the **fast baseline**, and a query is considered slow when its average reaches `slowMultiplier × fastBaseline` (default: 5×).

### Hysteresis Band: Preventing Mode-Flapping

Even with a stable baseline, a query hovering right around the classification threshold could flip between lanes on consecutive executions. To prevent this, `RELATIVE_FAST_BASELINE` uses a **hysteresis band**: entry and recovery use different multipliers.

A query **enters** the slow lane when its average is at least `slowMultiplier × fastBaseline` (default: 5×). It **recovers** to the fast lane only when its average falls to or below `recoveryMultiplier × fastBaseline` (default: 3×). Additionally, a `minimumSlowQueryMs` floor (default: 100ms) ensures that no query is classified as slow regardless of the multiplier unless it genuinely takes meaningful time.

The gap between 5× and 3× means a query must clearly overshoot the threshold to enter the slow lane, and must clearly fall back to recover. A query with an average that drifts between 4× and 4.5× of the baseline stays classified as fast until it definitively crosses 5×, and once classified slow it stays there until it definitively drops below 3×.

### Warm-Up Period: Minimum Sample Requirement

To prevent a single slow execution of a brand-new query shape from immediately routing it to the slow lane, the classifier now requires a minimum number of samples before any classification decision is made. The default is 20 samples (`minSamples=20`). Until a query shape accumulates that many executions, it is treated as fast regardless of its individual execution times. This makes the early warm-up period predictable: new queries run in the fast lane until the server has enough data to classify them confidently.

### New Mode: ABSOLUTE_THRESHOLD

For teams that prefer a deterministic boundary over an adaptive one, 0.5.0-beta adds `ABSOLUTE_THRESHOLD`. In this mode, a query is classified as slow when its average execution time reaches or exceeds `slowQueryThresholdMs` (default: 1000ms). There is no baseline computation, no percentile, no hysteresis — the threshold is fixed.

`ABSOLUTE_THRESHOLD` is the right choice when you have a stable, well-understood workload and you know exactly where the latency boundary between fast and slow queries lies. It requires less configuration and is easier to reason about. `RELATIVE_FAST_BASELINE` is better when your workload varies by deployment environment, time of day, or data volume, and you want the server to adapt without manual tuning.

### Session Permit Short-Circuit

One operational optimisation was added for applications that issue multiple statements per connection — which is most applications. If a session has already acquired a fast or slow slot for a previous statement within the same session, subsequent statements in that session skip the slot acquisition step entirely. The session is already "in".

This reduces semaphore contention on the hot path. In the original implementation, every statement competed for a slot, even within a long-running session that had already been granted access. The short-circuit eliminates that overhead for sequential multi-statement workloads.

---

## The Core Mechanics

Here is how Open J Proxy's slow query segregation works under the hood. Some of these details — in particular the EWMA averaging — were not covered in the original article and are documented here for the first time.

When SQS is enabled, the Open J Proxy server partitions the connection pool into two lanes. By default, 20% of slots go to the slow lane and 80% go to the fast lane. A pool with 20 connections becomes 4 slow slots and 16 fast slots.

The server tracks every distinct query shape — a normalised hash of the SQL text — and maintains a rolling average execution time for each one using an EWMA formula where each new measurement contributes 20% weight and the stored average carries 80%. Classifications are updated continuously as queries execute.

If one lane is idle for a configurable period (default: 10 seconds), the other lane can **borrow** its slots. A fast query can borrow slow slots when no slow queries are running; a slow query can borrow fast slots when the fast lane is idle. Borrowed slots are returned to their original lane when released. The 80/20 split is a guaranteed minimum for each lane, not a hard cap — the full pool capacity is available to whichever lane needs it during periods of imbalanced load.

The segregation is transparent to the application. The JDBC driver and the SQL remain exactly as they are. The server handles everything.

---

## Configuration

SQS is disabled by default. Enable it in the Open J Proxy server configuration file once you confirm your workload has the mixed fast+slow pattern it is designed for.

```properties
# Enable slow query segregation
ojp.server.slowQuerySegregation.enabled=true

# Lane allocation: 20% slow, 80% fast (default)
ojp.server.slowQuerySegregation.slowSlotPercentage=20

# Idle timeout before borrowing (milliseconds, default: 10 seconds)
ojp.server.slowQuerySegregation.idleTimeout=10000

# Slot wait timeouts
ojp.server.slowQuerySegregation.slowSlotTimeout=120000
ojp.server.slowQuerySegregation.fastSlotTimeout=60000

# Classification mode (RELATIVE_FAST_BASELINE or ABSOLUTE_THRESHOLD)
ojp.server.slowQuerySegregation.classificationMode=RELATIVE_FAST_BASELINE

# RELATIVE_FAST_BASELINE tuning
ojp.server.slowQuerySegregation.minimumSlowQueryMs=100
ojp.server.slowQuerySegregation.slowMultiplier=5.0
ojp.server.slowQuerySegregation.recoveryMultiplier=3.0
ojp.server.slowQuerySegregation.minSamples=20
ojp.server.slowQuerySegregation.baselinePercentile=50
ojp.server.slowQuerySegregation.baselineRefreshIntervalSeconds=10

# For ABSOLUTE_THRESHOLD mode only
ojp.server.slowQuerySegregation.slowQueryThresholdMs=1000
```

The properties most worth reviewing for your specific situation:

- **`slowSlotPercentage`** — increase if your slow queries are important and must not be starved; decrease if slow queries are background jobs that should have minimal impact on fast traffic.
- **`minimumSlowQueryMs`** — the absolute floor for slow classification. Leave at 100ms unless your fast queries regularly run close to that.
- **`slowMultiplier` and `recoveryMultiplier`** — widen the gap to reduce flapping; narrow it if slow queries take too long to enter the slow lane after they degrade.
- **`minSamples`** — lower if you want new queries classified quickly; raise if you want more statistical confidence before routing decisions are made.

---

## When to Enable SQS

SQS is most valuable when there is a clear latency bimodality in your workload: queries that consistently complete in under 50ms running alongside queries that regularly take seconds. The most common examples are transactional applications that also run embedded reporting or data export jobs, background aggregation workers sharing a pool with user-facing APIs, and analytics queries issuing over the same Open J Proxy datasource as point lookups.

For **pure OLTP** deployments — where almost every query completes in single-digit milliseconds — SQS provides little benefit. There is nothing to segregate. The overhead of classification and semaphore management is small but nonzero, and the complexity is not worthwhile when all queries are in the same latency class.

For **pure OLAP** deployments — where almost every query is long-running — SQS is also of limited value. With no fast baseline to anchor the threshold, the relative mode cannot function usefully, and the fixed threshold mode would classify nearly everything as slow and funnel it all into the slow lane anyway.

The right signal for enabling SQS is your monitoring: if you see fast query P95 latency climb during periods of heavy slow-query activity, and the two query classes are clearly separated in your latency histogram, SQS will help.

---

## Worked Example

Suppose you have a 20-connection pool and the following query mix:

- `SELECT * FROM users WHERE id = ?` — average: 8ms
- `SELECT * FROM orders WHERE id = ?` — average: 15ms
- `SELECT * FROM reports WHERE month = ?` — average: 900ms

With defaults (20% slow slots = 4 slots, fast baseline at the median of fast queries = ~11ms):

- Fast baseline: 11ms
- Slow entry threshold: max(100ms, 5 × 11ms = 55ms) → **100ms** (minimum floor wins)
- Slow recovery threshold: 3 × 11ms = **33ms**

After the reporting query accumulates 20 samples, its 900ms average far exceeds the 100ms threshold. It enters the slow lane and is confined to at most 4 of the 20 slots. The remaining 16 slots are reserved for the user-facing queries, which continue to execute without interference even when 4 reporting queries are running simultaneously.

If the reporting query is later optimised and its average drops below 33ms, it recovers to the fast lane automatically.

Notice what did **not** happen: the 900ms reporting query's presence did not move the baseline. The baseline was computed from the 8ms and 15ms queries only, so the slow entry threshold stayed at 100ms throughout the surge, rather than drifting upward and accidentally reclassifying the reporting query as fast.

---

## How SQS Interacts with Client-Side Throttling

Client-side throttling (`ClientThrottleManager`) and SQS operate at different layers. SQS enforces lane limits inside the server, at the point where connections are assigned to SQL execution. Client throttling operates in the JDBC driver, limiting the number of concurrent in-flight gRPC requests per client process.

When SQS is active, the server sends `maxAdmission=fastSlots` (not `totalSlots`) in the `SessionInfo` on connection. This tells the driver that the effective capacity it should compute its fair share against is the fast lane only, not the full pool. This avoids a scenario where the driver believes it has access to 20 slots when in reality fast queries are competing for 16.

The full analysis of how the two features interact — including startup warm-up behaviour and recommended configurations for both modes — is in [documents/analysis/CLIENT_REACTIVE_THROTTLING_ANALYSIS.md](../analysis/CLIENT_REACTIVE_THROTTLING_ANALYSIS.md).

---

## Enabling SQS — Server Restart Required

Unlike query result caching and read/write splitting, SQS is configured on the **server side** in the Open J Proxy server properties file. Enabling or changing SQS settings requires restarting the Open J Proxy server.

The complete configuration reference is in [documents/configuration/ojp-server-configuration.md](../configuration/ojp-server-configuration.md). The design document with the original problem statement and design decisions is in [documents/designs/SLOW_QUERY_SEGREGATION.md](../designs/SLOW_QUERY_SEGREGATION.md).
