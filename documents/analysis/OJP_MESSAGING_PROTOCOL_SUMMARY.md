# OJP Generic Messaging Protocol — Executive Summary

Full analysis: [OJP_MESSAGING_PROTOCOL_ANALYSIS.md](./OJP_MESSAGING_PROTOCOL_ANALYSIS.md)
Related analysis: [OJP_CONSENSUS_ALGORITHM_ANALYSIS.md](./OJP_CONSENSUS_ALGORITHM_ANALYSIS.md)

## What's proposed

A generic `MessagingService` gRPC contract — `Publish`, `Subscribe`, `Ack`;
topic + opaque payload + delivery mode — added to `ojp-grpc-commons` and
consumed through the existing `ojp-jdbc-driver` connection/session/failover
machinery. No new wire protocol, no mandatory external broker.

```proto
service MessagingService {
  rpc Publish (PublishRequest) returns (PublishAck);
  rpc Subscribe (SubscribeRequest) returns (stream Envelope);
  rpc Ack (AckRequest) returns (AckResponse);
}

message Envelope {
  string topic = 2;
  bytes payload = 3;
  DeliveryMode delivery_mode = 6;
  bool cluster_scope = 8;
}
```

Two delivery modes: **fire-and-forget** (at-most-once, no ack — consensus,
cache invalidation) and **guaranteed** (at-least-once, ack + retry + dedup
— server-restart notices).

## Two server-to-server topologies

| | Client-relay | Direct mesh |
|---|---|---|
| Default | **Yes, always on** | No — opt-in (`ojp.server.mesh.enabled`) |
| New connections between servers | **None** | One channel per configured peer |
| Works with zero clients connected | No | Yes |
| Good for | Cache invalidation | Consensus (required); any deployment with long zero-client periods (serverless) |

Client-relay works because multinode clients are already connected to every
server in the cluster and forward `cluster_scope=true` messages between the
servers they hold sessions to — no new connection, no new config. The
direct mesh is a small, explicit exception: servers open a channel directly
to configured peers, reusing the driver's client-side gRPC plumbing as a
library (not its public JDBC API), only when an operator turns it on.

## Consensus: direct mesh vs. encrypted client-relay

Direct mesh is the recommended default for consensus: cost is `O(servers)`
regardless of client count, and it works with zero clients connected
(serverless).

Encrypted client-relay is a supported alternative for deployments that want
zero new connections between OJP servers. Full fan-out means an attacker
needs to defeat *every* client bridging two servers to suppress a message,
not just one — a real, meaningful bar. What's left is a single point
shared by all clients (the driver build they all run, or a network path
they all cross), which more clients doesn't fix. This option also costs
`O(clients × servers)` per heartbeat and needs a widened election timeout
to tolerate relay-path latency, trading failover speed for avoiding the
mesh. Full reasoning and configuration:
[OJP_CONSENSUS_ALGORITHM_ANALYSIS.md §5](./OJP_CONSENSUS_ALGORITHM_ANALYSIS.md#5-can-client-relay-carry-consensus-messages-reliably).

## Which consensus algorithm

RAFT (via Apache Ratis) — its crash-only trust assumption matches the
direct mesh's fixed, operator-configured peer set. BFT alternatives (PBFT,
HotStuff, Tendermint, BFT-SMaRt) cost more nodes and more messages to
tolerate a threat model (malicious peers) that doesn't fit a
single-operator mesh. Full comparison:
[OJP_CONSENSUS_ALGORITHM_ANALYSIS.md](./OJP_CONSENSUS_ALGORITHM_ANALYSIS.md).

## Biggest open items

1. Inter-server authentication for the direct mesh doesn't exist yet and
   needs designing before the mesh carries anything real.
2. Keeping plain client-relay's blast radius small by default: a topic
   allowlist so consensus traffic is opt-in rather than automatic, a
   per-connection opt-out, and clear operator docs that it's best-effort,
   not guaranteed cluster-wide delivery.
3. Guaranteed-delivery retries don't survive a publisher crash — fine for
   a restart notice, not a durable outbox; a real broker would be needed if
   that's ever required.
