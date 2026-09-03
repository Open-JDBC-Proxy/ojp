# OJP Generic Messaging Protocol — Analysis

## Question

OJP needs a generic-purpose way to exchange messages:

1. Between OJP servers (e.g. RAFT-style leader-election consensus messages).
2. From OJP servers to OJP servers (e.g. cache-invalidation broadcasts).
3. From an OJP server to the JDBC clients connected to it (e.g. "this server is
   restarting, please fail over").

**Hard constraint:** the transport must reuse the existing `ojp-jdbc-driver`
(and the gRPC channel/session machinery it already implements). OJP servers
are **not** allowed to open a new, separate connection directly to each other
(no raw sockets, no second gRPC server-to-server link, no new listening port
purely for inter-server chat). Whatever moves bytes between two OJP processes
must travel through the same code path a normal JDBC application already uses.

This document is a **design-only analysis**. It does not implement RAFT, a
cache-invalidation feature, or a restart-notification feature — it defines the
messaging substrate those features (and future ones) could be built on, and
records the options considered, with pros/cons of each, and my own opinions,
concerns and open questions.

---

## 1. Use Cases (context, not the object of this analysis)

| # | Use case | Direction | Fan-out | Rough reliability need |
|---|---|---|---|---|
| 1 | RAFT leader-election / consensus messages | server ↔ server | 1-to-N (cluster) | Fire-and-forget (RAFT is designed to tolerate loss/duplication; it re-tries at the protocol level) |
| 2 | Cache invalidation broadcast | server → servers | 1-to-N (cluster) | Fire-and-forget, best-effort, idempotent |
| 3 | "Server is restarting" notice | server → clients | 1-to-N (all sessions attached to that server) | Best-effort but **should be attempted with an ack/retry** because clients act on it to avoid failed in-flight work |

These three are **examples used to validate the design**, not requirements to
special-case in the protocol itself. The protocol described below is generic:
topic + payload + delivery-mode, so any future use case (metrics gossip,
config propagation, distributed lock notifications, admission-control
back-pressure signals, etc.) can reuse it without a protocol change.

---

## 2. What already exists that we can build on

Understanding today's transport is essential, because the constraint forces
us to reuse it rather than invent something parallel to it.

- **`ojp-grpc-commons`** defines the wire contract (`StatementService.proto`).
  Today it exposes only JDBC-shaped RPCs: `executeQuery` (server-streaming),
  `createLob`/`readLob` (bidi/streaming), and various unary statement calls.
  There is **no existing server→client push channel** and **no existing
  server→server channel** at all.
- **`ConnectionDetails`** (sent by the driver on connect) already carries
  `repeated string serverEndpoints` and a `clusterHealth` string. This is the
  seed of cluster awareness: the driver already tells a server about its
  peers, and a server already reports back a health summary. This is a strong
  hint that some cluster-topology plumbing is already anticipated, just not a
  generic messaging layer.
- **Multinode driver** (`ojp-jdbc-driver`) already implements: a JDBC URL that
  addresses multiple OJP servers (`jdbc:ojp[host1:port1,host2:port2]_url`),
  load-aware server selection, health-checked failover, and retry/backoff.
  This is exactly the kind of resilient client machinery a messaging layer
  would otherwise have to reinvent.
- **Circuit breaker** on the driver side (60s default timeout) already exists
  to protect the driver from a wedged server.

**Conclusion:** the driver is already a general-purpose "resilient RPC client
to one-or-more OJP servers." That is precisely the building block the
constraint asks us to reuse: *the driver is not just a JDBC feature, it is
OJP's client-side RPC and failover library.* We should lean on it rather than
duplicate its retry/failover/health logic in a second, parallel component.

---

## 3. Requirements

### 3.1 Functional

- Publish a message to a **topic** (free-form string, e.g. `raft.election`,
  `cache.invalidate`, `server.lifecycle`), with an opaque `byte[]` payload.
- Subscribe to a topic and receive messages as they are published.
- Support **broadcast** (all servers in the cluster / all sessions on a
  server) and, later, **point-to-point** (a specific server or client) —
  the analysis should not preclude point-to-point even though the 3 examples
  are all broadcast-shaped.
- Two delivery modes, selectable per publish call:
  - **Fire-and-forget**: best-effort, at-most-once, no retry, no ack.
  - **Guaranteed delivery**: at-least-once, with ack + retry + de-duplication
    on the receiving side.

### 3.2 Non-functional

- Must not require clients or servers to open any new network endpoint.
- Must not require a third-party broker (Kafka/RabbitMQ/NATS) as a hard
  dependency, to keep OJP's "single jar, no extra infra" deployment model
  intact — see Option D below for why this was rejected as the *default*.
- Low latency is important for RAFT (election timeouts are usually
  150–300ms); the protocol must not add its own buffering/batching delay for
  that topic class.
- Must survive individual server restarts without becoming a single point of
  failure (i.e., messaging must not depend on one "coordinator" server that,
  if down, blocks messaging for everyone).
- Security/auth must reuse whatever the driver already does to authenticate
  to a server (this analysis assumes credentials configured for
  server-to-server publishing, see §8 Open Questions).

---

## 4. Options Considered

### Option A — Piggyback on existing `StatementService` RPCs

Encode messages as SQL-shaped calls, e.g. `driver.execute("CALL
ojp_internal_publish(topic, payload)")`, using the JDBC driver exactly as an
application would, no new proto messages needed.

**Pros**
- Zero new wire protocol; ships immediately.
- Reuses 100% of the existing driver code path (auth, pooling, failover).

**Cons**
- Abuses SQL semantics for a non-SQL concern; every server/database
  dialect quirk (statement parsing, prepared-statement caching, the SQL
  enhancer, slow-query segregation) sits in the way of a message that has
  nothing to do with SQL. High risk of accidental interaction with unrelated
  features (e.g. slow-query classification would "learn" about a fake SQL
  statement).
- No server-streaming push to clients (a client would have to poll).
- Feels like a hack that will be confusing to future maintainers reading
  `StatementService`.

**Verdict:** rejected as the general mechanism, but the "pretend it's a
statement" trick is a reasonable *emergency fallback* for a single unary
control message if we ever need one without waiting for a proto change.

### Option B — Extend `ConnectionDetails`/handshake fields only

Keep growing ad-hoc fields like `clusterHealth` for whatever cluster
information is needed (e.g. add a `pendingLeaderVotes` field, a
`restartScheduled` bool, etc.).

**Pros**
- Simplest possible change for a single, rarely-changing piece of state.
- Already precedented in the codebase (`clusterHealth`).

**Cons**
- Not a messaging system — it is a snapshot exchanged only at connect time
  (and maybe re-sent on later requests). No push, no topics, no ordering,
  no ack. Does not scale to "N kinds of messages"; every new use case adds
  another ad-hoc field forever, which is precisely the "not generic" outcome
  we're trying to avoid.

**Verdict:** rejected as the primary mechanism; fine as a narrow special case
for *coarse, low-frequency, last-value-wins* state (see §7, where I suggest
keeping `clusterHealth` for that purpose and not routing it through the new
messaging layer).

### Option C — New dedicated `MessagingService` gRPC contract, transported by reusing the driver's client library code, not a JDBC `Connection`

Add a new proto service (e.g. `MessagingService`) in `ojp-grpc-commons`:

```proto
service MessagingService {
  rpc Publish (PublishRequest) returns (PublishAck);
  rpc Subscribe (SubscribeRequest) returns (stream Envelope);
}
```

Any process that needs to send a message — including an OJP server acting on
behalf of RAFT or cache invalidation — does so **by reusing the same
low-level client-side building blocks the driver is made of** (gRPC channel
management, retries, circuit breaker, multinode/health awareness), just
invoking `Publish`/`Subscribe` instead of `executeQuery`. A subscribing side
(client or peer server) opens the `Subscribe` stream once and keeps it open;
the server pushes `Envelope` messages on it as they are published. Because
`Subscribe` is a **server-streaming RPC initiated by the subscriber**, no
participant ever needs to accept an inbound connection it doesn't already
accept — it's the same call shape already used by `executeQuery`.

#### What "embedding the driver" concretely means (clarifying feedback on the first draft)

The first draft of this analysis said an OJP server would hold "a small
internal pool of driver client instances pointed at its peers," which was
too vague and reads as messier than intended. To be concrete:

- **It does *not* mean an OJP server calls `DriverManager.getConnection("jdbc:ojp[...]_...")`
  or otherwise goes through `java.sql.*` / `ojp-jdbc-driver`'s public
  `java.sql.Driver` entry point.** That entry point is for applications; it
  parses JDBC URLs, wraps a `java.sql.Connection`, etc. — machinery an OJP
  server has no reason to instantiate just to send a gRPC message to a peer.
- **What it does mean:** the driver's *internal* gRPC-plumbing classes —
  concretely, things like `GrpcChannelFactory` (already in
  `ojp-grpc-commons`, so already shared/available to both driver and server
  today), the retry/circuit-breaker logic, and the multinode
  connect/health-check bookkeeping in `MultinodeConnectionManager` — are
  reused as a **plain internal library dependency**, the same way
  `StatementServiceGrpcClient` reuses `GrpcChannelFactory` today. A new,
  small `MessagingServiceGrpcClient` class (living in `ojp-grpc-commons` or a
  new thin shared module) would wrap a `MessagingServiceGrpc` stub using
  that same channel-management code, and `ojp-server` would depend on that
  class the way it already depends on other `ojp-grpc-commons` classes. This
  keeps the "no bespoke second wire protocol" property without literally
  spinning up a JDBC `Connection` object inside the server process.
- Practically, per configured peer this is: **one long-lived `ManagedChannel`
  + one `MessagingServiceGrpc` stub + one open `Subscribe` stream**, held by
  a small, purpose-built component inside `ojp-server` (e.g. a
  `PeerMessagingClient`), not a `java.sql.Connection`, not a connection
  pool in the HikariCP sense, and not the JDBC driver's public API surface.
  "Pool" in the original wording was a poor choice of word — there is
  exactly one channel per peer, not a pool of interchangeable connections to
  the same peer.

#### This must be opt-in and off by default

This is a firm requirement, not just a preference: **by default, an OJP
server must not attempt to connect to any other OJP server.** Standalone,
single-server OJP deployments (almost certainly the majority of current
installs) must see zero behavior change — no new outbound connection
attempts, no new config required, no new failure mode from an unreachable
peer that was never supposed to exist.

Concretely:
- A new server setting, e.g. `ojp.server.mesh.enabled` (default `false`),
  gates the entire feature. When `false`, `ojp-server` never constructs a
  `PeerMessagingClient`, never reads a peer list, and the `MessagingService`
  RPC handlers can simply be registered but will have no server-side
  subscribers among peers (irrelevant, since nothing calls out to them).
- The peer list itself (e.g. `ojp.server.mesh.peers=host1:port1,host2:port2`)
  is only read/validated when `ojp.server.mesh.enabled=true`. No peer
  discovery, DNS lookups, or connection attempts happen otherwise.
- This mirrors how other optional OJP server features are already gated —
  e.g. slow-query segregation is `ojp.server.slowQuerySegregation.enabled`,
  defaulting to `false`, with zero behavioral change until explicitly turned
  on (see `documents/designs/SLOW_QUERY_SEGREGATION.md`). The messaging mesh
  should follow the exact same pattern for consistency.
- RAFT/cache-invalidation/restart-notice are themselves all-optional
  features that *depend on* `ojp.server.mesh.enabled=true`; none of them
  should force the flag on implicitly. If a future feature absolutely
  requires the mesh, that should be a clear, explicit validation error at
  startup ("feature X requires ojp.server.mesh.enabled=true"), not an
  automatic silent activation.

For server-to-server messaging: once enabled, each OJP server holds one
`PeerMessagingClient` (one channel + one subscribe stream) per configured
peer, using the `ojp.server.mesh.peers` setting described above (same
`host:port` shape as `serverEndpoints`, but a distinct, server-configured
list — see the correction below for why). From the receiving server's
point of view, a peer server is indistinguishable from any other
`MessagingService` caller — there is no new "server-to-server" concept in
the wire protocol, only "the same client-side gRPC plumbing the driver uses,
linked into the server process and activated only when explicitly
configured."

> **Correction to §6.1/§8.8 of the previous revision:** those sections
> referred to peers being discovered "the same way multinode driver
> instances are discovered today... peer list is exactly `serverEndpoints`."
> On reflection that overstated the reuse and conflicts with "off by
> default": `serverEndpoints` is populated by *clients* today (in
> `ConnectionDetails`, at connect time) to tell a server about its multinode
> siblings for client-failover purposes, and it is only ever populated when
> a client actually connects with a multinode URL — which is exactly the
> "clients might all be off" scenario this thread is about. The mesh peer
> list must instead be **its own explicit, server-side configuration
> setting**, independent of whether/how any client connects. §5.3 and §6.1
> below are corrected accordingly.

**Pros**
- Clean, explicit contract; easy to test and to reason about;
  self-documenting.
- Reuses the driver's proven client-side building blocks (retry, circuit
  breaker, channel management) as an internal library dependency, without
  dragging in the public JDBC API surface that has no purpose here.
- `Subscribe` as a long-lived server-streaming call is the natural gRPC
  pattern for server→client push, and is architecturally identical to what
  `executeQuery` already does (stream of `OpResult`), so it's not a new kind
  of risk for the codebase.
- Supports both delivery modes cleanly (see §5).
- Extensible: new topics require zero protocol changes.
- Fully opt-in: zero impact, zero new connections, for the default
  single-server (or client-failover-only multinode) deployment.

**Cons**
- New proto surface to design well up front (`Envelope` schema, topic
  naming, ack semantics) — mistakes here are expensive to change later
  because of backward-compatibility rules already noted for `.proto` files
  in this repo.
- Every OJP server becomes a client of every other *configured* peer OJP
  server (mesh), which means server count² channels in the worst case when
  enabled; needs sane bounds/validation on peer list size (see §8).
- A new, explicit server-side configuration surface (peer list, enable
  flag) that operators must set up correctly for multi-server features to
  work — this is deliberate (see "opt-in" above) but is still an added
  operational step compared to "it just works."
- Slightly more moving parts than Option A/B for a first cut.

**Verdict: recommended.** It is the only option that is both generic and
compliant with the "reuse the driver" constraint without abusing SQL
semantics, provided it is implemented as an explicitly opt-in feature.

### Option D — External message broker (Kafka, RabbitMQ, NATS, Redis pub/sub)

**Pros**
- Batteries-included durability, ordering, consumer groups, replay.
- Well-understood operationally at scale.

**Cons**
- Directly contradicts the constraint (this would be "connecting the OJP
  servers to each other" via a third system instead of via the driver) and
  adds a mandatory piece of infrastructure to what is currently a
  zero-dependency proxy deployment (`ojp-server` is meant to be a single
  jar). This would also be a governance/licensing surface to vet.

**Verdict: rejected**, explicitly out of scope per the problem statement.
Mentioned only for completeness/contrast.

### Option E — Direct server-to-server gRPC/TCP link (classic RAFT transport)

This is the "obvious" way most RAFT implementations (etcd, Consul) do it:
each node opens its own RPC/socket to its peers, independent of any client
driver.

**Verdict: explicitly disallowed** by the problem statement. Listed here
only so the rejection is on record and its absence doesn't look like an
oversight: this is the default architecture in virtually every reference
RAFT implementation, and *not* doing it is a deliberate, constraint-driven
deviation with real trade-offs (see §8 concerns).

---

## 5. Recommended Protocol Shape (Option C, detailed)

### 5.1 Envelope

```proto
message Envelope {
  string   message_id      = 1;  // UUID, for de-dup / ack correlation
  string   topic           = 2;  // e.g. "raft.election", "cache.invalidate", "server.lifecycle"
  bytes    payload         = 3;  // opaque; producer/consumer agree on encoding
  string   producer_id     = 4;  // server/client identity, for loop-avoidance and auditing
  int64    produced_at_ms  = 5;
  DeliveryMode delivery_mode = 6;
  int32    ttl_seconds     = 7;  // optional expiry, mainly for guaranteed mode retries
}

enum DeliveryMode {
  FIRE_AND_FORGET = 0;
  GUARANTEED      = 1;
}

message PublishRequest {
  Envelope envelope = 1;
  // target_scope left unset = broadcast to all current subscribers of the topic;
  // set = point-to-point to a single producer_id (future-proofing, not needed by the 3 examples)
  string target_id = 2;
}

message PublishAck {
  string message_id = 1;
  bool   accepted   = 2; // true once durably queued for guaranteed mode, or immediately for fire-and-forget
}

message SubscribeRequest {
  repeated string topics = 1;
  string subscriber_id = 2;
}
```

`Subscribe` returns `stream Envelope`. For `GUARANTEED` messages, the
subscriber sends a small separate unary `Ack(message_id)` call (not modeled
above in full) once it has durably processed the message; the publisher side
retries un-acked messages with backoff until ack or `ttl_seconds` expiry.

### 5.2 Delivery modes

| | Fire-and-forget | Guaranteed delivery |
|---|---|---|
| Semantics | At-most-once | At-least-once |
| Ack required | No | Yes (`Ack(message_id)`) |
| Retry | None | Exponential backoff, bounded by `ttl_seconds` |
| De-duplication | N/A | Consumer keeps a short-lived seen-set of `message_id` (LRU/TTL cache — reuse Caffeine, already an OJP dependency per ADR-008) |
| Ordering | Best-effort, no guarantee | Per-producer, per-topic ordering only (see below); no global total order |
| Where it's used here | RAFT messages, cache invalidation | "server restarting" notice |
| Failure mode if peer is down | Message silently dropped | Buffered/retried up to `ttl_seconds`, then dropped with a log/metric — **not** durable across a publisher restart in the first iteration (see §8) |

**Ordering note:** true total ordering across a cluster requires a
sequencer/consensus mechanism of its own — which is circular for the RAFT
use case (you can't use total order to bootstrap the thing that produces
total order). This protocol therefore only promises **per-(producer,
topic) FIFO ordering** on a single subscribe stream, which is sufficient
for RAFT (each node's own message stream is ordered) and for cache
invalidation (order between different producers' invalidations usually
doesn't matter because invalidation is idempotent).

### 5.3 Server-to-server topology

- **Disabled by default.** This entire subsection describes behavior gated
  behind a new server setting, e.g. `ojp.server.mesh.enabled=false` by
  default. When disabled, none of the below happens: no peer list is read,
  no channel is opened, no outbound connection is attempted. A default,
  single-server (or client-only-multinode) OJP deployment sees no change.
- Peers are discovered from a **dedicated, explicit server-side setting**
  (e.g. `ojp.server.mesh.peers=host1:port1,host2:port2`), set independently
  by the operator on each node — this is deliberately *not* the same list as
  `serverEndpoints`. `serverEndpoints` is populated by *clients* at connect
  time (part of `ConnectionDetails`) to tell a server about its multinode
  siblings for client failover; it only exists while/because a client
  connected with a multinode URL, which is the opposite of "must work when
  all clients are off." The mesh's peer list must be known to the server
  independently of any client ever connecting.
- Once enabled, each server holds exactly **one long-lived channel + one
  `Subscribe` stream per configured peer** (mesh: N servers → N-1 outbound
  connections each), managed by a small dedicated component (see §5.2's
  "what embedding the driver means" discussion) — not a connection pool, one
  channel per peer. This is a bounded, small number in realistic OJP
  deployments (tens of nodes at most), so a full mesh is acceptable; if OJP
  ever targets hundreds of nodes, gossip-based fan-out would be needed
  instead (flagged as a non-goal for now, see §9).
- **This is the server-to-server option**, and it is driven entirely by
  server-side lifecycle/configuration (peer list), not by application client
  activity: once enabled, a server establishes and maintains these peer
  streams from its own startup regardless of whether any JDBC client is
  currently connected. This is what keeps RAFT/cache-invalidation working in
  serverless deployments where application clients scale to zero for long
  stretches — see §6.1 for the detailed reasoning.
- Publishing from a server to "the cluster" is a local fan-out: the local
  `MessagingService` implementation receives one `Publish` call and pushes
  the `Envelope` to every currently-connected `Subscribe` stream (peers and,
  for client-directed topics, connected client sessions) whose subscription
  matches the topic.

### 5.4 Server-to-client topology

- The driver, after establishing its normal JDBC connection, additionally
  opens (or lazily opens, on first use) a `Subscribe` stream for topics it
  cares about (initially just `server.lifecycle`).
- The server pushes a `server.lifecycle` envelope (e.g. `{"event":
  "restarting", "graceMs": 30000}`) to every subscribed client session when
  a graceful shutdown begins.
- The driver surfaces this to the application as a `SQLWarning` on the
  connection (there is already a documented pattern for propagating server
  conditions to `SQLWarning`, see `SQLWARNING_FULL_TRANSFER.md`) and/or a
  driver-level listener callback for applications that want to react
  programmatically (e.g. stop sending new requests, drain in-flight work).
  Reusing `SQLWarning` avoids inventing a whole new client-facing API for a
  notification that is fundamentally advisory.

### 5.5 Message flow diagrams — Mesh OFF vs. Mesh ON

The two diagrams below make the difference between the two modes from §5.3
concrete: **Mesh OFF** is the default for every OJP deployment; **Mesh ON**
is an explicit opt-in (`ojp.server.mesh.enabled=true` + a configured peer
list) needed only when servers must exchange messages with each other
(RAFT, cross-server cache invalidation) independently of any client, e.g.
serverless client tiers (§6.1).

#### Topology comparison

```mermaid
graph LR
    subgraph "Mesh OFF (default)"
        C1[App Client] -->|Subscribe / Publish| S1[OJP Server 1]
        C2[App Client] -->|Subscribe / Publish| S2[OJP Server 2]
        S1 -.->|no connection attempted| S2
    end
```

```mermaid
graph LR
    subgraph "Mesh ON (opt-in: ojp.server.mesh.enabled=true)"
        C3[App Client] -->|Subscribe / Publish| S3[OJP Server 1]
        C4[App Client] -->|Subscribe / Publish| S4[OJP Server 2]
        S3 <-->|MessagingService: Publish/Subscribe over configured ojp.server.mesh.peers| S4
    end
```

Note both subgraphs use the **same** `MessagingService` contract end to end —
"Mesh ON" does not add a different protocol, it just means the server also
acts as a `MessagingService` client of its peers, using the shared gRPC
plumbing described in §5.2, in addition to serving its own clients.

#### Mesh OFF — sequence flow (default behavior)

With the mesh disabled, `ojp-server` never reads a peer list and never
dials another server. Messaging only flows between a server and the
clients connected *to that specific server*. A topic that is meant to
coordinate the whole cluster (e.g. `cache.invalidate`) only reaches clients
of the server that received the `Publish` call — there is no cross-server
fan-out.

```mermaid
sequenceDiagram
    participant ClientA as App Client (on Server 1)
    participant S1 as OJP Server 1
    participant S2 as OJP Server 2
    participant ClientB as App Client (on Server 2)

    Note over S1,S2: ojp.server.mesh.enabled=false (default) — S1 and S2 never dial each other

    ClientA->>S1: Subscribe(topics=["cache.invalidate","server.lifecycle"])
    ClientB->>S2: Subscribe(topics=["cache.invalidate","server.lifecycle"])

    ClientA->>S1: Publish(topic="cache.invalidate", FIRE_AND_FORGET)
    S1-->>ClientA: PublishAck(accepted=true)
    S1->>ClientA: Envelope(cache.invalidate)  Note: local fan-out only

    Note over S2: Server 2 and its clients never receive this message —<br/>no peer link exists to carry it there

    S1->>S1: Begin graceful shutdown
    S1->>ClientA: Envelope(server.lifecycle, "restarting", GUARANTEED)
    ClientA-->>S1: Ack(message_id)
```

**Implication (worth calling out explicitly):** with the mesh off, any use
case that needs cluster-wide consistency (cache invalidation across all
servers, RAFT) is **not achieved** unless every server happens to be
reached directly by a client that independently publishes to it, or unless
the mesh is turned on. Mesh OFF is only sufficient for the pure
server-to-*its-own*-clients use case (`server.lifecycle`) or for
single-server deployments. This should be documented plainly for operators
so nobody assumes cache invalidation is cluster-wide by default when it
isn't.

#### Mesh ON — sequence flow (opt-in)

With the mesh enabled and peers configured, each server also holds a
`PeerMessagingClient` per peer, opened at server startup regardless of
client activity. A `Publish` call now fans out both to the server's own
connected clients *and* to its peers, which in turn fan out to their own
connected clients (and, if relevant, further peers already covered by the
full mesh so no re-forwarding loop is needed — see the loop-avoidance note
below).

```mermaid
sequenceDiagram
    participant ClientA as App Client (on Server 1)
    participant S1 as OJP Server 1
    participant S2 as OJP Server 2
    participant ClientB as App Client (on Server 2)

    Note over S1,S2: ojp.server.mesh.enabled=true, ojp.server.mesh.peers configured on both nodes

    S1->>S2: Subscribe(topics=["raft.election","cache.invalidate"]) [at S1 startup, no client involved]
    S2->>S1: Subscribe(topics=["raft.election","cache.invalidate"]) [at S2 startup, no client involved]

    ClientA->>S1: Subscribe(topics=["cache.invalidate","server.lifecycle"])
    ClientB->>S2: Subscribe(topics=["cache.invalidate","server.lifecycle"])

    ClientA->>S1: Publish(topic="cache.invalidate", FIRE_AND_FORGET)
    S1-->>ClientA: PublishAck(accepted=true)
    S1->>ClientA: Envelope(cache.invalidate)  Note: local fan-out
    S1->>S2: Envelope(cache.invalidate)        Note: mesh fan-out (producer_id=S1)
    S2->>ClientB: Envelope(cache.invalidate)   Note: S2 relays to its own clients

    Note over S1,S2: RAFT example — no client involved at all
    S2->>S1: Envelope(topic="raft.election", FIRE_AND_FORGET)
    S1->>S1: Process vote request, update local RAFT state

    Note over S1: Server 1 begins graceful shutdown
    S1->>ClientA: Envelope(server.lifecycle, "restarting", GUARANTEED)
    ClientA-->>S1: Ack(message_id)
```

**Loop-avoidance note:** because §5.3 already assumes a full mesh (every
server subscribes directly to every other configured peer), a receiving
server only needs to fan a peer-originated `Envelope` out to its *own*
connected clients, never re-publish it back onto the mesh — `producer_id`
lets a receiver recognize and drop an `Envelope` it produced itself (guards
against any accidental echo) but no further re-broadcast logic is needed
given the full-mesh topology. If OJP ever moves to gossip-based fan-out
for larger clusters (§8.1), this would need actual hop-count/seen-set
loop-avoidance — flagged there, not solved here.

---

## 6. Mapping back to the 3 example use cases

| Use case | Topic | Mode | Notes |
|---|---|---|---|
| RAFT consensus | `raft.<cluster-id>.election` (or similar) | Fire-and-forget | RAFT already assumes an unreliable network and re-sends `RequestVote`/`AppendEntries` on timeout; making the transport "guaranteed" would add latency (ack round-trip) for no protocol benefit and could even mask real partitions from RAFT's own failure detector. |
| Cache invalidation | `cache.invalidate` | Fire-and-forget | Invalidation is idempotent and self-healing (a missed invalidation just means a slightly stale cache entry until the next write/TTL); guaranteed delivery adds cost with little benefit. Could optionally periodically re-broadcast a checksum/version as a belt-and-suspenders anti-entropy mechanism — not required for this analysis. |
| Server restarting | `server.lifecycle` | Guaranteed (to currently-connected clients only) | This is the one case where "the client acts differently because it got the message" (e.g. stop sending new statements, prepare to fail over), so an ack-and-retry within the shutdown grace period is worth the extra complexity. Note "guaranteed" here can only mean "guaranteed to currently-attached subscribers within the grace period" — a client that is disconnected at the moment of publish cannot be reached by this mechanism, only by the normal failover behavior of the multinode driver, which already exists independently of this new protocol. |

---

## 6.1 Serverless / zero-client deployments

This point needs to be made explicit, because it changes what "reuse the
driver, don't connect servers directly" actually means in practice.

**The problem:** In a serverless deployment (application instances scaled to
zero between invocations, e.g. AWS Lambda/Fargate/Knative-style workloads),
there can be long stretches of time — potentially most of the time — where
**zero application clients are connected to any OJP server**. RAFT
leader-election and cache invalidation are both needs of the OJP server
cluster itself, and both must keep working in that window. If the messaging
design's server-to-server path only worked *through* connected application
client sessions (e.g. "server A asks a connected client to relay a message
to server B"), it would break the moment all clients scale to zero — which
is unacceptable for RAFT in particular, since a cluster with no application
traffic still has to hold an election, detect leader failure, and replicate
whatever state RAFT protects.

**Good news / clarification:** the Option C design in §5.3 was already built
to not depend on this. The "reuse the driver" constraint in this analysis
was never about *piggybacking on application client connections* — it was
about *not inventing a second, bespoke wire protocol/socket for
server-to-server traffic*. Concretely: each OJP server reuses the driver's
internal gRPC client-plumbing (channel management, retry/circuit-breaker
logic — see §5.2 for exactly which classes) as a library dependency and uses
it to open a `Subscribe` (and call `Publish`) directly against its peer
servers' `MessagingService`, the same way `StatementServiceGrpcClient`
already uses that plumbing today. **No application client needs to be
connected, or ever connect, for this path to work**, and — per the "opt-in"
requirement below — nothing here runs at all unless an operator explicitly
enables it. So: **yes, this already is "an OJP server-to-server option"**,
just implemented as "server process links the driver's client-side gRPC
plumbing as a library and calls the peer with it" rather than as a raw
socket or a JDBC `Connection` — which is what satisfies the original
constraint without leaving serverless clusters unable to run
RAFT/cache-invalidation when idle.

That said, a few consequences of this are worth calling out because they
were understated in the original write-up:

1. **The mesh must be independent of, and outlive, any client traffic.**
   Each server should establish and maintain its peer `Subscribe` streams
   (with reconnect/backoff) as part of its own startup and health-check
   loop, not lazily "when a client first connects." Practically this means
   the peer-mesh client instances are a **server-owned background
   component**, not something built opportunistically off application
   sessions. This should be stated as an explicit requirement, not an
   implementation detail.
2. **"Client off" doesn't mean "server off."** Serverless here refers to the
   *application* tier scaling to zero; the OJP servers themselves are
   assumed to remain running (they are the proxy/pool layer, not the
   thing being scaled per-request). If OJP servers themselves were expected
   to scale to zero between requests (a true "serverless OJP server"), RAFT
   membership and leadership would need to be re-derived on every cold
   start, which is a much bigger problem than this analysis covers — my
   assumption (70% confidence) is that this is out of scope because OJP
   servers are long-lived connection-pool owners by design (ADR-003), and a
   pool that's destroyed on every scale-to-zero event defeats the purpose of
   OJP. **Question for the team: is there any scenario where the OJP
   *servers* (not just client apps) are expected to scale to zero, e.g. one
   OJP server per Lambda invocation?** If yes, this analysis's server-mesh
   design does not cover that case and would need rework (likely toward a
   stateless/external-coordination model, which starts to look like Option D
   again).
3. **Server-to-client topics (`server.lifecycle`) are naturally a no-op when
   no clients are connected**, which is fine — there is nothing to notify.
   No special handling needed there.
4. **Bootstrap ordering**: at cluster cold start (e.g. all OJP servers
   starting together, as might happen in an autoscaled/serverless-adjacent
   infra deployment), servers need to discover and connect to peers *before*
   any application client connects, otherwise RAFT can't elect a leader in
   time for the first request. This reinforces point 1: the peer mesh must
   be driven by its own dedicated server-side configuration (see §5.3 — a
   distinct `ojp.server.mesh.peers` setting, not `serverEndpoints`, since the
   latter is only populated by connecting clients), not by client activity.
5. **This does not conflict with "opt-in, off by default" (§5.2/§5.3).** A
   deployment that wants RAFT/cache-invalidation to survive a fully idle
   client tier must explicitly set `ojp.server.mesh.enabled=true` and
   configure the peer list on every node — the serverless scenario is a
   reason *to* enable the mesh, not a reason to make it default-on. Nothing
   about supporting this use case requires or justifies connecting OJP
   servers to each other by default.

**Net effect on the recommendation:** no change to the recommended option
(Option C) — but the analysis should have been explicit from the start that
the server-to-server mesh in §5.3 *is* the "OJP server-to-server option"
being asked about here, and that it is designed to work with zero connected
application clients, provided the OJP server processes themselves stay up.
I'm revising §5.3/§9 framing below to make this explicit rather than leaving
it implied.

---

## 7. What NOT to change

- `ConnectionDetails.clusterHealth` should stay as-is: a coarse, last-value
  snapshot exchanged at connection time. It should not be re-implemented on
  top of the new messaging layer; it solves a different problem (a client
  finding out server health when establishing a connection, before any
  subscription exists).
- No change to `executeQuery`/`createLob`/`readLob` semantics.

---

## 8. Concerns and Open Questions (opinions included)

1. **Mesh scaling.** A full N×(N-1) subscribe mesh is fine for the cluster
   sizes I'd expect OJP to run at (a handful to maybe a few dozen servers
   for connection-pool proxying). If there's an intention to run hundreds of
   OJP server instances, this design needs a gossip/fan-out tree instead of
   a full mesh — **question for the team: what cluster sizes are actually
   expected in production?** My default assumption is "small enough that a
   mesh is fine," medium confidence (60%) since I don't have real deployment
   numbers.
2. **Where does the new client-plumbing code/module live?** As clarified in
   §5.2, the server-side mesh does **not** add a compile-time dependency from
   `ojp-server` onto `ojp-jdbc-driver` — it reuses the lower-level gRPC
   channel/retry/circuit-breaker code that already lives in
   `ojp-grpc-commons` (shared by both today) plus a small new
   `MessagingServiceGrpc` client class that should also live in
   `ojp-grpc-commons` (or a new thin shared module) so both the driver and
   the server can depend on it symmetrically, exactly like they already both
   depend on `GrpcChannelFactory`. This avoids the `server → driver`
   dependency the first draft implied, which would have been an odd,
   one-directional coupling for a feature that isn't really about JDBC at
   all. Worth confirming this module placement explicitly as part of any
   implementation ADR, since "which module owns the new client class" is an
   easy thing to get inconsistent across a phased rollout.
3. **Authentication/authorization for server-to-server calls.** Application
   clients authenticate with DB credentials meant for the *target database*.
   A server-to-server messaging call has nothing to do with a database
   credential. We need a distinct, cluster-internal credential/shared-secret
   (or mTLS-based peer identity) so that `Publish`/`Subscribe` for internal
   topics (`raft.*`) can be restricted to known peer servers, and cannot be
   spoofed or eavesdropped by a regular JDBC application that happens to know
   the topic name. **Open question: does OJP already have any
   inter-server trust mechanism to build on (e.g. shared cluster secret,
   mTLS certs), or does this analysis need to also propose one?** I did not
   find one in the codebase; flagging this as a likely next analysis if this
   proceeds.
4. **Guaranteed delivery durability across a publisher crash.** As
   specified above, "guaranteed" only covers retries while the publisher
   process is alive. If the publishing server crashes mid-retry, queued
   guaranteed messages are lost. For the "restart notice" use case, this is
   acceptable (a crashed server can't announce a graceful restart anyway).
   If a future use case needs durability across a publisher crash, that
   requires a WAL/outbox persisted to disk — a meaningfully bigger feature
   than this analysis is scoped for and, in my opinion, would be a strong
   signal that a real broker (rejected in Option D) is actually the right
   tool, constraint notwithstanding. Worth flagging now rather than
   discovering it mid-implementation.
5. **Payload versioning/compatibility.** `payload` is opaque bytes; each
   topic's producers/consumers must agree on encoding (likely another small
   protobuf message per topic) and must handle version skew during rolling
   upgrades (an old server must not crash on a message from a newer one).
   Recommend: every per-topic payload message reserves a `schema_version`
   field from day one.
6. **Should `Subscribe` be one stream per topic-set or one stream total?**
   The proto above allows multiple topics per `Subscribe` call to avoid
   N streams for N topics on a single peer/client connection. I'd default to
   **one multiplexed stream per peer connection, N topics inside it**, to
   minimize the number of long-lived gRPC streams held open — gRPC streams
   are cheap but not free, and a small OJP server shouldn't hold hundreds of
   idle streams open per connected client. Confidence: medium-high (75%),
   based on general gRPC stream-cost guidance rather than OJP-specific
   measurement.
7. **Backpressure.** What happens if a subscriber's stream is slow/blocked
   and the publisher fans out to many subscribers? Recommend a bounded
   per-subscriber outbound queue with drop-oldest for `FIRE_AND_FORGET` and
   a small bounded retry queue with backpressure signaling (reject new
   `Publish` calls, i.e. explicit failure rather than unbounded memory
   growth) for `GUARANTEED`. This needs load testing before being called
   "done," which is out of scope for this analysis but should be an
   explicit acceptance criterion for implementation.
8. **Does "serverless" ever mean the OJP *servers* scale to zero, not just
   the application clients?** As discussed in §6.1, this design assumes OJP
   server processes are long-lived even when application clients are not,
   and the peer mesh is what keeps RAFT/cache-invalidation alive during
   client-less periods. If there's a deployment model where OJP servers
   themselves are ephemeral (spun up per request/invocation), the mesh-based
   design here does not apply and this would need a fundamentally different
   (likely externally-coordinated) approach. Flagging this as a question for
   the team rather than assuming an answer.

---

## 9. Suggested (high-level) implementation phasing

This is intentionally light — the ask was for analysis, not an implementation
plan — but a phased rollout is worth recording as a suggestion:

1. Add `MessagingService` to `ojp-grpc-commons`, implement server-side
   fan-out for `Subscribe`/`Publish` with `FIRE_AND_FORGET` only, no
   cross-server mesh yet — validate with the "cache invalidation" and
   "server restarting" use cases first since they are lower risk than RAFT.
2. Add the cluster-internal peer identity/credential mechanism (see
   Concern 3) before turning on any server-to-server traffic.
3. Enable the server-to-server mesh (`ojp.server.mesh.enabled=true`,
   servers using the shared client-plumbing to reach configured peers) and
   exercise it with cache invalidation broadcast.
4. Add `GUARANTEED` delivery mode (ack + retry + de-dup) and switch
   "server restarting" to it.
5. RAFT is the most complex and highest-risk consumer (correctness-critical,
   latency-sensitive); build/adopt it last, on top of a already-proven
   messaging substrate, likely evaluating an existing, well-tested Java RAFT
   library (e.g. an existing Raft implementation) rather than writing RAFT
   from scratch, using this messaging layer purely as its transport.

---

## 10. Summary Recommendation

Introduce a new, generic `MessagingService` gRPC contract (topic + opaque
payload + delivery mode), implemented as an addition to `ojp-grpc-commons`,
consumed by application clients through `ojp-jdbc-driver`, and consumed by
`ojp-server` itself (for the peer-to-peer mesh) by reusing the driver's
internal client-side gRPC plumbing as a plain library dependency — **not**
its public JDBC API — and only when explicitly enabled via
`ojp.server.mesh.enabled` (default `false`). This satisfies the "no direct
server-to-server link" constraint by construction (a peer server is just
another `MessagingService` caller, using the same channel/retry/circuit-
breaker code the driver already has), reuses everything that plumbing
already does well (retries, circuit breaking, channel management), stays
fully opt-in so a default OJP deployment sees no behavior change, and keeps
the three example use cases as thin, topic-specific consumers of one shared
substrate rather than three bespoke mechanisms.

My biggest open concern (see §8.3) is that this only really works cleanly
once there's a proper answer for **inter-server authentication** that's
distinct from the JDBC-target-database credentials the driver was originally
built to carry — I'd want that settled before writing any code.
