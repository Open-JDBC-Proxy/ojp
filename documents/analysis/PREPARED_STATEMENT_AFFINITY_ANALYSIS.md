# Prepared Statement Affinity and Performance Impact Analysis

**Date:** 2026-05-08  
**Status:** Findings Documented

---

## Question

> What is the performance impact of prepared statements in OJP multinode mode?  
> Are prepared statements bound to a single connection, so all calls for that prepared statement must go to the same OJP server (sticky)?

---

## Short Answer

Yes. In practice, prepared statements are session/connection-bound in OJP, so prepared-statement calls are expected to stay on the same bound server for correctness and reuse.  
Performance gain depends on affinity quality:

- **High affinity** → good reuse, less parse/prepare overhead.
- **Low affinity** → less reuse and more repeated prepare work.

---

## Findings

### 1) Prepared statements are stored inside a server-side `Session`

Server-side session state includes a dedicated `preparedStatementMap` keyed by UUID.  
This means prepared statement lifecycle is tied to that specific session object (and therefore its connection context).

Relevant code:
- `/home/runner/work/ojp/ojp/ojp-server/src/main/java/org/openjproxy/grpc/server/Session.java`
- `/home/runner/work/ojp/ojp/ojp-server/src/main/java/org/openjproxy/grpc/server/SessionManagerImpl.java`
- `/home/runner/work/ojp/ojp/ojp-server/src/main/java/org/openjproxy/grpc/server/action/resource/CallResourceAction.java`

### 2) The driver enforces session-to-server affinity in multinode mode

The driver keeps a `sessionUUID -> server` mapping and resolves routing through `affinityServer(sessionKey)`.  
If a session is missing or bound server is unhealthy, the call fails rather than silently rerouting.

Relevant code:
- `/home/runner/work/ojp/ojp/ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/MultinodeConnectionManager.java`
- `/home/runner/work/ojp/ojp/ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/SessionTracker.java`
- `/home/runner/work/ojp/ojp/ojp-jdbc-driver/src/main/java/org/openjproxy/grpc/client/MultinodeStatementService.java`

### 3) Prepared statement UUID reuse assumes session continuity

The JDBC driver stores the remote statement UUID (`statementUUID`) and sends it on subsequent calls.  
That reuse path requires the same session context where the prepared statement was registered.

Relevant code:
- `/home/runner/work/ojp/ojp/ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/PreparedStatement.java`
- `/home/runner/work/ojp/ojp/ojp-jdbc-driver/src/main/java/org/openjproxy/jdbc/Statement.java`

---

## Performance Impact

## Positive impact (when affinity is stable)

- Lower repeated parse/prepare overhead on the database side.
- Better latency consistency for repeated SQL patterns.
- Better throughput for prepare-heavy workloads.

## Neutral/negative impact (when affinity is weak)

- Prepared statement reuse hit rate drops if workload is stateless and spread broadly.
- More repeated prepare operations across sessions/servers.
- Extra memory overhead for per-session prepared statement registries with little reuse benefit.

---

## Practical Conclusion for OJP

- Prepared statement behavior is effectively **sticky to the session**, and session is sticky to one OJP node.
- For PS-heavy workloads, preserving session affinity is important to realize gains.
- If traffic pattern intentionally minimizes affinity, expected PS performance benefit is reduced.

---

## Confidence

**High (90%)**  
Reason: Findings are directly supported by current driver/server routing and session-management code paths listed above.  
Remaining uncertainty is mostly workload-specific (reuse frequency, SQL mix, session lifetime), not architecture ambiguity.

