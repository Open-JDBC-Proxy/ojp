# OJP Client Specification — Machine-Oriented Reference

> **Status:** Normative — April 2026
> **Last updated:** 2026-04-30
> **Scope:** Defines the complete behavioral contract for any OJP client implementation.
> **Keywords:** MUST, MUST NOT, SHOULD, MAY as defined in RFC 2119.
> **Protocol source:** `ojp-grpc-commons/src/main/proto/StatementService.proto`, `echo.proto`
> **Human-readable companion:** [`CLIENT_SPEC.md`](CLIENT_SPEC.md)

---

## 1. Terminology

| Term | Definition |
|---|---|
| **Client** | A software library implementing this specification. |
| **Server** | An OJP server instance exposing `StatementService` and `EchoService` via gRPC. |
| **Endpoint** | A `host:port` pair identifying one Server. |
| **Virtual Connection** | A client-side object representing logical access to a database pool, identified by a `SessionInfo` token. Does not correspond 1:1 to a real database connection. |
| **Real Connection** | A JDBC connection held by the Server's connection pool. The Client never holds one directly. |
| **connHash** | A server-computed SHA-256 string keying a specific connection pool. Computed as SHA-256(`url + user + password + datasource_name`). |
| **SessionInfo** | A proto message propagated on every RPC. Contains `connHash`, `clientUUID`, `sessionUUID`, `transactionInfo`, `sessionStatus`, `isXA`, `targetServer`, `clusterHealth`. |
| **sessionUUID** | A server-assigned handle for a stateful session (transaction, LOB, cursor). Absent until the Server assigns it. |
| **targetServer** | The `host:port` the Server binds a `sessionUUID` to. The Client MUST route all requests carrying that `sessionUUID` to this server. |
| **clientUUID** | A stable UUID v4 generated once per Client process lifetime. |
| **clusterHealth** | A semicolon-delimited string of `host:port(UP\|DOWN)` segments reflecting known endpoint health. |
| **connHash cache** | A thread-safe client-side map: `url\|user\|password\|datasourceName → connHash`. Populated on first non-XA `connect()` RPC. |

---

## 2. State Machine

### 2.1 Connection States

| State | Description |
|---|---|
| `DISCONNECTED` | No `SessionInfo` exists; no RPC has been made. |
| `CONNECTING` | `connect()` RPC is in flight. |
| `CONNECTED` | `connHash` is known; no `sessionUUID` assigned. Requests are stateless. |
| `SESSION_ACTIVE` | `sessionUUID` is assigned; stickiness is enforced. |
| `IN_TRANSACTION` | `SESSION_ACTIVE` and `transactionStatus = TRX_ACTIVE`. |
| `TERMINATED` | `terminateSession()` has been called. No further RPCs are permitted. |

### 2.2 Connection State Transitions

| From | Trigger | To | Required Action |
|---|---|---|---|
| `DISCONNECTED` | `connect()` called, cache miss | `CONNECTING` | Issue `connect()` RPC |
| `DISCONNECTED` | `connect()` called, cache hit (non-XA) | `CONNECTED` | Build `SessionInfo` locally; NO RPC |
| `CONNECTING` | `connect()` RPC succeeds | `CONNECTED` | Cache `connHash`; store `ConnectionDetails` |
| `CONNECTING` | `connect()` RPC fails (transport error) | `DISCONNECTED` | Failover (§6.2); retry |
| `CONNECTED` | `startTransaction()` succeeds | `IN_TRANSACTION` | Update local `SessionInfo`; bind `sessionUUID` if newly assigned |
| `CONNECTED` | RPC returns new `sessionUUID` | `SESSION_ACTIVE` | Bind `sessionUUID → targetServer` |
| `SESSION_ACTIVE` | `startTransaction()` succeeds | `IN_TRANSACTION` | |
| `IN_TRANSACTION` | `commitTransaction()` succeeds | `SESSION_ACTIVE` | `transactionStatus = TRX_COMMITED` |
| `IN_TRANSACTION` | `rollbackTransaction()` succeeds | `SESSION_ACTIVE` | `transactionStatus = TRX_ROLLBACK` |
| `SESSION_ACTIVE` or `IN_TRANSACTION` | `terminateSession()` called | `TERMINATED` | Unbind `sessionUUID`; decrement server session count |
| Any | `NOT_FOUND` received | `DISCONNECTED` | Invalidate `connHash` cache entry; re-issue `connect()` |
| Any | `UNAVAILABLE` / `DEADLINE_EXCEEDED` | `DISCONNECTED` (that server) | Mark Endpoint `UNHEALTHY`; failover |

### 2.3 Server Endpoint States

| State | Description |
|---|---|
| `HEALTHY` | Server is reachable; eligible for load-balancing selection. |
| `UNHEALTHY` | Server has failed; not eligible for selection; health checker probes it periodically. |

**`HEALTHY → UNHEALTHY`:** Triggered by any of: `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `UNKNOWN` (message contains "connection"), `INTERNAL` without `SqlErrorResponse` trailer.

**`UNHEALTHY → HEALTHY`:** Triggered by successful health probe **after** `reinitializePoolOnRecoveredServer()` completes — the pool MUST be pre-created before `markHealthy()` is called.

### 2.4 Invalid Transitions

| Situation | Required Behavior |
|---|---|
| Any RPC called on a `TERMINATED` connection | MUST raise an error immediately; MUST NOT make any RPC. |
| Request routed to a server other than `targetServer` when `sessionUUID` is set | MUST raise an error; MUST NOT silently reroute. |
| `terminateSession()` called twice | MUST be idempotent (no error, no second RPC). |
| XA operation when `targetServer` is `UNHEALTHY` | MUST raise `XAER_RMFAIL` (or language equivalent); MUST NOT reroute. |
| `CANCELLED` treated as server failure | MUST NOT mark server `UNHEALTHY`; MUST NOT failover. |

---

## 3. Protocol Model

### 3.1 Message Structures

```
ConnectionDetails:
  url: string                     # actual database connection URL (e.g., jdbc:postgresql://...)
  user: string                    # database username
  password: string                # database password
  clientUUID: string              # stable process UUID (§4.1)
  properties: list[PropertyEntry] # key-value config pairs; include ojp.datasource.name
  serverEndpoints: list[string]   # all known OJP addresses as "host:port"
  clusterHealth: string           # current health string (§3.5); "" on first call
  isXA: bool                      # true for XA connections

SessionInfo:
  connHash: string                # opaque pool key; treat as immutable once received
  clientUUID: string              # echoed from ConnectionDetails
  sessionUUID: string             # absent until server assigns; triggers stickiness when set
  transactionInfo: {
    transactionUUID: string
    transactionStatus: TRX_ACTIVE | TRX_COMMITED | TRX_ROLLBACK
  }
  sessionStatus: SESSION_ACTIVE | SESSION_TERMINATED
  isXA: bool
  targetServer: string            # "host:port"; MUST be used for routing when sessionUUID is set
  clusterHealth: string           # server's view of cluster topology

StatementRequest:
  session: SessionInfo            # MUST include current SessionInfo
  sql: string
  parameters: list[ParameterProto]
  statementUUID: string           # new random UUID per statement instance
  properties: list[PropertyEntry]

ParameterProto:
  index: int32                    # 1-based parameter position
  type: ParameterTypeProto        # one of 28 enum values (see §9.1)
  values: list[ParameterValue]    # one for normal params; multiple for array params

ParameterValue (oneof):
  is_null: bool                   # SQL NULL
  bool_value: bool
  int_value: int32                # also used for PT_BYTE, PT_SHORT
  long_value: int64
  float_value: float
  double_value: double
  string_value: string            # also PT_BIG_DECIMAL ("<unscaled> <scale>"), PT_CHARACTER_READER, PT_SQL_XML
  bytes_value: bytes              # PT_BYTES, PT_ASCII_STREAM, PT_UNICODE_STREAM, PT_BINARY_STREAM
  date_value: google.type.Date    # PT_DATE
  time_value: google.type.TimeOfDay  # PT_TIME
  timestamp_value: TimestampWithZone # PT_TIMESTAMP
  int_array_value: IntArray       # PT_ARRAY of ints
  long_array_value: LongArray     # PT_ARRAY of longs
  string_array_value: StringArray # PT_ARRAY of strings
  url_value: google.protobuf.StringValue      # PT_URL; absent wrapper = SQL NULL
  rowid_value: google.protobuf.StringValue    # PT_ROW_ID; base64 bytes; absent = SQL NULL

TimestampWithZone:
  epochSeconds: int64
  nanos: int32
  timezone: string                # IANA zone ID or UTC offset (e.g., "America/New_York", "+05:30")
  originalType: TemporalType      # UNSPECIFIED | TIMESTAMP | CALENDAR | OFFSET_DATE_TIME |
                                  #   LOCAL_DATE_TIME | INSTANT | LOCAL_DATE | LOCAL_TIME | OFFSET_TIME

CallResourceRequest:
  session: SessionInfo
  resourceType: ResourceType      # RES_RESULT_SET | RES_STATEMENT | RES_PREPARED_STATEMENT |
                                  #   RES_CALLABLE_STATEMENT | RES_LOB | RES_CONNECTION | RES_SAVEPOINT
  resourceUUID: string
  target: TargetCall
  properties: list[PropertyEntry]

TargetCall:
  callType: CallType              # one of 47 codes
  resourceName: string
  params: list[ParameterValue]
  nextCall: TargetCall            # optional chaining for multiple operations in one round-trip

CallResourceResponse:
  session: SessionInfo
  resourceUUID: string            # UUID of newly created resource, if any
  values: list[ParameterValue]    # return values

XidProto:
  formatId: int32
  globalTransactionId: bytes      # max 64 bytes
  branchQualifier: bytes          # max 64 bytes

LobDataBlock:
  session: SessionInfo
  position: int64                 # byte offset of this chunk
  data: bytes                     # chunk content; recommended size 32–64 KB
  lobType: LobType                # LT_BLOB | LT_CLOB | LT_BINARY_STREAM | LT_ASCII_STREAM |
                                  #   LT_UNICODE_STREAM | LT_CHARACTER_STREAM
  metadata: list[PropertyEntry]

LobReference:
  session: SessionInfo
  uuid: string                    # LOB handle; pass as PT_BLOB/PT_CLOB string_value parameter
  bytesWritten: int32
  lobType: LobType

SqlErrorResponse (in gRPC trailing metadata on Status.INTERNAL):
  reason: string
  sqlState: string                # ANSI SQL state code
  vendorCode: int32               # database-specific error code
  sqlErrorType: SQL_EXCEPTION | SQL_DATA_EXCEPTION
```

### 3.2 RPC Catalogue

| RPC | Stream Type | Retry on Transport Error? |
|---|---|---|
| `connect` | unary | YES (always for XA; non-XA cache miss only) |
| `executeUpdate` | unary | Only if `sessionUUID` absent in request |
| `executeQuery` | server-streaming | Only if `sessionUUID` absent in request |
| `fetchNextRows` | unary | NO |
| `createLob` | client-streaming | NO |
| `readLob` | server-streaming | NO |
| `terminateSession` | unary | NO |
| `startTransaction` | unary | Only if `sessionUUID` absent in request |
| `commitTransaction` | unary | NO |
| `rollbackTransaction` | unary | NO |
| `callResource` | unary | Only if `sessionUUID` absent in request |
| `xaStart` | unary | YES |
| `xaEnd` | unary | NO |
| `xaPrepare` | unary | NO |
| `xaCommit` | unary | NO |
| `xaRollback` | unary | NO |
| `xaRecover` | unary | NO |
| `xaForget` | unary | NO |
| `xaSetTransactionTimeout` | unary | NO |
| `xaGetTransactionTimeout` | unary | NO |
| `xaIsSameRM` | unary | NO |
| `EchoService.Echo` | unary | used for health probes only |

---

## 4. Client Contract

### 4.1 Initialization

1. The client MUST generate one UUID v4 as `clientUUID` at library initialization time. This value MUST remain constant for the process lifetime and MUST NOT be persisted across restarts.
2. The client MUST create one gRPC `ManagedChannel` (or equivalent) per distinct server endpoint. Channels MUST be long-lived and shared across all connections to that endpoint.
3. The client MUST use graceful channel shutdown (allow in-flight calls to drain) on process termination.
4. The client MUST start a background health-check task scheduled at `ojp.health.check.interval` (default 5 000 ms).

### 4.2 Connection Rules

**Non-XA first connect (cache miss):**

1. Build `ConnectionDetails` with `url`, `user`, `password`, `clientUUID`, `serverEndpoints`, `clusterHealth`, `isXA=false`, and applicable `properties`.
2. Call `connect(ConnectionDetails)` on the selected endpoint.
3. Cache: `connHashByKey[url+"|"+user+"|"+password+"|"+datasourceName] = response.connHash`.
4. Cache: `storedDetails[response.connHash] = ConnectionDetails` (for `NOT_FOUND` recovery).
5. Return `SessionInfo` from response.

**Non-XA subsequent connect (cache hit):**

1. Look up `connHash` from cache using the connection key.
2. Build `SessionInfo` locally: `{connHash, clientUUID, isXA=false}`. MUST NOT set `sessionUUID`.
3. Return without making any RPC call.

**XA connect (always RPC):**

1. MUST always call `connect(ConnectionDetails)` with `isXA=true`.
2. MUST immediately bind `response.sessionUUID → response.targetServer`.

**NOT_FOUND recovery:**

1. Remove `connHashByKey[connectionKey]` from cache (keep `storedDetails`).
2. Re-issue `connect(storedDetails[oldConnHash])`.
3. Update `connHashByKey[connectionKey]` with new `connHash`.
4. Retry the original failed operation once.
5. This retry MUST NOT be performed if a `sessionUUID` was active — the session state is permanently lost and the error MUST be surfaced to the caller.

### 4.3 Session Propagation Rules

1. The client MUST include the current `SessionInfo` in every outgoing RPC request.
2. The client MUST replace its local `SessionInfo` with the `SessionInfo` returned in every RPC response.
3. When the response `SessionInfo` contains a `sessionUUID` not present in the request, the client MUST immediately register: `sessionUUID → response.targetServer`.
4. The client MUST call `terminateSession(session)` exactly once when closing a connection. After this call, the connection MUST be considered unusable.

### 4.4 Statement Execution Rules

1. The client MUST generate a new random UUID as `statementUUID` for each `StatementRequest`.
2. Parameters MUST use 1-based indexing in `ParameterProto.index`.
3. `PT_BIG_DECIMAL` MUST be encoded as `string_value = "<unscaledInteger> <scale>"` (space-separated). Example: `BigDecimal("123.45")` → `"12345 2"`.
4. Presence-aware fields (`url_value`, `rowid_value`, `uuid_value`, `biginteger_value`) use `google.protobuf.StringValue` wrappers. An absent (unset) wrapper MUST be treated as SQL NULL. An empty string inside the wrapper is a valid non-null value.

### 4.5 Resource Lifecycle Rules

1. LOB handles (`LobReference.uuid`) are server-side objects. They MUST NOT be used after `terminateSession()`.
2. Result set handles (`resultSetUUID`) are server-side objects. The client MUST call `callResource(RES_RESULT_SET, CALL_CLOSE)` when done, unless the connection is being terminated.
3. Savepoint handles (from `CALL_SET` on `RES_SAVEPOINT`) MUST NOT be used after `commitTransaction()` or `rollbackTransaction()`.

---

## 5. Concurrency Model

1. A single `SessionInfo` / connection object MUST NOT be used concurrently from multiple threads without external synchronization.
2. The `connHash` cache MUST be thread-safe. Multiple connections with the same credentials will read from it concurrently.
3. The `sessionUUID → targetServer` map MUST be thread-safe.
4. Per-server session counts (for load balancing) MUST be updated atomically.
5. The background health-check task MUST run in a separate thread / goroutine / async task and MUST NOT block SQL execution paths.
6. `pushClusterHealthToAllHealthyServers()` MUST be submitted to a background scheduler (non-blocking) when called from a query thread via `handleServerFailure()`. It MAY be called inline when called from the health-check thread.

---

## 6. Error Handling and Retry Semantics

### 6.1 Error Classification

| gRPC Status | Condition | Required Client Action |
|---|---|---|
| `INTERNAL` + `SqlErrorResponse` trailer | SQL error (bad query, constraint, auth) | Throw SQL exception; DO NOT retry; DO NOT mark server `UNHEALTHY` |
| `NOT_FOUND` | Pool not found (server restarted) | Invalidate `connHash`; reconnect; retry once if no active `sessionUUID` |
| `UNAVAILABLE` | Server unreachable | Mark server `UNHEALTHY`; failover (§6.2) |
| `DEADLINE_EXCEEDED` | Request timed out | Mark server `UNHEALTHY`; failover (§6.2) |
| `UNKNOWN` (message contains "connection") | Transport failure | Mark server `UNHEALTHY`; failover (§6.2) |
| `INTERNAL` (no `SqlErrorResponse` trailer) | Transport-level internal error | Mark server `UNHEALTHY`; failover (§6.2) |
| `CANCELLED` | Client-initiated cancellation | DO NOT mark server `UNHEALTHY`; DO NOT failover; surface to caller |
| `RESOURCE_EXHAUSTED` | Pool exhausted | DO NOT retry; DO NOT mark server `UNHEALTHY`; surface to caller |
| Session-invalidation message | Session state lost after server failure | DO NOT retry; surface to caller |

### 6.2 Failover Procedure (Ordered Steps)

1. Capture `wasHealthy = endpoint.isHealthy`.
2. Set `endpoint.isHealthy = false`. Record `endpoint.lastFailureTime = now()`.
3. Log the failure.
4. If `wasHealthy == true`: submit `pushClusterHealthToAllHealthyServers()` to the background scheduler. MUST NOT block the caller thread.
5. Gracefully shut down the gRPC channel for the failed endpoint (allow in-flight calls to drain, then discard).
6. Select the next `HEALTHY` endpoint using the configured strategy, excluding all already-attempted endpoints in this retry cycle.
7. Retry the original operation on the new endpoint.
8. If all endpoints are `UNHEALTHY` or exhausted: raise a connection error to the caller.

### 6.3 Retry Limits

| Property | Default | Meaning |
|---|---|---|
| `ojp.multinode.retry.attempts` | `3` | Maximum failover attempts per operation |
| `ojp.multinode.retry.delay` | `100` ms | Delay between retry attempts |

---

## 7. Session and Affinity Rules

### 7.1 When Affinity Is Required

Affinity is required whenever `sessionUUID` is present in the local `SessionInfo`. This includes all of:
- Any open transaction (`IN_TRANSACTION` state)
- Any open LOB handle
- Any open server-side cursor (`resultSetUUID`)
- Any XA session (entire lifetime of the XA branch)

### 7.2 How Affinity Is Maintained

1. The client MUST maintain a thread-safe map: `sessionUUID → host:port`.
2. When routing a request that carries a `sessionUUID`, the client MUST look up the bound server and route exclusively to it.
3. When a response returns a `sessionUUID` not present in the request, the client MUST add the binding immediately.
4. When a response returns a `targetServer` different from the currently bound server, the client MUST update the binding and SHOULD log a warning.
5. On `terminateSession()`: MUST remove the binding and MUST decrement the active-session count for the previously bound server.

### 7.3 Affinity Violation Behavior

If the bound server is `UNHEALTHY` when a sticky request is made, the client MUST:

1. Raise an error to the caller immediately.
2. MUST NOT reroute the request to any other server.
3. MUST NOT retry the operation automatically.

---

## 8. Versioning and Compatibility

1. The client MUST be compiled against the same `.proto` files as the target server version.
2. The client SHOULD send only fields defined in the proto version it was compiled against.
3. The client MUST gracefully handle unknown enum values in responses by treating them as the zero/default value.
4. The client MUST NOT depend on the internal structure or value of `connHash` — it is an opaque string assigned by the server.
5. The cluster health string format (`host:port(UP|DOWN);...`) MUST be treated as stable across minor versions. The client MUST parse only the `UP`/`DOWN` token and MUST ignore any additional tokens inside the parentheses.

---

## 9. Compliance Requirements

### 9.1 MUST Implement

- All 21 `StatementService` RPCs and `EchoService.Echo`
- `connHash` caching (non-XA cache-hit path with no RPC)
- `NOT_FOUND` recovery (invalidate cache, reconnect, retry once)
- `SessionInfo` propagation on every RPC (send current, replace with response)
- Session stickiness enforcement (`sessionUUID → targetServer` binding)
- Failover (mark `UNHEALTHY`, select next endpoint, retry up to configured limit)
- Background health checking (Phase 1: probe healthy endpoints; Phase 2: probe unhealthy endpoints)
- Health check guard: Phase 1 fires only when `sessionToServerMap` non-empty **OR** `connectionDetailsByConnHash` non-empty
- Cluster health string generation (`host:port(UP|DOWN);...`) and consumption
- Both load-balancing strategies (least-connections and round-robin) selectable via `ojp.loadaware.selection.enabled`
- `terminateSession()` on connection close
- Graceful gRPC channel shutdown on process termination
- All 28 `ParameterTypeProto` values (encode and decode): `PT_NULL`, `PT_BOOLEAN`, `PT_BYTE`, `PT_SHORT`, `PT_INT`, `PT_LONG`, `PT_FLOAT`, `PT_DOUBLE`, `PT_BIG_DECIMAL`, `PT_STRING`, `PT_BYTES`, `PT_DATE`, `PT_TIME`, `PT_TIMESTAMP`, `PT_ASCII_STREAM`, `PT_UNICODE_STREAM`, `PT_BINARY_STREAM`, `PT_OBJECT`, `PT_CHARACTER_READER`, `PT_REF`, `PT_BLOB`, `PT_CLOB`, `PT_ARRAY`, `PT_URL`, `PT_ROW_ID`, `PT_N_STRING`, `PT_N_CHARACTER_STREAM`, `PT_N_CLOB`, `PT_SQL_XML`
- `BigDecimal` encoding as `"<unscaledInteger> <scale>"`
- `TimestampWithZone` encoding/decoding for all 9 `TemporalType` values
- LOB write (`createLob` client-streaming, chunked at 32–64 KB) and read (`readLob` server-streaming)
- Non-XA transaction lifecycle (`startTransaction`, `commitTransaction`, `rollbackTransaction`)
- Savepoints via `callResource` (`RES_SAVEPOINT`, `CALL_SET`/`CALL_ROLLBACK`/`CALL_RELEASE`)
- `callResource` protocol (all 7 `ResourceType` values, all 47 `CallType` codes)
- Configuration loading: system/env properties > `ojp.properties` file > built-in defaults; per-datasource prefix `<name>.ojp.*`
- TLS transport support (plaintext default; TLS when `ojp.grpc.tls.enabled=true`)
- `clientUUID` generation (one UUID v4 per process lifetime)
- `reinitializePoolOnRecoveredServer()` called **before** `endpoint.markHealthy()` on recovery

### 9.2 SHOULD Implement

- Full XA transaction lifecycle (all 10 XA RPCs)
- Full-validation health probe (in addition to heartbeat probe)
- Connection redistribution on recovery (rebalancing idle connections across servers)
- Cache rule pass-through via `ConnectionDetails.properties`
- `DataSource` wrapper / integration API matching host platform conventions
- Per-datasource configuration namespacing

### 9.3 MAY Implement

- Async / non-blocking RPC API surface
- Metrics / telemetry export (OpenTelemetry recommended)
- Client-side connection pooling — NOTE: if implemented, pool size MUST effectively be 1 per virtual connection; double-pooling causes incorrect behavior

---

## 10. Action → Protocol Mapping

| High-Level Action | gRPC RPC(s) | Notes |
|---|---|---|
| `open_connection(endpoint, db_url, user, password)` | `connect(ConnectionDetails)` — only on cache miss | Cache hit: no RPC; build `SessionInfo` locally |
| `open_xa_connection(...)` | `connect(ConnectionDetails, isXA=true)` | Always RPC; pins to one endpoint |
| `execute_query(sql, params)` | `executeQuery(StatementRequest)` | Server-streaming; first message has labels + first batch of rows |
| `fetch_more_rows(result_set_uuid, page_size)` | `fetchNextRows(ResultSetFetchRequest)` | Empty `rows` list means result set exhausted |
| `execute_update(sql, params)` | `executeUpdate(StatementRequest)` | `OpResult.value.int_value` = affected row count |
| `call_stored_procedure(sql, params)` | `callResource(CALL_PREPARE)` then `callResource(CALL_EXECUTE)` | `CALL_PREPARE` returns `resourceUUID` |
| `begin_transaction()` | `startTransaction(SessionInfo)` | Returns `SessionInfo` with `TRX_ACTIVE` |
| `commit()` | `commitTransaction(SessionInfo)` | Returns `SessionInfo` with `TRX_COMMITED` |
| `rollback()` | `rollbackTransaction(SessionInfo)` | Returns `SessionInfo` with `TRX_ROLLBACK` |
| `set_savepoint(name)` | `callResource(RES_SAVEPOINT, CALL_SET, "Savepoint", [name])` | Returns `resourceUUID` for later rollback/release |
| `rollback_to_savepoint(uuid)` | `callResource(RES_SAVEPOINT, CALL_ROLLBACK, resourceUUID=uuid)` | |
| `release_savepoint(uuid)` | `callResource(RES_SAVEPOINT, CALL_RELEASE, resourceUUID=uuid)` | |
| `write_lob(data)` | `createLob(stream LobDataBlock)` | Client-streaming; chunk at 32–64 KB; returns `LobReference.uuid` |
| `read_lob(uuid, pos, len)` | `readLob(ReadLobRequest)` | Server-streaming; concatenate `data` fields in order |
| `close_result_set(uuid)` | `callResource(RES_RESULT_SET, CALL_CLOSE, resourceUUID=uuid)` | |
| `navigate_cursor(uuid, op, row)` | `callResource(RES_RESULT_SET, CALL_ABSOLUTE/RELATIVE/FIRST/LAST/NEXT/PREVIOUS, …)` | |
| `cancel_statement(uuid)` | `callResource(RES_STATEMENT, CALL_CANCEL, resourceUUID=uuid)` | |
| `get_db_metadata(key)` | `callResource(RES_CONNECTION, CALL_GET, resourceName=key)` | |
| `set_transaction_isolation(level)` | `callResource(RES_CONNECTION, CALL_SET, "TransactionIsolation", [level])` | |
| `close_connection()` | `terminateSession(SessionInfo)` | MUST be called exactly once per connection |
| `health_probe_heartbeat(endpoint)` | `connect(url="", user="", password="")` or `EchoService.Echo` | Any response means transport is up |
| `health_probe_full(endpoint, details)` | `connect(details)` then `terminateSession(session)` | Full pool validation |
| `push_cluster_health(endpoints, stored)` | `connect(ConnectionDetails{clusterHealth=…})` on each healthy endpoint | No-op for pool creation; server resizes pool |
| `xa_start(xid)` | `xaStart(XaStartRequest)` | Safe to retry on transport error |
| `xa_end(xid)` | `xaEnd(XaEndRequest)` | MUST NOT retry |
| `xa_prepare(xid)` | `xaPrepare(XaPrepareRequest)` | MUST NOT retry |
| `xa_commit(xid, one_phase)` | `xaCommit(XaCommitRequest)` | MUST NOT retry |
| `xa_rollback(xid)` | `xaRollback(XaRollbackRequest)` | MUST NOT retry |
| `xa_recover()` | `xaRecover(XaRecoverRequest)` | |
| `xa_forget(xid)` | `xaForget(XaForgetRequest)` | |
