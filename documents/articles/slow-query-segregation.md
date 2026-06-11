# Slow Query Segregation in OJP

Database queries are not all the same. A user-facing lookup by primary key completes in a few milliseconds. A reporting query that aggregates a year of transactions might run for thirty seconds. When both kinds of queries share the same connection pool and the same thread budget, the reporting queries win by default — not because they are more important, but because they hold slots longer and are simply harder to displace.

The result is familiar to anyone who has run a mixed workload: the monitoring dashboard shows user-facing response times climbing steadily during the morning reporting run, and the on-call engineer ends up throttling batch jobs to get the site back to normal.

OJP's Slow Query Segregation feature addresses this at the control plane level, before any query touches the connection pool. It learns which query shapes are slow, assigns them to a dedicated connection lane, and ensures that no matter how many slow queries are in flight, a configurable reserve of slots is always available for fast queries. Slow queries cannot starve fast ones.

This article explains how that works, how to tune it, and when to enable it.

---

## The Core Idea: Two Lanes, One Pool

When slow query segregation (SQS) is enabled, the OJP server partitions the connection pool's capacity into two semaphore-guarded lanes: a **fast lane** and a **slow lane**. By default, 20% of slots go to the slow lane and 80% go to the fast lane. A pool with 20 connections becomes 4 slow slots and 16 fast slots.

Before executing any SQL statement, the server checks the query's classification. A query whose shape has been learned to be slow must acquire a slot from the slow lane. A fast (or unclassified) query acquires from the fast lane. The semaphores enforce this: a slow query cannot consume a fast slot and vice versa, so a flood of long-running analytics queries cannot squeeze out the user-facing lookups.

The segregation is transparent to the application. The JDBC driver and the SQL remain exactly as they are. The server handles everything.

---

## How Queries Get Classified

The server tracks every distinct query shape — where a "shape" is a normalised hash of the SQL text — and maintains a rolling average execution time for each one using an EWMA (exponentially weighted moving average) formula. Each new measurement has 20% weight; the stored average carries 80%. This smooths over occasional outliers without losing responsiveness to genuine performance changes.

Classification does not begin immediately. A query shape must accumulate at least 20 samples before it is eligible for slow classification. This prevents a single slow execution of a brand-new query from immediately routing it to the slow lane.

### Classification Mode: RELATIVE_FAST_BASELINE

The default classification mode computes the slow threshold dynamically from the behaviour of the fast queries themselves.

At configurable intervals (default: every 10 seconds), the server takes all query shapes that are currently classified as fast, collects their average execution times, and computes the percentile value at the configured percentile position (default: the 50th percentile — the median). This becomes the **fast baseline**.

A query enters the slow lane when both conditions hold:
- its average execution time is at least `minimumSlowQueryMs` (default: 100ms), AND
- its average is at least `slowMultiplier × fastBaseline` (default: 5×)

A query recovers to the fast lane when:
- its average falls below `minimumSlowQueryMs`, OR
- its average falls to or below `recoveryMultiplier × fastBaseline` (default: 3×)

The gap between `slowMultiplier` (5×) and `recoveryMultiplier` (3×) is the **hysteresis band**. A query must clearly overshoot the threshold to enter the slow lane, and must clearly fall back to recover. This prevents mode-flapping: a query that hovers right around the threshold will not flip between lanes on consecutive requests.

The baseline is computed only from queries that are currently fast, which means a burst of slow queries does not raise the threshold for classifying further queries as slow. The baseline is stable during overload, which is precisely when you need SQS to work.

### Classification Mode: ABSOLUTE_THRESHOLD

For teams that prefer predictability over adaptivity, `ABSOLUTE_THRESHOLD` uses a fixed millisecond cutoff. Any query whose average execution time reaches or exceeds `slowQueryThresholdMs` (default: 1000ms) is classified as slow. There is no baseline computation and no hysteresis — the threshold is the threshold.

Use `ABSOLUTE_THRESHOLD` when you have a good understanding of your workload's latency distribution and want a deterministic boundary that does not shift under load. Use `RELATIVE_FAST_BASELINE` (the default) when your workloads have varying latency profiles across deployments or time-of-day patterns, or when you want the system to adapt without manual tuning.

---

## Slot Borrowing

Strict lane separation would waste capacity when one workload type is idle. If no slow queries are running at all, 20% of the connection pool sits unused while fast queries queue up.

SQS handles this with **slot borrowing**. If one lane has been idle for a configurable period (default: 10 seconds), the other lane can borrow its slots. A slow query can borrow from the fast lane when the fast lane is idle; a fast query can borrow from the slow lane when no slow queries are running. Borrowed slots are tracked and returned to their original lane when released.

This means the 80/20 split is a guaranteed minimum for each lane, not a hard cap. During periods of imbalanced load, the full pool capacity is available to whichever lane needs it, and the safety guarantee still holds: as soon as slow queries reappear, they are confined to their own lane again.

---

## Session Permit Short-Circuit

One optimisation worth knowing about: if a session has already acquired a fast or slow slot for a previous statement in the same session, subsequent statements within that session skip the slot acquisition step. The session is already "in". This avoids unnecessary semaphore contention on the hot path for applications that issue multiple statements per connection — which is most applications.

---

## Configuration

SQS is disabled by default. Enable it in the OJP server configuration file (or via environment variables) once you confirm your workload has the mixed fast+slow pattern it is designed for.

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

The defaults are conservative and work well for most mixed workloads. The properties most worth reviewing for your specific situation are:

- **`slowSlotPercentage`** — increase if your slow queries are important and must not be starved; decrease if slow queries are background jobs that should have minimal impact on fast traffic.
- **`minimumSlowQueryMs`** — the floor below which no query is considered slow, regardless of the fast baseline. Leave at 100ms unless your fast queries regularly exceed that.
- **`slowMultiplier` and `recoveryMultiplier`** — widen the gap if you observe flapping; narrow it if slow queries take a long time to enter the slow lane after they degrade.
- **`minSamples`** — lower if you want new queries classified quickly; raise if you want more statistical confidence before routing decisions are made.

---

## When to Enable SQS

SQS is most valuable when there is a clear latency bimodality in your workload: queries that consistently complete in under 50ms running alongside queries that regularly take seconds. The most common examples are transactional applications that also run embedded reporting or data export jobs, background aggregation workers sharing a pool with user-facing APIs, and analytics queries issuing over the same OJP datasource as point lookups.

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
- Slow entry threshold: max(100ms, 5 × 11ms = 55ms) → **100ms** (minimum wins)
- Slow recovery threshold: 3 × 11ms = **33ms**

After the reporting query accumulates 20 samples, its 900ms average far exceeds the 100ms threshold. It enters the slow lane and is confined to at most 4 of the 20 slots. The remaining 16 slots are reserved for the user-facing queries, which continue to execute without interference even when 4 reporting queries are running simultaneously.

If the reporting query is later optimised and its average drops below 33ms, it recovers to the fast lane automatically.

---

## How SQS Interacts with Client-Side Throttling

Client-side throttling (`ClientThrottleManager`) and SQS operate at different layers. SQS enforces lane limits inside the server, at the point where connections are assigned to SQL execution. Client throttling operates in the JDBC driver, limiting the number of concurrent in-flight gRPC requests per client process.

When SQS is active, the server sends `maxAdmission=fastSlots` (not `totalSlots`) in the `SessionInfo` on connection. This tells the driver that the effective capacity it should compute its fair share against is the fast lane only, not the full pool. This avoids a scenario where the driver believes it has access to 20 slots when in reality fast queries are competing for 16.

The full analysis of how the two features interact — including startup warm-up behaviour and recommended configurations for both modes — is in [documents/analysis/CLIENT_REACTIVE_THROTTLING_ANALYSIS.md](../analysis/CLIENT_REACTIVE_THROTTLING_ANALYSIS.md).

---

## Enabling SQS — Server Restart Required

Unlike query result caching and read/write splitting, SQS is configured on the **server side** in the OJP server properties file. Enabling or changing SQS settings requires restarting the OJP server.

The complete configuration reference is in [documents/configuration/ojp-server-configuration.md](../configuration/ojp-server-configuration.md). The design document with the original problem statement and design decisions is in [documents/designs/SLOW_QUERY_SEGREGATION.md](../designs/SLOW_QUERY_SEGREGATION.md).
