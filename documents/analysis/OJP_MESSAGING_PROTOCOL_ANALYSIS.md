# OJP Generic Messaging Protocol — Analysis

## Question

OJP needs a generic-purpose way to exchange messages:

1. Between OJP servers (e.g. RAFT-style leader-election consensus messages).
2. From OJP servers to OJP servers (e.g. cache-invalidation broadcasts).
3. From an OJP server to the JDBC clients connected to it (e.g. "this server is
   restarting, please fail over").

**Constraint, and how review has refined it (read this before anything else
in the document):** the original framing of this constraint was: *"OJP
servers are not allowed to open a new, separate connection directly to each
other — no raw sockets, no second gRPC server-to-server link, no new
listening port purely for inter-server chat. Whatever moves bytes between
two OJP processes must travel through the same code path a normal JDBC
application already uses."* That framing is **no longer accurate** and this
whole document has been revised to stop implying it, per explicit review
feedback: a server-to-server option that opens its own connection, driven
entirely by server configuration and independent of any client, was
requested and added (§5.3.2, "direct mesh") specifically to cover cases
where no client can be relied upon to be present (serverless/idle-client
deployments, §6.1) or as a deliberate choice by an operator who prefers a
dedicated link over relaying through client processes. **The constraint
that actually holds, everywhere in this document, is narrower:**

- **By default, no new connection between OJP servers exists, at all,
  ever.** A standalone deployment, or one that never turns on the mesh, sees
  zero new outbound connections, zero new listening ports, and zero new
  network endpoints compared to today. This is what "reuse the driver"
  guarantees unconditionally, and it's satisfied by client-relay (§5.3.1)
  and server→client push (§5.4) alone.
- **When a server does need to reach another server directly (the opt-in
  direct mesh, §5.3.2), that link must still be built by reusing the
  driver's client-side gRPC machinery** (channel management, retry/circuit-
  breaker logic — the same code `ojp-jdbc-driver` is built from, see §5.2)
  **as a library, not as a bespoke second wire protocol, raw socket, or ad
  hoc RPC mechanism.** A server acting as a peer's `MessagingService` client
  is architecturally the same shape as an application acting as a server's
  `MessagingService` client — same proto contract, same channel-management
  code, same auth model (§5.3.4/§8.3) — just with the "application" being
  another OJP server process instead of a JDBC user. That is what "must
  travel through the same code path a normal JDBC application already uses"
  now means in practice: *the same client-side code*, not *literally only
  through an application's live session*.
- This direct link remains **opt-in** (`ojp.server.mesh.enabled`, default
  `false`) precisely because it's the one part of this design that puts a
  new connection on the wire between two OJP processes. Everything else in
  this document — client-relay and server→client push — rides entirely on
  connections a JDBC client already opened for its own purposes, with zero
  exceptions, by construction.

Getting this distinction right matters enough that it is repeated at the
top of §5.3, §5.3.2, and §6.1 as well, so a reader who jumps to any of
those sections independently gets the same, current framing.

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

- **By default**, must not require clients or servers to open any new
  network endpoint — satisfied unconditionally by client-relay (§5.3.1) and
  server→client push (§5.4), which is why they need no enable flag at all.
  The one deliberate, explicit, opt-in exception is the direct mesh
  (§5.3.2): once an operator sets `ojp.server.mesh.enabled=true`, servers do
  open new outbound connections to each other — that is the entire point of
  turning it on — but nothing about it happens unless an operator asks for
  it.
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
semantics, provided the peer mesh piece of it is implemented as an
explicitly opt-in feature. Note this "opt-in" requirement applies
specifically to the **direct peer mesh** described above; the same
`MessagingService` contract also underpins a second, always-on
server-to-server path — **client-relay**, via multinode clients — that
needs no enable flag at all. See §5.3 for the full picture of both.

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

### 5.1 Service contract, messages, and how pub/sub actually works

The previous revision of this section showed only the `Envelope`/`Publish`/
`Subscribe` *messages*, without the service definition itself or an
explanation of the mechanics — reviewed feedback asked for both, so here is
the full contract followed by a walk-through of what actually happens on a
`Publish` and on a `Subscribe`.

```proto
syntax = "proto3";

package org.openjproxy.grpc.messaging;

service MessagingService {
  // Unary call: publish one envelope. Returns as soon as the message is
  // locally accepted (queued for guaranteed-mode retry, or fire-and-forget
  // handed off) — it does NOT wait for subscribers to receive it.
  rpc Publish (PublishRequest) returns (PublishAck);

  // Server-streaming call, initiated by the subscriber (client or, in mesh
  // mode, a peer server) and kept open indefinitely. The server writes an
  // Envelope onto this stream every time a Publish matches one of the
  // subscribed topics. This is architecturally identical to how
  // `executeQuery` already streams `OpResult` back to the driver today —
  // no new RPC shape is introduced.
  rpc Subscribe (SubscribeRequest) returns (stream Envelope);

  // Unary call, used only for GUARANTEED delivery mode (§5.2): the
  // subscriber calls this once it has durably processed a message. The
  // publisher-side retry loop stops retrying that message_id once acked
  // (or once ttl_seconds expires, whichever comes first).
  rpc Ack (AckRequest) returns (AckResponse);
}

message Envelope {
  string   message_id      = 1;  // UUID, for de-dup / ack correlation
  string   topic           = 2;  // e.g. "raft.election", "cache.invalidate", "server.lifecycle"
  bytes    payload         = 3;  // opaque; producer/consumer agree on encoding
  string   producer_id     = 4;  // identity of the ORIGINAL producer (server or client), fixed for
                                 // the lifetime of the message — never rewritten by a relaying hop;
                                 // used for loop-avoidance and auditing (see §5.3.3/§8.9)
  int64    produced_at_ms  = 5;
  DeliveryMode delivery_mode = 6;
  int32    ttl_seconds     = 7;  // optional expiry, mainly for guaranteed mode retries
  bool     cluster_scope   = 8;  // true = intended for every server in the cluster, not just the
                                 // one that received the Publish call (see §5.3); false = local to
                                 // this server's own subscribers only (e.g. server.lifecycle)
  int32    max_relay_hops  = 9;  // only meaningful when cluster_scope=true and mesh is disabled
                                 // (client-relay mode, §5.3.1); bounds how many client-mediated
                                 // hops a message may travel before being dropped. Default: 1 (see
                                 // §5.3.1) — in the standard topology where every client is
                                 // multinode-connected to every server, one hop is already enough
                                 // to reach every server directly from wherever it was published,
                                 // so anything higher just adds redundant relay traffic for no
                                 // extra reach (the cascading-amplification concern raised in
                                 // review, see §5.3.1 and §8.9).
}

enum DeliveryMode {
  FIRE_AND_FORGET = 0;
  GUARANTEED      = 1;
}

message PublishRequest {
  Envelope envelope = 1;
  // target_id left unset = broadcast to all current subscribers of the topic on the
  // server that receives this call; set = point-to-point to a single subscriber_id
  // (future-proofing — see §5.3.4 for the point-to-point flow; not needed by the 3 examples)
  string target_id = 2;
}

message PublishAck {
  string message_id = 1;
  bool   accepted   = 2; // true once durably queued for guaranteed mode, or immediately for fire-and-forget
}

message SubscribeRequest {
  repeated string topics = 1;
  string subscriber_id = 2;   // stable identity of this subscriber (client connection id, or peer
                               // server id in mesh mode) — used as the fan-out target list and, for
                               // relaying clients, echoed back as part of loop-avoidance bookkeeping
}

message AckRequest {
  string message_id = 1;
  string subscriber_id = 2;
}

message AckResponse {
  bool acknowledged = 1;
}
```

**How `Subscribe` works, mechanically:** a subscriber (a JDBC driver
instance, or — in mesh mode — a peer server's `PeerMessagingClient`) opens
one `Subscribe` call with the list of topics it cares about and keeps that
gRPC stream open for as long as it's connected. The server-side
`MessagingService` implementation keeps an in-memory registry, conceptually
`Map<topic, Set<StreamObserver<Envelope>>>` (a bare-minimum pub/sub broker,
entirely in-process, no persistence): registering a subscriber is just
adding its `StreamObserver` to the set for each topic in its
`SubscribeRequest`; the entry is removed automatically when the stream
closes (client disconnect, server shutdown, network drop — the same
lifecycle a `Subscribe` stream already has for any other gRPC streaming
call in this codebase). There is no separate "subscription database" and no
durability for subscriptions — a subscriber that reconnects re-subscribes
from scratch, and simply misses anything published while it was
disconnected (this is why `GUARANTEED` mode exists for the one topic that
cares, `server.lifecycle` — see §5.2).

**How `Publish` works, mechanically:** `Publish` is a plain unary RPC. On
receipt, the server-side implementation does three things, in order:
1. **De-dup check.** Look up `envelope.message_id` in a short-lived seen-set
   (Caffeine, TTL-bounded — the same mechanism already used for
   `GUARANTEED` mode de-dup, §5.2). If already seen, return
   `PublishAck{accepted:false}` immediately and do nothing else — this is
   what makes it safe for the same message to arrive at a server more than
   once (e.g. from two different relaying clients, §5.3.1).
2. **Local fan-out.** Look up the topic in the subscriber registry above and
   write the `Envelope` onto every currently-open `Subscribe` stream
   registered for that topic — *every one of them*, not a single arbitrarily
   chosen subscriber (this directly answers the "does it go to all clients
   or one client" question raised in review — see §5.3.1 and §5.5 for the
   full explanation of why "all" is correct and necessary).
3. **Ack.** Return `PublishAck{accepted:true}` once step 2 has been handed
   off (fire-and-forget: as soon as the writes are enqueued; guaranteed: once
   the message is durably placed in the retry-tracking structure — this
   does not wait for any subscriber to actually receive it, only for the
   server to have committed to delivering/retrying it).

Everything else in §5.3–§5.5 (client-relay, direct mesh, server→client
push) is this same three-step `Publish`/`Subscribe` mechanism reused for
different populations of subscribers — a peer server, a relaying client, or
an application's own driver session are all, from the server's point of
view, just another `StreamObserver<Envelope>` in the same registry.

`Subscribe` returns `stream Envelope`. For `GUARANTEED` messages, the
subscriber calls the `Ack` RPC above once it has durably processed the
message; the publisher side retries un-acked messages with backoff until
ack or `ttl_seconds` expiry.

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

### 5.3 Server-to-server topology — two modes, not one

Earlier revisions of this analysis treated the direct peer mesh (§5.3.2
below) as *the* server-to-server option and described "mesh off" as simply
"servers don't talk to each other." That was incomplete. There are actually
**two distinct ways** a `Publish` on one server can reach another server,
and both are worth offering rather than presenting the mesh as the only
real mechanism:

| | 5.3.1 Client-relay | 5.3.2 Direct mesh |
|---|---|---|
| Controlled by | Automatic, no server-to-server config needed | `ojp.server.mesh.enabled=true` + `ojp.server.mesh.peers` |
| Default | **Yes — this is what happens today with zero extra config** | No — explicit opt-in |
| Transport | Existing multinode client sessions, used as a relay medium | A dedicated channel per configured peer |
| New server-to-server connection? | **None** | Yes (that's the whole point of enabling it) |
| Works with zero clients connected? | **No** | Yes |

#### 5.3.1 Client-relay mode (the default — no new connections at all)

This is the mechanism that directly answers "use the clients as a means to
communicate with other OJP servers": OJP already has a population of
clients that are simultaneously connected to more than one OJP server —
**multinode clients**, using the existing
`jdbc:ojp[host1:port1,host2:port2]_url` URL format (see
`documents/multinode/README.md`). A multinode client already holds an open
gRPC session to each server in its URL, primarily for load-aware routing
and failover. Nothing new needs to be opened for such a client to also
carry a message from one of "its" servers to another.

**Important correction on topology (raised in review):** the diagrams and
description in an earlier revision showed some clients connected to only a
single server. That is not the standard/expected shape of an OJP
deployment: **the normal case is every client is multinode-connected to
every server in the cluster** (a client only ends up talking to a subset of
servers under a network partition, a misconfiguration, or a deliberate
single-node URL, which is the exception, not the rule). This matters a lot
for how relay actually behaves, so it's corrected throughout §5.3–§5.5 from
here on: assume, unless stated otherwise, that the client population is
"every client, every server."

Mechanics:
- A multinode driver instance, in addition to its normal query traffic,
  keeps a `Subscribe` stream open on *every* server session it holds (this
  is already true regardless of relay — see §5.4) for whichever topics it
  or the application cares about.
- When a server's `Publish` handler does its local fan-out (§5.1, step 2),
  it writes the `Envelope` to **every one of its currently-subscribed
  clients** — not a single, arbitrarily chosen one. This is not optional:
  every one of those clients needs the message anyway if the topic is
  client-relevant (e.g. a client-side cache also wants to know about
  `cache.invalidate`), and it's also what makes relay coverage as good as it
  is — every one of those clients is a potential relay carrier to whichever
  other servers it also holds sessions to.
- Each client that receives an `Envelope` with `cluster_scope=true` and has
  not seen that `message_id` before (a small client-side Caffeine-backed
  seen-set, same de-dup mechanism as guaranteed delivery) re-publishes the
  same `Envelope` — unchanged, `message_id` preserved — on every *other*
  server session it holds, after decrementing `max_relay_hops`.
- **`max_relay_hops` defaults to 1, and this is a deliberate, load-bearing
  choice, not an arbitrary number.** In the standard "every client, every
  server" topology, one hop is already sufficient for a message published
  on any server to directly reach every other server: any given client
  holds a direct session to every server, so its one relay hop from the
  publishing server covers all of them in parallel, with no need for a
  second hop. Setting `max_relay_hops` any higher would not improve
  reach in the standard topology — it would only cause servers that
  receive a relayed message to have their *own* subscribed clients
  attempt to relay it *again*, which is the "cascading broadcast" risk
  flagged in review (see the amplification analysis right below). A value
  greater than 1 should be reserved for the non-standard case where the
  client population is known to be partitioned across disjoint groups of
  servers (i.e., no single client spans the full cluster), where extra hops
  can, in principle, bridge that gap at the cost of the additional
  amplification.
- From a receiving server's point of view, an incoming relayed `Publish` is
  indistinguishable from any other client `Publish` call — no protocol
  change, no new RPC.

**The cascading/amplification cost, made concrete (raised in review — this
needs an honest number, not a vague "might be expensive"):** with
`max_relay_hops=1` and the standard "every client, every server" topology,
publishing one `cluster_scope=true` envelope on server S with C clients
currently subscribed and N servers in the cluster produces:
- **1** local fan-out write per subscribed client on S (C writes — this
  part is unavoidable and desirable, every client needs to see it).
- Up to **C × (N-1)** relay `Publish` calls, because *every one* of those
  C clients independently attempts to relay the same message to every
  *other* server it holds a session to, not just one designated client.
  Each of those N-1 target servers therefore receives the same
  `message_id` up to C times.
- Each of those redundant deliveries is cheap to reject (§5.1's de-dup
  step 1 is a single seen-set lookup, not a fan-out), so this does **not**
  turn into a second wave of local fan-out at the receiving servers — but
  the C×(N-1) *attempted* unary RPCs are real network/CPU cost that scales
  with the client population, independent of how many servers actually
  need the message.
- **Concretely, this makes client-relay a poor fit for anything published
  frequently** (e.g. a RAFT heartbeat every 50–150ms — see §5.3.5 for why
  RAFT should not use this mode at all): with, say, 200 connected clients
  and a 5-server cluster, every heartbeat would attempt roughly 200 × 4 =
  800 redundant relay RPCs, every 50–150ms, cluster-wide — that scales with
  client count, not with the actual message rate a 5-node consensus group
  needs, which is a real, quantifiable problem, not a hypothetical one.
  For an occasional broadcast like `cache.invalidate` (expected to fire on
  writes, not on a fixed high-frequency timer), the same math produces an
  occasional burst rather than a sustained load, which is a materially
  different — and acceptable — cost profile.
- **Mitigation considered but not adopted for v1** (noted for completeness):
  electing a small subset of "relay-eligible" clients per topic (e.g. one
  per server-pair) would cut this from `O(C×N)` to `O(N)`, but doing that
  correctly requires its own coordination/election mechanism among clients
  — which is more complexity than this analysis wants to justify before
  the simpler default has even been tried, and ironically starts to need
  something consensus-like to coordinate, which is exactly the kind of
  problem this whole document is trying to solve for other use cases in the
  first place. Flagged as a possible v2 optimization, not a v1 requirement.

**When this is the right choice:** exactly the scenario in the original
feedback — deployments where opening a direct connection between OJP
servers is difficult, disallowed by network policy, or simply undesirable
(e.g. servers live in network segments that only accept inbound traffic
from application-side clients, not from each other). It is also
zero-configuration: it works as an automatic side-effect of multinode
client connectivity that already exists today, with no new peer list to
maintain. It is best suited to **low-frequency, idempotent, best-effort**
broadcasts (`cache.invalidate` is the model case) — not to anything
published on a tight timer or anything where the amplification above would
matter.

**When this is *not* the right choice:** anything that must be guaranteed to
work regardless of client population, or anything published frequently
enough that the amplification above becomes a real load concern — most
importantly RAFT, which is both. See §5.3.5 for the full, concrete rationale
(not just "less reliable"), and §6.1 for the serverless angle specifically.

#### 5.3.2 Direct mesh mode (opt-in, off by default)

- Gated behind `ojp.server.mesh.enabled` (default `false`). When disabled,
  none of this runs: no peer list is read, no channel is opened, no
  outbound connection is attempted. A default, single-server (or
  client-relay-only) OJP deployment sees no change.
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
  connections each), managed by a small dedicated component (see §4 Option
  C's "what embedding the driver means" discussion) — not a connection
  pool, one channel per peer. This is a bounded, small number in realistic
  OJP deployments (tens of nodes at most), so a full mesh is acceptable; if
  OJP ever targets hundreds of nodes, gossip-based fan-out would be needed
  instead (flagged as a non-goal for now, see §9).
- This mode is driven entirely by server-side lifecycle/configuration (peer
  list), not by application client activity: once enabled, a server
  establishes and maintains these peer streams from its own startup
  regardless of whether any JDBC client is currently connected. This is
  what keeps RAFT/cache-invalidation working in serverless deployments
  where application clients scale to zero for long stretches — see §6.1 for
  the detailed reasoning.
- Publishing from a server to "the cluster" is a local fan-out plus a mesh
  fan-out: the local `MessagingService` implementation receives one
  `Publish` call and pushes the `Envelope` to every currently-connected
  `Subscribe` stream (peers and, for client-directed topics, connected
  client sessions) whose subscription matches the topic. When mesh mode is
  active, `cluster_scope=true` envelopes go directly to peers over the mesh
  and do **not** additionally rely on client relay (both mechanisms are
  never needed at once for the same message — see §5.3.3).

#### 5.3.3 Comparison: client-relay vs. direct mesh, and how to choose

| | Client-relay (default) | Direct mesh (opt-in) |
|---|---|---|
| **New connections required** | None — reuses existing multinode client sessions | Yes — one channel per configured peer |
| **Delivery guarantee** | Best-effort; depends entirely on the current multinode client population forming a connected graph across the cluster. No bound on latency or coverage. | Deterministic: every configured peer is reached directly, independent of any client |
| **Works with zero clients connected** | **No** — no clients, no relay medium, no cross-server delivery at all | **Yes** — this is the whole point |
| **Good fit for** | Cache invalidation and other idempotent, self-healing, best-effort broadcasts, *especially* in deployments where a direct server-to-server link is hard, blocked, or undesirable | RAFT consensus and anything else that needs bounded-latency, guaranteed-to-attempt delivery regardless of client traffic (e.g. serverless client tiers, §6.1) |
| **Poor fit for** | RAFT — see below | Deployments where opening any new outbound connection between OJP processes is explicitly disallowed by network policy |
| **New server-side config** | None | Peer list + enable flag, maintained per node |
| **New trust/auth surface** | None beyond what already secures client↔server traffic (a relay hop is just a normal authenticated client `Publish` call) | Yes — needs its own inter-server credential/trust story (§8.3) |
| **Extra idle resource cost** | None when no multinode clients are connected; a small amount of relay bookkeeping (seen-set) on multinode clients that are connected | One idle channel + stream per configured peer, always, once enabled |
| **Puts new responsibility on** | The JDBC driver (an app-embedded library becomes a message relay for cluster-internal traffic — see the concern in §8.9 about whether that is appropriate) | `ojp-server` only (the driver's role doesn't change) |

**My recommendation, stated plainly:** ship both, because they solve
different problems, and default to client-relay because it costs nothing
and requires nothing from operators:

- **Client-relay (default) for `cache.invalidate`** and any other
  idempotent/best-effort cluster-wide topic. It is genuinely the option
  requested in the original feedback for "situations where opening a
  connection between OJP servers is difficult or not possible," and it
  requires zero new infrastructure.
- **Direct mesh, explicitly enabled, for RAFT.** RAFT should not run over
  client-relay. §5.3.5 below expands this into its own, fully-argued
  section (per review feedback that the previous one-paragraph version,
  hedged behind confidence percentages, wasn't a strong enough rationale on
  its own) — it lays out the concrete, honest reasons, including where
  RAFT's own tolerance for message loss does *not* save client-relay, and
  where it does.
- **Direct mesh is also the answer for serverless/idle-client
  deployments** (§6.1) — it's the only one of the two modes that keeps
  working when the client population (and therefore the client-relay
  medium) disappears entirely.

#### 5.3.4 Connection & channel inventory — what actually gets opened, where, and for which traffic pattern

Reviewed feedback asked for this to be spelled out concretely rather than
left implicit in the prose above: for each mode, what gRPC channels exist,
whether the relevant RPC is a stream or a unary call, whether any new
connection is opened (and if so, where), and — separately — how each of the
three traffic patterns implied by the use cases actually moves: broadcast
to all OJP servers, point-to-point to one specific OJP server, and
broadcast to all clients of a server.

```mermaid
graph TB
    subgraph "Mesh OFF (default) — channel inventory"
        direction LR
        CL1["Client 1<br/>(multinode)"]
        CL2["Client 2<br/>(multinode)"]
        SA["OJP Server A"]
        SB["OJP Server B"]
        SC["OJP Server C"]
        CL1 -->|"Subscribe (stream, long-lived)"| SA
        CL1 -->|"Subscribe (stream, long-lived)"| SB
        CL1 -->|"Subscribe (stream, long-lived)"| SC
        CL2 -->|"Subscribe (stream, long-lived)"| SA
        CL2 -->|"Subscribe (stream, long-lived)"| SB
        CL2 -->|"Subscribe (stream, long-lived)"| SC
        CL1 -.->|"Publish (unary, per message,<br/>relay hop only)"| SB
        CL1 -.->|"Publish (unary, per message,<br/>relay hop only)"| SC
        SA -.->|"no direct connection ever"| SB
        SA -.->|"no direct connection ever"| SC
    end
```

```mermaid
graph TB
    subgraph "Mesh ON (opt-in) — channel inventory, in addition to the above"
        direction LR
        SX["OJP Server A"]
        SY["OJP Server B"]
        SZ["OJP Server C"]
        SX <-->|"Subscribe (stream, long-lived,<br/>opened at server startup)"| SY
        SX <-->|"Subscribe (stream, long-lived,<br/>opened at server startup)"| SZ
        SY <-->|"Subscribe (stream, long-lived,<br/>opened at server startup)"| SZ
        SX -.->|"Publish (unary, per message)"| SY
        SX -.->|"Publish (unary, per message)"| SZ
    end
```

| | Mesh OFF (client-relay) | Mesh ON (direct mesh) |
|---|---|---|
| New channel opened, and by whom | None between servers, ever. Clients already open one `ManagedChannel` per server in their multinode URL (pre-existing, for query traffic) — the `Subscribe` stream reuses it. | Each server opens one `ManagedChannel` per configured peer, at server startup, held for the server's lifetime. |
| Is the relevant RPC a stream or unary? | `Subscribe` is a long-lived server-streaming RPC (one per client-per-server pair, opened once, kept open). `Publish` (including every relay hop) is a small unary RPC, one per message, on top of the existing channel. | Same shape: `Subscribe` is long-lived server-streaming (one per configured peer pair), `Publish` is unary, one per message. |
| Where is the new connection, if any? | Nowhere — zero new sockets anywhere in the system. | Between every pair of configured peer servers (N×(N-1) directed streams for a full mesh of N servers). |
| Who initiates `Subscribe`? | The client (already true today, for query load-balancing/health purposes; the relay use adds topics to the same call, not a new call). | Each server, against each of its peers, at its own startup — no client involved. |

**Broadcast to all OJP servers** (e.g. `cache.invalidate`):
- *Mesh OFF:* the publishing server does its local fan-out (§5.1, all
  locally-subscribed clients get the `Envelope` over their existing
  `Subscribe` streams). Independently, every one of those clients that is
  also multinode-connected to other servers issues a `Publish` (unary) to
  each of those other servers — this is the "relay" step from §5.3.1, and
  it is genuinely a broadcast-to-all-clients-then-each-client-broadcasts-
  onward, not a single point-to-point hop, which is exactly the
  amplification discussed in §5.3.1.
- *Mesh ON:* the publishing server's local fan-out happens exactly the same
  way; in addition, the server directly issues one `Publish` (unary) per
  configured peer, over the pre-existing peer channel — no client
  involvement, no amplification, exactly N-1 extra calls for an N-server
  mesh regardless of how many clients exist.

**Point-to-point to one specific OJP server** (a possible future need, not
required by the 3 examples, but explicitly designed for via
`PublishRequest.target_id`, §5.1):
- *Mesh OFF:* only achievable if some multinode client happens to hold
  sessions to both the origin and the target server; that client's relay
  hop uses `target_id` instead of a broadcast, and the target server's local
  fan-out (or, for a peer-only message, direct delivery to a specific
  `subscriber_id`) delivers it to exactly one destination. This is a strictly
  weaker guarantee than the mesh case below — if no client bridges those two
  specific servers at that moment, point-to-point delivery simply cannot
  happen at all.
- *Mesh ON:* trivial and deterministic — the origin server calls `Publish`
  with `target_id` set directly on its existing channel to that one peer.
- **Point-to-point is the strongest illustration of why RAFT (which
  fundamentally needs targeted `RequestVote`/`AppendEntries` RPCs to
  specific peers, not just broadcasts) cannot be built on client-relay
  alone** — see §5.3.5.

**Broadcast to all clients of one server** (`server.lifecycle`, and the
client-facing half of `cache.invalidate`):
- This never involves another server at all, mesh on or off — it is pure
  local fan-out (§5.1, step 2): the server writes the `Envelope` to every
  currently-subscribed client `Subscribe` stream on that server. This
  directly answers the review question of whether a mesh-off broadcast
  goes "to all its clients or to a single client": **to all of them,
  always** — sending to only one client would defeat the purpose (other
  clients need the notification too) and would also cut relay coverage down
  to whatever that one arbitrarily-chosen client happens to be connected to.

#### 5.3.5 Why RAFT must use the direct mesh — the concrete, honest argument

The previous revision compressed this into one paragraph with confidence
percentages and no real substantiation ("less secure, less reliable"),
which reviewed feedback correctly called out as not good enough for a "hard
product rule." This section gives the actual argument, grounded in what
RAFT is documented to tolerate and what it explicitly does not, rather than
restating the same conclusion with more hedging.

**Start from what RAFT genuinely tolerates — conceding the point, because
it's true and important:** the Raft paper ("In Search of an Understandable
Consensus Algorithm," Ongaro & Ousterhout, 2014) explicitly designs RAFT
for an asynchronous network where messages can be **arbitrarily delayed,
lost, duplicated, and reordered**, and proves its safety properties (at
most one leader per term, log matching, leader completeness) hold
regardless. RAFT RPCs are naturally idempotent and rely on retry rather
than reliable delivery. **So "client-relay might drop a message" is, on its
own, not the risk — RAFT is built to survive exactly that, and any
argument against client-relay that stops at "it's less reliable" is not
actually engaging with how RAFT works.** This is worth stating plainly
because the previous version of this document implied unreliability itself
was disqualifying, and that was not an honest characterization.

**The real, load-bearing arguments are two, and they are not about
best-effort delivery at all:**

1. **RAFT explicitly assumes a non-Byzantine (crash-only) failure model —
   client-relay silently breaks that assumption.** The Raft paper is
   explicit that it assumes servers "fail by stopping" and does not defend
   against arbitrary or malicious behavior; it is a crash-fault-tolerant
   protocol, not a Byzantine-fault-tolerant one (that's a different, much
   more expensive class of algorithm — e.g. PBFT). Concretely, this means
   RAFT's safety proofs assume every message a server acts on genuinely
   originated from one of the *known, fixed set of cluster members*, and
   was not forged, replayed out of context, or selectively manipulated by
   a third party. Client-relay, by construction, routes `raft.*` envelopes
   through arbitrary application JDBC driver processes — processes that
   were never part of the RAFT membership, are not vetted as trusted
   cluster participants, and are (by design) reachable by any application
   with valid database credentials. Nothing in the `MessagingService`
   contract as designed distinguishes "a message a real peer server
   produced and a client faithfully relayed" from "a message any
   authenticated application client crafted and published directly with a
   forged `producer_id`" — `Publish` is reachable the same way in both
   cases. **This is a genuine expansion of RAFT's trust perimeter from "the
   N configured servers" to "the N configured servers plus every currently
   connected application," which is exactly the assumption RAFT documents
   itself as not being designed to survive.** This is the crux of the
   argument, and it is a safety concern (a forged/duplicated vote grant or
   a spoofed heartbeat could genuinely violate RAFT's election-safety
   invariant), not merely a liveness inconvenience. The direct mesh doesn't
   automatically solve this either — it still needs its own inter-server
   auth story (§8.3) — but it at least keeps the trust perimeter to "the N
   configured servers," which is the perimeter RAFT is designed for,
   instead of silently widening it to include an uncontrolled, arbitrarily
   large population of application processes.
2. **Amplification cost is real and quantifiable, and it specifically
   defeats RAFT's timing model.** RAFT's liveness (not safety) depends on
   `broadcastTime << electionTimeout << MTBF` — heartbeats/`AppendEntries`
   typically fire every ~50–150ms, and the whole point is that this traffic
   is small, frequent, and predictable. §5.3.1 quantified client-relay's
   cost as up to `C × (N-1)` redundant relay RPCs per broadcast message,
   where C is the connected-client count — for RAFT's heartbeat frequency,
   this turns a deliberately lightweight, predictable protocol into a load
   that scales with *application* traffic, which RAFT was never designed to
   tolerate or even be aware of. This is a concrete, arithmetic argument,
   not a vague "less reliable."

**What RAFT's own loss-tolerance does and doesn't buy client-relay, stated
plainly:** it buys correctness of the *coverage* problem — an occasionally
missed heartbeat or vote due to a thin client-relay graph would, on its
own, only cost RAFT some liveness (a slower election, a retried
`AppendEntries`), which RAFT is explicitly built to absorb. It does **not**
buy anything against the trust-perimeter problem in argument 1, because
that's a different axis entirely (authenticity/integrity of what does
arrive, not whether everything arrives) — and that is the actual reason
this document treats "RAFT requires `ojp.server.mesh.enabled=true`" as a
hard rule rather than a tunable recommendation: it isn't about the odds of
message loss, it's about not letting RAFT's messages travel through a
transport whose trust boundary was never designed to match RAFT's own
non-Byzantine assumption.

**Confidence:** high (85%) that argument 1 (trust perimeter) is the
correct, defensible reason — it follows directly from RAFT's documented
crash-only failure model, not from speculation. High (80%) on argument 2
(amplification) since it's arithmetic given the numbers already in §5.3.1.
Lower confidence (55%) on precisely how *severe* an exploit of argument 1
would be in a specific deployment (that depends on how OJP ultimately
authenticates JDBC clients and how much an operator trusts their own
application fleet) — worth revisiting once OJP's inter-server/client
credential model (§8.3) is actually designed, rather than assumed here.

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

### 5.5 Message flow diagrams — Mesh OFF (client-relay) vs. Mesh ON (direct mesh)

The two diagrams below make the difference between the two modes from §5.3
concrete: **Mesh OFF** is the default for every OJP deployment and still
carries cluster-wide messages, via client relay, whenever a multinode
client happens to bridge the servers involved; **Mesh ON** is an explicit
opt-in (`ojp.server.mesh.enabled=true` + a configured peer list) that
removes the dependency on client connectivity entirely, needed for RAFT and
for serverless client tiers (§6.1).

**Topology correction (raised in review):** the sequence diagrams below now
show the **standard** topology — every client multinode-connected to every
server — rather than clients pinned to a single server as in the previous
revision, since single-server clients are the non-standard case (only seen
under a partial network partition or a deliberate single-node URL). This
also makes the "does a broadcast go to all clients or one client" question
unambiguous: local fan-out always targets **every** subscribed client of
the server that received the `Publish`, never a single arbitrarily-chosen
one, and (see the cascading note below) relaying stops after a single hop
in this topology because that one hop already reaches every server.

#### Topology comparison

```mermaid
graph LR
    subgraph "Mesh OFF (default) — every client connects to every server"
        CL1[Client 1] -->|Subscribe / Publish| S1[OJP Server 1]
        CL1 -->|Subscribe / Publish, also relays| S2[OJP Server 2]
        CL2[Client 2] -->|Subscribe / Publish| S1
        CL2 -->|Subscribe / Publish, also relays| S2
        S1 -.->|no direct connection, ever| S2
    end
```

```mermaid
graph LR
    subgraph "Mesh ON (opt-in: ojp.server.mesh.enabled=true)"
        CL3[Client 1] -->|Subscribe / Publish| S3[OJP Server 1]
        CL3 -->|Subscribe / Publish| S4[OJP Server 2]
        CL4[Client 2] -->|Subscribe / Publish| S3
        CL4 -->|Subscribe / Publish| S4
        S3 <-->|MessagingService: Publish/Subscribe over configured ojp.server.mesh.peers| S4
    end
```

Note both subgraphs use the **same** `MessagingService` contract end to end
— "Mesh ON" does not add a different protocol, it just removes the
dependency on a multinode client being present to carry the message; a
`Publish` call, an `Envelope`, and a `Subscribe` stream look identical to a
server whether the other end is a peer server or a relaying client. See
§5.3.4 for the full channel-by-channel inventory behind these diagrams.

#### Mesh OFF — sequence flow (default behavior, client-relay)

With the mesh disabled, `ojp-server` never reads a peer list and never
dials another server directly. Instead, every client is (in the standard
topology) multinode-connected to every server, already holds a `Subscribe`
stream open on each of them (§5.4), and acts as a relay carrier: when it
receives a `cluster_scope=true` `Envelope` it hasn't seen before, it
re-publishes that same envelope on its other server sessions.

```mermaid
sequenceDiagram
    participant Cl1 as Client 1 (connected to S1 and S2)
    participant Cl2 as Client 2 (connected to S1 and S2)
    participant S1 as OJP Server 1
    participant S2 as OJP Server 2

    Note over S1,S2: ojp.server.mesh.enabled=false (default) — S1 and S2 never dial each other
    Note over Cl1,Cl2: Standard topology — every client is multinode-connected to every server

    Cl1->>S1: Subscribe(topics=["cache.invalidate","server.lifecycle"])
    Cl1->>S2: Subscribe(topics=["cache.invalidate","server.lifecycle"])
    Cl2->>S1: Subscribe(topics=["cache.invalidate","server.lifecycle"])
    Cl2->>S2: Subscribe(topics=["cache.invalidate","server.lifecycle"])

    Cl1->>S1: Publish(topic="cache.invalidate", cluster_scope=true, max_relay_hops=1, FIRE_AND_FORGET)
    S1-->>Cl1: PublishAck(accepted=true)
    S1->>Cl1: Envelope(cache.invalidate, message_id=M1)  Note: local fan-out, ALL subscribed clients of S1
    S1->>Cl2: Envelope(cache.invalidate, message_id=M1)  Note: same message, both clients of S1 get it

    Note over Cl1,Cl2: Both Cl1 and Cl2 independently relay M1 to S2 — this is the amplification<br/>from §5.3.1: 2 clients x 1 other server = 2 relay Publish calls for 1 original message
    Cl1->>S2: Publish(Envelope(cache.invalidate, message_id=M1, max_relay_hops=0))
    S2-->>Cl1: PublishAck(accepted=true)
    S2->>Cl1: Envelope(cache.invalidate, message_id=M1)  Note: local fan-out on S2 — all of S2's subscribers
    S2->>Cl2: Envelope(cache.invalidate, message_id=M1)  Note: Cl2 is also subscribed directly to S2

    Cl2->>S2: Publish(Envelope(cache.invalidate, message_id=M1, max_relay_hops=0))
    S2-->>Cl2: PublishAck(accepted=false)  Note: de-dup — M1 already seen (§5.1 step 1), no second local fan-out on S2

    Note over S2: max_relay_hops is now 0 on every copy that reached S2 — S2's own subscribers<br/>(Cl1, Cl2) do NOT relay it a second hop onward. No cascading beyond this one hop, by design.

    Note over S1,S2: If Cl1 and Cl2 were both offline right now, S2 would never see M1 at all — silently.

    S1->>S1: Begin graceful shutdown
    S1->>Cl1: Envelope(server.lifecycle, "restarting", GUARANTEED, cluster_scope=false)
    S1->>Cl2: Envelope(server.lifecycle, "restarting", GUARANTEED, cluster_scope=false)
    Cl1-->>S1: Ack(message_id)
    Cl2-->>S1: Ack(message_id)
    Note over S1: server.lifecycle is never cluster_scope — always local to the restarting server's own clients, never relayed
```

**Implication (worth calling out explicitly):** with the mesh off, cluster
delivery is real but **probabilistic** — it depends on the connectivity
graph formed by whichever multinode clients happen to be connected at that
moment, and it costs `O(clients × servers)` redundant relay attempts per
broadcast (§5.3.1), not `O(servers)`. That is a fundamentally different
guarantee, and a fundamentally different cost profile, from a direct link,
and it is why RAFT should not rely on this mode alone (§5.3.5, §6.1) even
though cache invalidation is a good fit for it.

#### Mesh ON — sequence flow (opt-in)

With the mesh enabled and peers configured, each server also holds a
`PeerMessagingClient` per peer, opened at server startup regardless of
client activity. A `Publish` call now fans out both to the server's own
connected clients *and* directly to its peers — client relay is not needed
and, when a message is already delivered via the mesh, a relaying client
that also happens to see it is a no-op thanks to `message_id` de-dup (the
peer already marks itself as having produced/seen it).

```mermaid
sequenceDiagram
    participant Cl1 as Client 1 (connected to S1 and S2)
    participant Cl2 as Client 2 (connected to S1 and S2)
    participant S1 as OJP Server 1
    participant S2 as OJP Server 2

    Note over S1,S2: ojp.server.mesh.enabled=true, ojp.server.mesh.peers configured on both nodes
    Note over Cl1,Cl2: Standard topology — every client is multinode-connected to every server (unchanged from Mesh OFF)

    S1->>S2: Subscribe(topics=["raft.election","cache.invalidate"]) [at S1 startup, no client involved]
    S2->>S1: Subscribe(topics=["raft.election","cache.invalidate"]) [at S2 startup, no client involved]

    Cl1->>S1: Subscribe(topics=["cache.invalidate","server.lifecycle"])
    Cl1->>S2: Subscribe(topics=["cache.invalidate","server.lifecycle"])
    Cl2->>S1: Subscribe(topics=["cache.invalidate","server.lifecycle"])
    Cl2->>S2: Subscribe(topics=["cache.invalidate","server.lifecycle"])

    Cl1->>S1: Publish(topic="cache.invalidate", cluster_scope=true, FIRE_AND_FORGET)
    S1-->>Cl1: PublishAck(accepted=true)
    S1->>Cl1: Envelope(cache.invalidate)  Note: local fan-out — ALL of S1's subscribed clients
    S1->>Cl2: Envelope(cache.invalidate)
    S1->>S2: Envelope(cache.invalidate)  Note: single mesh fan-out call (producer_id=S1) — exactly one, regardless of client count
    S2->>Cl1: Envelope(cache.invalidate)   Note: S2 relays to its own clients (Cl1 also gets it here — de-duped client-side by message_id)
    S2->>Cl2: Envelope(cache.invalidate)

    Note over S1,S2: RAFT example — no client involved at all, works identically whether zero or many clients are connected.<br/>This is also a point-to-point example (§5.3.4): S2 targets S1 specifically via PublishRequest.target_id, not a broadcast.
    S2->>S1: Publish(Envelope(topic="raft.election", target_id="S1", FIRE_AND_FORGET))
    S1->>S1: Process vote request, update local RAFT state

    Note over S1: Server 1 begins graceful shutdown
    S1->>Cl1: Envelope(server.lifecycle, "restarting", GUARANTEED)
    S1->>Cl2: Envelope(server.lifecycle, "restarting", GUARANTEED)
    Cl1-->>S1: Ack(message_id)
    Cl2-->>S1: Ack(message_id)
```

**Loop-avoidance note:** because §5.3.2 already assumes a full mesh (every
server subscribes directly to every other configured peer), a receiving
server only needs to fan a peer-originated `Envelope` out to its *own*
connected clients, never re-publish it back onto the mesh — `producer_id`
lets a receiver recognize and drop an `Envelope` it produced itself (guards
against any accidental echo) but no further re-broadcast logic is needed
given the full-mesh topology. Note this diagram shows exactly **one** mesh
`Publish` call from S1 (to S2) regardless of how many clients are
connected — the mesh's cost is `O(servers)`, not `O(clients × servers)`
like client-relay, which is the other half of why it's the required choice
for anything frequent or broadcast-heavy (§5.3.5). If OJP ever moves to
gossip-based fan-out for larger clusters (§8.1), this would need actual
hop-count/seen-set loop-avoidance — flagged there, not solved here.

---

## 6. Mapping back to the 3 example use cases

| Use case | Topic | Mode | Recommended topology | Notes |
|---|---|---|---|---|
| RAFT consensus | `raft.<cluster-id>.election` (or similar) | Fire-and-forget | **Direct mesh required** (`ojp.server.mesh.enabled=true`) | RAFT already assumes an unreliable network and re-sends `RequestVote`/`AppendEntries` on timeout; making the transport "guaranteed" would add latency (ack round-trip) for no protocol benefit and could even mask real partitions from RAFT's own failure detector. Client-relay's probabilistic coverage is not the disqualifying reason on its own — RAFT is built to tolerate lost/delayed messages; see §5.3.5 for the concrete, honest argument (trust perimeter and amplification cost, not just "less reliable"). |
| Cache invalidation | `cache.invalidate` | Fire-and-forget | **Client-relay (default) is fine**; mesh optional for stronger guarantees | Invalidation is idempotent and self-healing (a missed invalidation just means a slightly stale cache entry until the next write/TTL), which is exactly what client-relay's best-effort coverage tolerates well. Could optionally periodically re-broadcast a checksum/version as a belt-and-suspenders anti-entropy mechanism — not required for this analysis. |
| Server restarting | `server.lifecycle` | Guaranteed (to currently-connected clients only) | Neither — always server-to-its-own-clients, never cluster-wide | This is the one case where "the client acts differently because it got the message" (e.g. stop sending new statements, prepare to fail over), so an ack-and-retry within the shutdown grace period is worth the extra complexity. Note "guaranteed" here can only mean "guaranteed to currently-attached subscribers within the grace period" — a client that is disconnected at the moment of publish cannot be reached by this mechanism, only by the normal failover behavior of the multinode driver, which already exists independently of this new protocol. This topic is never `cluster_scope=true` and is therefore unaffected by mesh on/off. |

---

## 6.1 Serverless / zero-client deployments, and the client-relay vs. mesh choice

This section now covers two related questions raised in review: how does
this design behave when the application client tier is idle or absent, and
— stated bluntly — does that actually matter?

**The mechanism, restated:** §5.3 now offers two ways for a message to
cross servers. **Client-relay** (default, §5.3.1) uses whichever multinode
clients happen to be connected as the carrier — free, but coverage is a
function of current client connectivity. **Direct mesh** (opt-in,
§5.3.2) uses a dedicated peer link per server, driven entirely by server
startup/configuration, independent of any client. Only the mesh is
unaffected by the client population going to zero.

**Recommendation for serverless/idle-client deployments:** enable the mesh.
Client-relay is the right default for typical deployments (it costs
nothing and needs no extra config), but the moment a deployment can have
long stretches with zero connected clients, client-relay's carrier
disappears along with them, and cross-server messaging stops silently
until a client reconnects. If that idle-period gap matters for the use
case (see below), `ojp.server.mesh.enabled=true` is the only one of the two
modes that keeps working through it, because it is driven by server
lifecycle, not client activity.

**Now, the honest answer to "is it actually a problem if OJP servers don't
communicate while no clients are connected?"** — asked directly in review,
and it deserves a direct, un-hedged answer rather than a reflexive "always
enable the mesh":

- **In the fully idle case, on its own, no.** If literally zero clients are
  connected, there is no query traffic, nothing reading the cache, and
  nothing depending on a leader decision *at that instant*. A cluster with
  no application activity has no user-visible correctness or availability
  at stake while it is idle. I would resist treating "servers can't reach
  each other right now" as inherently bad — it's only bad in relation to
  something that needs the result of that communication.
- **The real risk is at the boundary, not during the idle period itself:**
  the moment the *first* client reconnects after a long idle stretch. If
  that first request depends on cluster state that only converges through
  server-to-server messaging (e.g. "who is the current RAFT leader,"
  "is my local cache fresh"), and the messaging carrier (client-relay) only
  starts working once *that same client* becomes multinode-connected, the
  cluster has to bootstrap its coordination state concurrently with serving
  the very first request that depends on it. For self-healing, idempotent
  concerns (stale cache entry served once, RAFT election completing a beat
  late) this is a bounded cold-start cost, not an ongoing bug — likely
  acceptable. For anything where "answer before consensus is reached" is
  actively wrong rather than just outdated (e.g. two servers each
  believing themselves leader long enough to accept conflicting writes — a
  split-brain window), it is a real correctness problem, not just added
  latency, and is worth avoiding entirely rather than tolerating.
- **Applied to the 3 examples:** `server.lifecycle` can never be a problem
  when idle — there is nothing to notify with no clients present. Cache
  invalidation is essentially never a problem — worst case is one avoidable
  stale read at reconnect, self-corrected on the next write/TTL. RAFT is
  the only one of the three where the answer genuinely depends on *what*
  the consensus protects: coordination that's purely advisory/background
  can tolerate the same cold-start gap as cache invalidation; coordination
  that gates a decision that must not be made twice or made incorrectly
  (leader-exclusive writes, distributed locks) cannot, and that is
  precisely the case the direct mesh exists for.
- **My confidence in this framing:** high (80%) on the general shape of the
  argument (idle = no stakes, the risk is at the reconnect boundary, and it
  scales with how "must-not-be-wrong" the protected state is). Lower
  confidence (50%) on how large the cold-start window actually is in
  practice for a specific RAFT implementation, since that depends on
  election-timeout tuning this analysis doesn't specify — worth revisiting
  once RAFT is actually implemented rather than assuming it away here.

**Remaining consequences worth calling out** (mostly unchanged from the
prior revision of this section):

1. **The mesh, when enabled, must be independent of, and outlive, any
   client traffic.** Each server should establish and maintain its peer
   `Subscribe` streams (with reconnect/backoff) as part of its own startup
   and health-check loop, not lazily "when a client first connects." This
   should be stated as an explicit requirement, not an implementation
   detail.
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
   OJP server per Lambda invocation?** If yes, neither mode in this analysis
   covers that case and it would need rework (likely toward a
   stateless/external-coordination model, which starts to look like Option D
   again).
3. **Bootstrap ordering (mesh mode only)**: at cluster cold start (e.g. all
   OJP servers starting together), servers need to discover and connect to
   peers *before* any application client connects, otherwise RAFT can't
   elect a leader in time for the first request. This reinforces point 1:
   the peer mesh must be driven by its own dedicated server-side
   configuration (`ojp.server.mesh.peers`, not `serverEndpoints`, since the
   latter is only populated by connecting clients), not by client activity.
4. **This does not conflict with "client-relay is the default."** A
   deployment that needs RAFT/cache-invalidation to survive a fully idle
   client tier, or that considers even the reconnect-boundary risk above
   unacceptable, must explicitly set `ojp.server.mesh.enabled=true` and
   configure the peer list on every node. Client-relay remains the default
   for everyone else because it requires no extra configuration and no new
   inter-server trust relationship.

**Net effect on the recommendation:** ship both modes (§5.3.3). Client-relay
is the default and directly addresses the original ask — servers exchange
messages via connected clients when opening a direct server-to-server link
is hard or undesirable — while the direct mesh remains the opt-in answer
for RAFT and for any deployment where client presence cannot be assumed.

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
   §4 (Option C, "what embedding the driver means"), the server-side mesh
   does **not** add a compile-time dependency from
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
   themselves are ephemeral (spun up per request/invocation), neither mode
   in this design applies and this would need a fundamentally different
   (likely externally-coordinated) approach. Flagging this as a question for
   the team rather than assuming an answer.
9. **Client-relay coverage is probabilistic, not guaranteed, and that is
   invisible to operators unless documented loudly.** A missed relay hop
   produces no error anywhere — the publishing server gets a normal
   `PublishAck`, the message simply never reaches a server that had no
   multinode client bridging to it at that moment. This is an acceptable
   trade-off for cache invalidation but needs a prominent callout in any
   operator-facing docs (something like: "if you need a guarantee that
   cache invalidation reaches every node, enable the mesh"), otherwise an
   operator could reasonably assume "cluster-wide" means "every node,
   always," which client-relay does not promise.
10. **Client-relay puts new responsibility on the driver that is arguably
    outside its job description.** Today the JDBC driver's entire purpose is
    "be a JDBC driver for one application's queries." Client-relay asks it
    to additionally become a piece of cluster-internal transport
    infrastructure — forwarding messages that have nothing to do with the
    application that embeds it, consuming a small amount of the
    application's CPU/memory/network for another tenant's (the OJP cluster's)
    benefit. This is a real, if small, scope-creep concern, and part of why
    I would keep relay strictly limited to `cluster_scope=true`,
    hop-bounded, fire-and-forget topics (never RAFT, never anything latency-
    or trust-sensitive) — the blast radius of "an application's JDBC driver
    is briefly acting as a cluster relay" should stay small and clearly
    bounded. **Open question for the team: should relay be a per-client
    opt-out (e.g. a driver connection property such as
    `relay=false`) for applications that don't want their driver
    participating in cluster transport at all, even for cache invalidation?**
    My default assumption is yes, this should be opt-out-able per
    connection, medium confidence (65%), since some operators will
    reasonably object to any cluster-internal traffic riding through their
    application's process on principle, independent of the actual resource
    cost being small.
11. **RAFT must never be allowed onto client-relay, even accidentally.**
    Because both modes reuse the same `MessagingService` contract (§5.1),
    it would be easy for an implementation to let a `raft.*` topic's
    envelopes leak onto client-relay simply because a multinode client
    happens to be subscribed to it. Recommend the server-side
    implementation hard-codes a topic allowlist for what client-relay is
    permitted to forward (e.g. only `cache.*` by default), independent of
    whatever `max_relay_hops`/`cluster_scope` the publisher set, so a
    misconfigured or malicious client cannot smuggle a RAFT message across
    servers, and a well-meaning bug can't silently make RAFT "sort of work"
    over an unreliable, untrusted path that was never meant for it. See
    §5.3.5 for the full rationale this rule is based on.
12. **Client-relay's amplification cost is quantified in §5.3.1 as up to
    `C × (N-1)` redundant relay `Publish` attempts per broadcast message**
    (C = connected clients, N = servers) — worth restating here as its own
    concern because it is easy to under-appreciate until put in these terms:
    at a few hundred connected clients, an occasional `cache.invalidate`
    broadcast is a brief, tolerable spike, but the same mechanism used for
    anything published on a tight timer (which is exactly why RAFT is
    excluded, §5.3.5) would turn a handful of servers coordinating into a
    load proportional to the *application* tier's size. This should be a
    documented, explicit limit in any operator-facing guidance — e.g. "do
    not publish more than roughly once every few seconds on a
    `cluster_scope=true` topic under client-relay" — rather than something
    an operator discovers by exhausting connection/CPU budget in
    production. No load testing has been done for this analysis; the
    numbers above are arithmetic upper bounds, not measurements, and should
    be validated before this ships (see §9's phasing, which puts
    client-relay validation before the direct mesh for exactly this
    reason).

---

## 9. Suggested (high-level) implementation phasing

This is intentionally light — the ask was for analysis, not an implementation
plan — but a phased rollout is worth recording as a suggestion:

1. Add `MessagingService` to `ojp-grpc-commons`, implement server-side
   fan-out for `Subscribe`/`Publish` with `FIRE_AND_FORGET` only, no
   cross-server delivery of any kind yet — validate with the "server
   restarting" use case first since it's purely server-to-its-own-clients
   and carries no cross-server complexity at all.
2. Add **client-relay** (default mode, §5.3.1): driver-side seen-set +
   forwarding logic for `cluster_scope=true` envelopes, gated by a
   hard-coded topic allowlist (Concern 11) and, per Concern 10, an
   opt-out connection property. Exercise it with `cache.invalidate`, since
   client-relay's probabilistic coverage is an acceptable trade-off there.
3. Add the cluster-internal peer identity/credential mechanism (see
   Concern 3) before turning on any direct server-to-server traffic.
4. Add the **direct mesh** (opt-in, §5.3.2:
   `ojp.server.mesh.enabled=true`, servers using the shared client-plumbing
   to reach configured peers) and re-exercise `cache.invalidate` over it to
   confirm both modes agree on the wire format and dedup correctly when
   both happen to be active.
5. Add `GUARANTEED` delivery mode (ack + retry + de-dup) and switch
   "server restarting" to it.
6. RAFT is the most complex and highest-risk consumer (correctness-critical,
   latency-sensitive); build/adopt it last, on top of an already-proven
   messaging substrate, **requiring the direct mesh** (never client-relay,
   per Concern 11), likely evaluating an existing, well-tested Java RAFT
   library rather than writing RAFT from scratch, using this messaging
   layer purely as its transport.

---

## 10. Summary Recommendation

Introduce a new, generic `MessagingService` gRPC contract (topic + opaque
payload + delivery mode), implemented as an addition to `ojp-grpc-commons`,
consumed by application clients through `ojp-jdbc-driver`, with **two
complementary server-to-server topologies** rather than one:

- **Client-relay (default, no config, no new connections):** multinode
  clients — which already hold sessions to more than one OJP server for
  failover purposes — carry `cluster_scope=true` envelopes between the
  servers they're connected to, de-duplicated by `message_id` and bounded by
  `max_relay_hops`. This directly satisfies "use the clients as a means to
  communicate between OJP servers" for deployments where a direct
  server-to-server link is hard, disallowed, or simply not worth the extra
  configuration — at the cost of best-effort, probabilistic coverage that
  depends on current client connectivity.
- **Direct mesh (opt-in via `ojp.server.mesh.enabled`, default `false`):**
  each server reuses the driver's internal client-side gRPC plumbing as a
  plain library dependency — **not** its public JDBC API — to hold one
  channel per configured peer, driven entirely by server startup/config,
  independent of any client. This is required for RAFT and recommended
  whenever client presence cannot be assumed (serverless/idle-client
  deployments, §6.1).

Both modes are built by reusing the driver's client-side gRPC plumbing, not
a bespoke wire protocol (§4 Option C) — but only client-relay satisfies "no
new connection between OJP servers" unconditionally; the direct mesh is a
deliberate, opt-in exception to that, added specifically because some needs
(RAFT, serverless) cannot be met without it (see the Question section at
the top of this document for why the original, stricter framing of the
constraint was revised). Client-relay requires literally nothing new to be
opened; the mesh stays off by default so a default OJP deployment sees no
behavior change until an operator explicitly asks for it. Together they let
the three example use cases pick the topology that matches their actual
reliability need — cache invalidation on the free, best-effort default;
RAFT on the guaranteed, opt-in mesh — rather than forcing every use case
through a single, one-size-fits-all mechanism.

My biggest open concerns, in order: (1) §8.3, inter-server authentication
for the direct mesh, distinct from the JDBC-target-database credentials the
driver was originally built to carry — I'd want that settled before writing
any code that turns the mesh on by default in any environment; and (2)
§8.10/§8.11/§8.12, keeping client-relay's blast radius small (topic
allowlist, per-connection opt-out, and a documented rate ceiling given the
`C × (N-1)` amplification) and RAFT strictly off of it — these are cheap to
get right now, in the design, and expensive to retrofit once client-relay
code exists and topics start relying on it implicitly.
