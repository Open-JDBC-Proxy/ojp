# OJP Generic Messaging Protocol — Executive Summary

## Question

How should OJP servers exchange messages with each other (e.g. RAFT
leader-election, cache-invalidation broadcasts) and with connected JDBC
clients (e.g. "this server is restarting"), given a hard constraint that OJP
servers must **not** connect directly to each other — all communication must
reuse the existing `ojp-jdbc-driver`?

## Quick Answer

Add a new, generic `MessagingService` gRPC contract (topic + opaque payload +
delivery mode: fire-and-forget or guaranteed) to `ojp-grpc-commons`.
Application clients reach it through `ojp-jdbc-driver`, the same way they use
any other RPC today. **Two complementary topologies get a message from one
OJP server to another:**

- **Client-relay (default, always on, no config):** a client connected to
  more than one OJP server (a *multinode* client, using the existing
  `jdbc:ojp[host1:port1,host2:port2]_url` format) already holds a
  `Subscribe` stream on each — it forwards cluster-wide envelopes it
  receives from one server onto the others it's connected to. This directly
  is "use the clients as a means to communicate between OJP servers,"
  requires zero new connections, and is the right default whenever opening a
  direct server-to-server link is hard, disallowed, or just not worth the
  config. Its trade-off: coverage depends entirely on which multinode
  clients happen to be connected right now — with zero clients connected,
  zero cross-server delivery happens, silently.
- **Direct mesh (opt-in, `ojp.server.mesh.enabled=false` by default):** each
  server reuses the driver's *internal* client-side gRPC plumbing (channel
  management, retries, circuit breaker — already shared via
  `ojp-grpc-commons`) as a plain library dependency — **not** the public
  JDBC `Connection` API, **not** a new kind of socket — to hold one channel
  per configured peer, driven by server startup/config rather than client
  activity. This is required for RAFT and recommended whenever client
  presence can't be assumed (e.g. serverless deployments).

## Key Design Points

- **Envelope:** `message_id`, `topic`, opaque `payload` bytes, `producer_id`,
  timestamp, `delivery_mode`, optional `ttl_seconds`, plus `cluster_scope`
  (is this meant for the whole cluster, or just this server's own clients?)
  and `max_relay_hops` (bounds client-relay fan-out). Generic on purpose —
  no hardcoded knowledge of RAFT/cache/lifecycle in the protocol itself.
- **Two delivery modes:**
  - **Fire-and-forget** (at-most-once, no ack, no retry) — recommended for
    RAFT consensus messages and cache invalidation, both of which already
    tolerate loss/duplication at the application level.
  - **Guaranteed delivery** (at-least-once, ack + retry + TTL + dedup via a
    Caffeine-backed seen-set) — recommended for the "server restarting"
    notice, since a client should durably receive that particular signal.
- **Ordering:** only per-(producer, topic) FIFO is promised, not a global
  total order — sufficient for all three example use cases, and honest
  about the limits of the design.
- **Push to clients:** implemented as a server-streaming `Subscribe` RPC that
  the driver opens (client-initiated, same shape as today's `executeQuery`
  streaming), so the server never has to dial the client — it just writes to
  a stream the client already opened. Surfaced to applications via
  `SQLWarning` (an existing pattern in this codebase) and/or an optional
  listener callback.
- **Direct mesh (opt-in, off by default):** once
  `ojp.server.mesh.enabled=true`, each server opens exactly one channel +
  one `Subscribe` stream per peer listed in a dedicated
  `ojp.server.mesh.peers` setting (deliberately *not* the client-populated
  `serverEndpoints` list, since that only exists while a client is
  connected). This forms a full mesh, which is acceptable at expected OJP
  cluster sizes; would need a gossip/tree topology if OJP ever targets
  hundreds of nodes (flagged as an open question, not solved here).

## Mesh OFF (client-relay) vs. Mesh ON (direct) at a Glance

```mermaid
graph LR
    subgraph "Mesh OFF (default) — relay via multinode clients"
        CM[Multinode App Client] -->|Subscribe / Publish| S1[OJP Server 1]
        CM -->|Subscribe / Publish, also relays| S2[OJP Server 2]
        S1 -.->|no direct connection| S2
    end
```

```mermaid
graph LR
    subgraph "Mesh ON (opt-in: ojp.server.mesh.enabled=true)"
        C3[App Client] -->|Subscribe / Publish| S3[OJP Server 1]
        C4[App Client] -->|Subscribe / Publish| S4[OJP Server 2]
        S3 <-->|MessagingService: Publish/Subscribe over configured peers| S4
    end
```

Full sequence diagrams for both modes, including the cache-invalidation and
RAFT-vote message flows, are in §5.5 of the full analysis.

## Client-relay vs. Direct Mesh — Pros and Cons

| | Client-relay (default) | Direct mesh (opt-in) |
|---|---|---|
| New connections needed | None | One channel per configured peer |
| Delivery guarantee | Best-effort, probabilistic (depends on current client connectivity) | Deterministic, independent of clients |
| Works with zero clients connected | No | Yes |
| Good fit | Cache invalidation and other idempotent, best-effort cluster-wide topics | RAFT; anything needing bounded-latency, guaranteed-to-attempt delivery |
| New trust surface | None (a relay hop is a normal authenticated client `Publish`) | Yes — needs its own inter-server credential story |
| Recommendation | **Default**, and the right choice when a direct server-to-server link is hard, disallowed, or not worth configuring | **Required for RAFT**; recommended whenever client presence can't be assumed (e.g. serverless) |

See §5.3.3 of the full analysis for the complete comparison and reasoning.

## Options Considered (see full analysis for pros/cons)

| Option | Verdict |
|---|---|
| A. Piggyback messages on `StatementService` as fake SQL calls | Rejected as primary mechanism (abuses SQL semantics); acceptable only as an emergency single-message fallback |
| B. Keep growing ad-hoc `ConnectionDetails` fields (like `clusterHealth`) | Rejected as a general mechanism (not pub/sub, no push, no ordering); kept for its original narrow purpose |
| C. New dedicated `MessagingService`, transported via the JDBC driver, in either topology above | **Recommended** |
| D. External broker (Kafka/RabbitMQ/NATS) | Rejected — contradicts the "no new infra / reuse driver" constraint |
| E. Direct server-to-server socket/gRPC link (classic RAFT transport) | Explicitly disallowed by the problem statement |

## Biggest Open Concerns

1. Inter-server messaging (direct mesh) needs its own authentication story —
   the driver was built to carry *database* credentials, not cluster-internal
   trust. This should be settled (shared cluster secret? mTLS peer identity?)
   before any implementation starts; the codebase does not appear to have
   this today.
2. Client-relay puts new, non-JDBC responsibility on the driver (forwarding
   cluster-internal traffic on behalf of the server cluster, not the
   embedding application) — keep its scope strictly bounded (topic
   allowlist, hop limit, RAFT never eligible) and consider a per-connection
   opt-out.

## Other Notable Risks / Questions Raised

- Expected cluster size (affects whether a full server mesh is acceptable or
  a gossip topology is needed).
- "Guaranteed delivery" as designed here only survives while the publishing
  process is alive — it is not a durable, crash-surviving outbox. If a future
  use case needs that, it's a signal to revisit the "no broker" constraint
  rather than bolt a WAL onto this design.
- Backpressure/queue-bounding for slow subscribers needs explicit acceptance
  criteria before implementation, not just "add a queue."
- Client-relay coverage is invisible when it fails — a missed hop produces no
  error anywhere. This needs a loud operator-facing callout so nobody assumes
  "cluster-wide" means "guaranteed to reach every node" under the default
  mode.

## Suggested Phasing

1. `MessagingService` + fire-and-forget only, validated with the restart
   notice first (pure server-to-its-own-clients, no cross-server complexity).
2. Client-relay (default mode), validated with cache invalidation.
3. Inter-server credential/trust mechanism.
4. Direct mesh (opt-in), re-validated with cache invalidation to confirm both
   modes agree on wire format and dedup.
5. Add guaranteed delivery; switch the restart notice to it.
6. RAFT last, on top of the proven substrate, **requiring the direct mesh**
   (never client-relay) — likely adopting an existing Java RAFT library
   rather than writing RAFT from scratch.

## Serverless / Zero-Client Deployments, and "Is It Actually a Problem?"

A reviewer asked how this works when application clients scale to zero for
long stretches, and — more pointedly — whether it's actually a problem if
OJP servers simply don't communicate during that idle window.

**Short answer:** while genuinely idle (zero clients, zero query traffic),
no — there's nothing user-visible at stake. The real risk is at the
*boundary*: the moment the first client reconnects, if it needs a decision
that depends on cluster state that only converges through server-to-server
messaging. For idempotent, self-healing concerns (a stale cache entry, a
RAFT election finishing a beat late) that's a bounded, likely-acceptable
cold-start cost. For coordination where "wrong answer before consensus"
is an actual correctness bug rather than a stale-but-harmless one (e.g. two
servers both briefly believing themselves leader), it's a real problem worth
avoiding outright — and that's exactly the case the direct mesh exists for,
since it's the only one of the two modes unaffected by the client population
going to zero. See §6.1 of the full analysis for the complete reasoning and
confidence levels.

The mesh remains opt-in even for serverless deployments — enabling it is
the explicit choice an operator makes to get this guarantee, not a default.
One open question remains: is there any deployment model where the **OJP
servers themselves** (not just client apps) scale to zero between requests?
If so, neither mode here covers that case and it would need a different,
likely externally-coordinated approach. See §6.1/§8.8 of the full analysis.

## Full Analysis

See [OJP_MESSAGING_PROTOCOL_ANALYSIS.md](./OJP_MESSAGING_PROTOCOL_ANALYSIS.md)
for the detailed proto sketch, delivery-mode comparison table, per-use-case
mapping, and the full list of concerns and open questions.
