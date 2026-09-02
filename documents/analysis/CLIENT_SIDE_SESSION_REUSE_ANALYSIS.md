# Client-Side Session Reuse Analysis (Persistent JDBC Connections)

**Date:** 2026-05-10  
**Status:** 📋 Draft  
**Scope:** OJP JDBC driver + OJP server behavior for non-XA session reuse

---

## 1) Problem Statement

Current flow still pays a `terminateSession` gRPC round trip when JDBC `Connection.close()` is called, even though non-XA `connect()` already has a cached `connHash` fast path.

Goal: reduce request overhead by reusing client-side logical JDBC connections/sessions instead of connect/close-per-request behavior, while enforcing:

- limits on how many reusable connections a client can hold,
- limits on how long they can be held,
- visibility/awareness of whether work is waiting or in flight.

---

## 2) Current Behavior (Verified)

### Already optimized
- Non-XA `connect()` can skip server RPC using cached `connHash` after first successful connect.

### Still expensive on close
- Driver `Connection.close()` always calls `terminateSession(session)`.
- In multinode, termination can fan out to multiple servers that previously received `connect()` for the same `connHash`.

### Server cleanup fallback exists
- Server has idle-session cleanup (enabled by default), with timeout + periodic cleanup task.
- This cleanup is a safety mechanism for abandoned sessions, not a client-side reuse strategy.

### Session activity tracking exists
- Server updates `lastActivityTime` on statement/row-fetch operations.
- There is no first-class client-facing signal for “queue depth per session” or “pending requests per session”.

---

## 3) Required Changes for Client-Side Session Reuse

## 3.1 Driver-side lease manager (new)

Introduce a **session lease manager** inside the JDBC driver (or multinode service layer):

- Lease states: `IDLE`, `IN_USE`, `EXPIRED`, `INVALID`.
- Per-lease metadata:
  - `createdAt`, `lastUsedAt`
  - borrow count
  - in-flight RPC count
  - bound server/session identifiers
- Reuse key: `(clientUUID, connHash, isXA=false, datasourceName, endpoint set)`

## 3.2 Close semantics split

Replace current unconditional terminate-on-close with policy:

- On `close()`:
  - return lease to local reusable pool if safe;
  - otherwise call `terminateSession`.
- Force terminate when:
  - transaction is active,
  - connection marked invalid/forceInvalid,
  - session consistency uncertain,
  - cleanup/reset fails.

## 3.3 Strict limits (must-have)

Add configurable limits:

- `maxReusableSessionsPerClient`
- `maxReusableSessionsGlobal` (optional but recommended)
- `maxSessionIdleMs`
- `maxSessionLifetimeMs`
- `acquireTimeoutMs` (wait for reusable lease)
- `maxPendingBorrowersPerKey` (backpressure)

## 3.4 Session sanitization before reuse

Before re-leasing to a different request:

- reset auto-commit and transaction state,
- clear warnings/temp statement artifacts,
- restore expected read-only/isolation/schema/catalog defaults,
- ensure no active LOB/result-set resources remain.

If sanitization is partial or uncertain, terminate rather than reuse.

## 3.5 Observability and “waiting queries” support

Expose and consume metrics for policy/operations:

- client-side: borrowers waiting, active leases, idle leases, evictions (reason),
- server-side: pool waiters (`threadsAwaitingConnection`), acquisition latency, active sessions per client.

Optional protocol extension can add lightweight status endpoint for better routing/eviction signals.

## 3.6 Server-side safeguards

Add optional server protections (recommended even with client reuse):

- max active sessions per `clientUUID`,
- max session creations per window (rate limiting),
- telemetry dimensions by `clientUUID` + `connHash`.

---

## 4) Compatibility and Architectural Considerations

## 4.1 Double-pooling guidance

OJP documentation emphasizes disabling app-local pools (to avoid double-pooling).  
Session reuse must be positioned as **driver-controlled session leasing**, not generic framework pooling.

## 4.2 Multinode stickiness side-effects

Reused leases are sticky by session/server binding. Risks:

- hot-spotting and imperfect rebalance,
- stale bindings after topology changes,
- higher complexity during failover/recovery.

Mitigation: eviction/rebind policy tied to health events and lease age.

## 4.3 XA scope

Do **not** include XA in initial rollout. XA lifecycle and dual-condition return semantics are more sensitive.

---

## 5) Risks

1. **State leakage across requests** (highest risk)  
2. **Semantic regressions** (transactions/metadata behavior)  
3. **Load skew in multinode mode**  
4. **Hidden resource retention** if limits/eviction are weak  
5. **Operational complexity** without strong metrics

---

## 6) Recommended Rollout Plan

1. **Phase 1 (opt-in, non-XA only):**
   - driver lease manager,
   - strict limits + aggressive safety fallback to terminate.
2. **Phase 2:**
   - server-side per-client quotas,
   - richer metrics and dashboards.
3. **Phase 3:**
   - protocol/status enhancements only if needed by production telemetry.

Default should remain current behavior until confidence is high.

---

## 7) Opinions, Suggestions, Questions, and Concerns

## Opinions
- This is worth doing for latency-sensitive workloads, but only with strong safety boundaries.
- “Never reuse if uncertain” should be the default invariant.

## Suggestions
- Name the feature **Session Lease Manager** (clearer than “client pooling”).
- Keep it **opt-in** with conservative defaults.
- Add explicit counters for eviction causes (`idle_timeout`, `max_lifetime`, `unsafe_state`, `server_unhealthy`).

## Questions
1. Should limits be per JVM process only, or also enforced server-side per client identity?
2. What defaults are acceptable for max idle and max lifetime?
3. Is preserving full session state between borrows a requirement, or should reset-to-default always occur?
4. Do we need a dedicated gRPC endpoint for session status/queue hints, or are current metrics sufficient?

## Concerns
- Without robust sanitization, this can introduce subtle cross-request bugs.
- Without server quotas, a misbehaving client could retain too many sessions.
- Multinode failover + stale leased sessions can create hard-to-debug edge cases.

---

## 8) Confidence

**High (85%)** for feasibility and direction, because major building blocks already exist:

- non-XA connect fast path,
- session activity tracking + cleanup,
- health/redistribution infrastructure.

Main uncertainty is safe sanitization breadth across all JDBC surface area.

