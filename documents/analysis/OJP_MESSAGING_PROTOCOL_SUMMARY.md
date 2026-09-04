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

## Why consensus needs the direct mesh

Not because client-relay might lose or delay a message — consensus
protocols already tolerate that. Two concrete reasons instead:

1. **Trust perimeter.** Consensus assumes messages come from a fixed,
   known set of servers. Client-relay routes them through application
   processes outside that set. Encryption (AEAD) stops a client from
   forging or altering a message, but not from simply choosing not to
   relay it, or relaying it late — a courier can be sealed but still
   decide not to deliver.
2. **Cost.** Relaying via every connected client costs up to
   `clients × servers` calls per broadcast. Fine for an occasional cache
   invalidation; too expensive at a consensus heartbeat's frequency
   (every 50–150ms).

Redundancy (sending the same message via many independently-connected
clients) genuinely helps against random relay failures — at a 5% failure
chance per client, 10 clients bring the odds of total failure to
practically zero. It does not help against a targeted actor, since
targeting breaks the independence that math depends on, and it doesn't
reduce the cost problem — more redundancy is more of the same traffic.
Full reasoning: [OJP_CONSENSUS_ALGORITHM_ANALYSIS.md §5](./OJP_CONSENSUS_ALGORITHM_ANALYSIS.md#5-can-client-relay-carry-consensus-messages-reliably).

## Which consensus algorithm

RAFT (via Apache Ratis) for the direct mesh — its crash-only trust
assumption matches the mesh's fixed, operator-configured peer set. BFT
alternatives (PBFT, HotStuff, Tendermint, BFT-SMaRt) cost more nodes and
more messages to tolerate a threat model (malicious peers) that doesn't fit
a single-operator mesh. Full comparison and Mesh-OFF reasoning:
[OJP_CONSENSUS_ALGORITHM_ANALYSIS.md](./OJP_CONSENSUS_ALGORITHM_ANALYSIS.md).

## Biggest open items

1. Inter-server authentication for the direct mesh doesn't exist yet and
   needs designing before the mesh carries anything real.
2. Keeping client-relay's blast radius small: a topic allowlist so
   consensus can never leak onto it, a per-connection opt-out, and clear
   operator docs that it's best-effort, not guaranteed cluster-wide
   delivery.
3. Guaranteed-delivery retries don't survive a publisher crash — fine for
   a restart notice, not a durable outbox; a real broker would be needed if
   that's ever required.
