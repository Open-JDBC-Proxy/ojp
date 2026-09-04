# Which Consensus Algorithm Fits OJP's Messaging Design?

## Question

[OJP_MESSAGING_PROTOCOL_ANALYSIS.md](./OJP_MESSAGING_PROTOCOL_ANALYSIS.md)
uses "RAFT" as a placeholder name for "leader-election/consensus algorithm."
This document treats the choice as open and compares RAFT against
Byzantine-fault-tolerant (BFT) alternatives — PBFT, HotStuff, Tendermint,
BFT-SMaRt — for OJP's two transports: the opt-in **direct mesh** and the
default **client-relay**.

The short answer: **RAFT (via Apache Ratis) for the direct mesh.
Client-relay should not carry consensus traffic, mesh or no mesh algorithm
choice.** §5 explains exactly why, with concrete examples — this is the
section most relevant to "can we make client-relay work well enough to
avoid needing the mesh at all?"

---

## 1. Why transport affects the algorithm choice

Consensus algorithms differ in what they assume about their transport:

- **Crash-fault-tolerant (CFT)** algorithms, like RAFT, assume every message
  came from a legitimate, fixed cluster member — a peer can crash or go
  silent, but a message that arrives is trusted at face value.
- **Byzantine-fault-tolerant (BFT)** algorithms assume some participants may
  actively lie, forge, or send conflicting messages, and tolerate that up
  to a bound (`f` faulty nodes out of `N`).

The direct mesh's participants are exactly the fixed, operator-configured
set of OJP servers — the situation CFT algorithms are built for.
Client-relay routes messages through application processes, which are a
different, larger, less centrally-operated population — this is the
situation that makes BFT interesting to consider, and is the focus of §5.

---

## 2. Candidate algorithms

| Algorithm | Fault model | Quorum | Message complexity | Typical latency | Java maturity |
|---|---|---|---|---|---|
| **RAFT** | Crash-only (CFT) | `N = 2f+1` | `O(N)` | Low (single round-trip per decision) | **High** — Apache Ratis is production-grade, used by Ozone/Ratis-based systems |
| **PBFT** | Byzantine | `N = 3f+1` | `O(N²)` | Medium (3 message phases) | Low — no mainstream, maintained Java implementation |
| **HotStuff** | Byzantine | `N = 3f+1` | `O(N)` (its key improvement over PBFT) | Medium | Low — reference implementations are Go/C++; used in Diem/Aptos, not Java |
| **Tendermint** | Byzantine | `N = 3f+1` | `O(N²)` | Medium-high (block-based, not single-decision) | Low in Java — the ecosystem is Go (Cosmos SDK); built for blockchain-style block finality, a heavier fit than "elect one leader" |
| **BFT-SMaRt** | Byzantine | `N = 3f+1` | `O(N²)`, with batching optimizations | Medium | **Medium** — the only Java-native, embeddable BFT library among these |

**Reading the table:** every BFT option needs 50% more nodes for the same
fault tolerance (`3f+1` vs `2f+1`) and at least quadratic messaging unless
using HotStuff specifically. That's the price of tolerating lying nodes,
not just crashed ones — worth paying only where the trust assumption RAFT
makes is actually false.

---

## 3. Mesh ON: recommendation

**RAFT, via Apache Ratis.** The mesh's participants are exactly the fixed,
operator-configured peer set RAFT assumes. Switching to a BFT algorithm
here buys protection against a scenario — a configured OJP server acting
maliciously — that, if it happens, is already a bigger operational problem
than consensus safety (that server also serves real client queries, sees
real data, and holds real credentials). It also costs more nodes, more
messages, and a Java ecosystem with materially less production track record
than Ratis.

**When this would change:** if OJP is ever deployed as a shared, mutually
untrusting multi-tenant mesh — operators who don't otherwise trust each
other's servers — BFT-SMaRt is the one Java-native, embeddable option worth
prototyping. Not needed for a single-operator cluster, which is what this
document assumes today. **Open question: is a multi-tenant mesh a real
target, or is every deployment single-operator?**

---

## 4. Mesh OFF: recommendation

**Do not run consensus over client-relay in any form, with any algorithm.**
Use the direct mesh for consensus even in an otherwise mesh-off deployment,
and reserve client-relay for cache invalidation and similarly idempotent,
low-frequency, best-effort topics. §5 is the detailed reasoning.

---

## 5. Can client-relay carry consensus messages reliably?

This is the practical question: OJP clients are trusted applications, not
random internet hosts, and every client is already connected to every
server. Doesn't that make client-relay good enough for consensus, without
needing the mesh at all?

### 5.1 What encryption actually fixes

Yes — if every consensus envelope is protected with **authenticated
encryption** (AEAD, e.g. AES-GCM, keyed per-server so only OJP servers hold
the key), a relaying client genuinely cannot forge a vote, alter a message,
or impersonate a server. That is a real, complete fix for **tampering and
forgery**. Plain confidentiality-only encryption (e.g. unauthenticated
AES-CBC) does *not* give this — it hides the bytes but doesn't stop someone
from flipping or replacing them; AEAD (or plaintext + MAC) is required.

This is worth stating plainly: **given trusted client applications and
proper AEAD, "the client rewrites the message" is a solved problem.**

### 5.2 What encryption does not fix

Encryption protects the message. It does nothing about whether the courier
**delivers** it at all.

**Simple example:** imagine a sealed, tamper-proof envelope handed to a
courier. Sealing it stops the courier from reading or altering the letter
inside. It does not stop the courier from putting it in a drawer and
forgetting about it, walking a different route that takes longer, or
simply choosing not to deliver it today. Client-relay has exactly this
gap: a relaying client can silently not relay, relay late, or relay only
some of the messages it sees — and the publisher has no way to detect that
from the outside, because a `Publish` call succeeds (returns an ack) the
moment the local server hands the message to its own subscribers, before
any relay hop happens.

For RAFT specifically, **selectively not relaying certain messages** — say,
only the vote requests from one particular candidate — can bias an
election even though nothing was forged. That is Byzantine-style behavior
(a participant deviating from "faithfully pass along what you receive"),
and RAFT's crash-only design has no mechanism to detect or tolerate it.
Encryption does not touch this at all, because nothing about the message
was altered — it just never arrived.

### 5.3 Does sending via multiple clients (redundancy) fix it?

This is a genuinely good point, and worth being precise about, because the
design already does this by construction: local fan-out (§5.1 of the
messaging analysis) sends every envelope to *every* subscribed client, and
each of them independently attempts to relay it onward. Sending "the same
message via more than one client" isn't a hypothetical addition — it's what
already happens today whenever more than one client is connected.

**What this fixes well: random, independent failures.** If each relaying
client has a small, independent chance `p` of failing to relay (crashed,
network blip, GC pause) and the message goes out via `k` different clients,
the chance that *all of them* fail is `p^k`. A concrete number: at `p =
0.05` (5% chance any one client fails to relay) and `k = 10` connected
clients, the chance that *every single one* fails is `0.05^10` —
practically zero. This is exactly why client-relay works reasonably well
for cache invalidation in practice — it is not simply "unreliable."

**What this does not fix: a targeted or coordinated actor.** The `p^k` math
only holds if each client's chance of failing to relay is *independent*.
That assumption breaks in the two cases that actually matter for
consensus:
- **One entity controls (or compromises) several of the specific clients**
  that happen to bridge two particular servers. Those failures are no
  longer independent coin flips — they're one decision, applied
  everywhere at once. `p^k` doesn't apply; the real probability of failure
  is just however good that one actor's evasion is.
- **A bug, not malice, shared across a fleet.** If every instance of an
  application runs the same driver version with the same relay bug, `k`
  independent-looking clients are really one failure mode duplicated `k`
  times, not `k` independent trials.

Redundancy is a real, effective defense against *random* relay failure. It
is not a defense against a *targeted* one, because targeting breaks the
independence the math depends on.

Redundancy also doesn't touch the other two blockers, which are
unaffected by trust:
- **Latency.** Client processes run app code, have their own GC pauses,
  and sit on whatever network path the application happens to have — none
  of that is tuned for RAFT's timing budget (heartbeats every 50–150ms,
  election timeout a small multiple of that). Sending via more clients
  doesn't make any individual path faster or more predictable; it can only
  help if at least one of the `k` paths happens to be fast enough, which
  is a bet, not a guarantee.
- **Cost.** Sending via `k` clients to get better odds is `k` times the
  relay traffic (§5.3.1 of the messaging analysis already quantifies this
  as up to `clients × servers` calls per broadcast). More redundancy for
  better odds directly means more of exactly the cost problem that already
  makes client-relay a poor fit for heartbeat-frequency traffic.

### 5.4 Bottom line

- **Forgery/tampering: solved** by per-peer AEAD encryption, given clients
  are trusted applications. Not a reason to avoid client-relay.
- **Random relay failures: well mitigated** by the fan-out redundancy the
  design already has. Genuinely a point in client-relay's favor for
  lower-stakes traffic like cache invalidation.
- **Targeted omission and unpredictable latency: not solved** by either
  encryption or redundancy, and are the real, concrete reasons consensus
  needs the direct mesh instead. Not "less secure" in the abstract — the
  specific, checkable failure mode is that a deviating (or just slow)
  client can bias an election result without forging anything, and RAFT's
  design has no way to detect or route around that.
- **If client-relay must be used for something today** (e.g. an operator
  who genuinely cannot enable the mesh yet), the best available design is:
  per-peer AEAD keys + a monotonic sequence number per producer (replay
  protection) + relying on the existing multi-client fan-out for
  redundancy. That combination is good enough for cache invalidation and
  similar idempotent, best-effort topics today. It is still not
  recommended for consensus, because of the latency and cost points above,
  which that combination does not address.

---

## 6. Overall recommendation

| | Recommendation |
|---|---|
| Direct mesh | RAFT via Apache Ratis |
| Client-relay | No consensus traffic. Cache invalidation and similar idempotent topics only, optionally with per-peer AEAD + sequence numbers if message integrity needs to be provable. |
| Switch to BFT | Only if a multi-tenant, mutually-untrusting mesh becomes a real deployment target (open question, §3) |

## 7. Open questions

1. Is a multi-tenant, mutually-untrusting mesh a real target for OJP, or is
   every deployment single-operator? (Drives §3.)
2. If per-peer AEAD is built for client-relay's cache-invalidation path
   (§5.4), where do server keys live and how do they rotate? This needs its
   own design, not an add-on to this analysis.
3. Should the driver expose a way for an application to observe "my relay
   attempt failed" instead of it being silent? Would help operators notice
   the "targeted omission" failure mode in §5.2 sooner, even without fully
   solving it.
