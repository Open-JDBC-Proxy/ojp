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

### Option C — New dedicated `MessagingService` gRPC contract, transported by the driver acting as a client of other servers

Add a new proto service (e.g. `MessagingService`) in `ojp-grpc-commons`:

```proto
service MessagingService {
  rpc Publish (PublishRequest) returns (PublishAck);
  rpc Subscribe (SubscribeRequest) returns (stream Envelope);
}
```

Any process that needs to send a message — including an OJP server acting on
behalf of RAFT or cache invalidation — does so **by embedding the same
`ojp-jdbc-driver` client machinery** (channel management, load-aware
selection, retries, circuit breaker, health checks) that a normal application
uses, just invoking `Publish`/`Subscribe` instead of `executeQuery`. A
subscribing side (client or peer server) opens the `Subscribe` stream once and
keeps it open; the server pushes `Envelope` messages on it as they are
published. Because `Subscribe` is a **server-streaming RPC initiated by the
subscriber**, no participant ever needs to accept an inbound connection it
doesn't already accept — it's the same call shape already used by
`executeQuery`.

For server-to-server messaging specifically: each OJP server also holds a
small internal pool of driver client instances pointed at its peers (the
peer list is exactly `serverEndpoints`, already exchanged today). From the
receiving server's point of view, a peer server is indistinguishable from
any other JDBC client calling `Publish`/`Subscribe` — there is no new
"server-to-server" concept in the protocol, only "the driver, used by a
server process instead of an application process." This is what satisfies
the constraint: OJP servers never open a new socket/API to talk to each
other, they reuse the driver as any other client would, over the connections
that already exist for that purpose.

**Pros**
- Clean, explicit contract; easy to test and to reason about;
  self-documenting.
- Reuses 100% of driver resiliency (retry, failover, circuit breaker,
  multinode awareness, TLS/auth) for free on both the "publish" and the
  "peer server as a client" side.
- `Subscribe` as a long-lived server-streaming call is the natural gRPC
  pattern for server→client push, and is architecturally identical to what
  `executeQuery` already does (stream of `OpResult`), so it's not a new kind
  of risk for the codebase.
- Supports both delivery modes cleanly (see §5).
- Extensible: new topics require zero protocol changes.

**Cons**
- New proto surface to design well up front (`Envelope` schema, topic
  naming, ack semantics) — mistakes here are expensive to change later
  because of backward-compatibility rules already noted for `.proto` files
  in this repo.
- Every OJP server becomes a client of every other OJP server (mesh),
  which means server count² driver-client instances in the worst case;
  needs sane connection reuse/pooling to avoid overhead at scale (see §8).
- Slightly more moving parts than Option A/B for a first cut.

**Verdict: recommended.** It is the only option that is both generic and
compliant with the "reuse the driver" constraint without abusing SQL
semantics.

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

- Peers are discovered the same way multinode driver instances are
  discovered today: from configuration (a peer list analogous to
  `serverEndpoints`), not via any new discovery protocol.
- Each server keeps a small set of long-lived driver-backed
  `Subscribe` streams open to its peers (mesh: N servers → N-1 outbound
  subscriptions each). This is a bounded, small number in realistic OJP
  deployments (tens of nodes at most), so a full mesh is acceptable; if OJP
  ever targets hundreds of nodes, gossip-based fan-out would be needed
  instead (flagged as a non-goal for now, see §9).
- **This is the server-to-server option**, and it is driven entirely by
  server-side lifecycle/configuration (peer list), not by application client
  activity: a server establishes and maintains these peer streams from its
  own startup regardless of whether any JDBC client is currently connected.
  This is what keeps RAFT/cache-invalidation working in serverless
  deployments where application clients scale to zero for long stretches —
  see §6.1 for the detailed reasoning.
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
server-to-server traffic*. Concretely: each OJP server embeds an
`ojp-jdbc-driver` client instance and uses it to open a `Subscribe`
(and call `Publish`) directly against its peer servers' `MessagingService`,
the same way any JDBC application does. **No application client needs to be
connected, or ever connect, for this path to work.** Server A is simply a
program that links `ojp-jdbc-driver` as a library and calls it — it does not
need an end user's `Connection` object to exist. So: **yes, this already is
"an OJP server-to-server option"**, just implemented as "server process
embeds the driver and calls the peer as a client" rather than as a raw
socket — which is what satisfies the original constraint without leaving
serverless clusters unable to run RAFT/cache-invalidation when idle.

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
   be driven by server lifecycle/config (peer list, analogous to
   `serverEndpoints`), not by client activity.

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
2. **"Reuse the driver" for server-to-server has a subtlety.** The driver
   was designed to be used by an *application*, with its own lifecycle
   (`DriverManager.getConnection`, connection pooling by the app or a
   framework). Using it *inside* `ojp-server` means `ojp-server` gains a
   compile-time dependency on `ojp-jdbc-driver`. That's a new module
   coupling that doesn't exist today (today the driver depends on
   `ojp-grpc-commons`, and the server depends on `ojp-grpc-commons`, but
   the server never depended on the driver). This is architecturally clean
   (no circular dependency: server → driver → grpc-commons) but is a real,
   visible change to the dependency graph and should be called out
   explicitly as a design decision (candidate ADR), not slipped in quietly.
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
3. Enable the server-to-server mesh (servers embedding driver clients
   pointed at peers) and exercise it with cache invalidation broadcast.
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
payload + delivery mode), implemented as an addition to
`ojp-grpc-commons`/`ojp-server`, and consumed on every side (application
clients, and OJP servers acting as clients of their peers) exclusively
through `ojp-jdbc-driver`'s existing connection/session/failover machinery.
This satisfies the "no direct server-to-server link" constraint by
construction (a server talking to another server is just another driver
client), reuses everything the driver already does well (pooling, retries,
circuit breaking, multinode failover, security), and keeps the three example
use cases as thin, topic-specific consumers of one shared substrate rather
than three bespoke mechanisms.

My biggest open concern (see §8.3) is that this only really works cleanly
once there's a proper answer for **inter-server authentication** that's
distinct from the JDBC-target-database credentials the driver was originally
built to carry — I'd want that settled before writing any code.
