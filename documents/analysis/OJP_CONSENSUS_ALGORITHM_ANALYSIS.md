# OJP Consensus Algorithm Analysis — Choosing a Leader-Election/Consensus Protocol for Mesh ON vs. Mesh OFF

## Question

[OJP_MESSAGING_PROTOCOL_ANALYSIS.md](./OJP_MESSAGING_PROTOCOL_ANALYSIS.md) uses
"RAFT" throughout as the running example for the leader-election use case, and
its §5.3.5 builds a "RAFT must use the direct mesh" argument specifically
around RAFT's documented crash-only (non-Byzantine) failure model. That
document was explicit that RAFT was only ever an *example*, not a decision —
this analysis is the follow-up asked for directly: **given the two transport
topologies already designed (client-relay / "Mesh OFF" vs. direct mesh /
"Mesh ON"), which consensus algorithm(s) actually make sense for each, and is
RAFT still the right pick, or should OJP consider a Byzantine-fault-tolerant
(BFT) alternative — HotStuff, PBFT, Tendermint, BFT-SMaRt — instead?**

This is a design-only analysis. It does not implement or select a final
algorithm; it exists so that choice can be made deliberately, with the
trade-offs on record, rather than by default because RAFT was the first
example used in an earlier document.

---

## 1. Recap: why the algorithm choice is coupled to the transport choice

The messaging protocol analysis's §5.3.5 argument, restated briefly because
this whole document builds on it: RAFT (Ongaro & Ousterhout, "In Search of an
Understandable Consensus Algorithm," 2014) is a **crash-fault-tolerant (CFT)**
protocol. It explicitly assumes nodes "fail by stopping" and does **not**
defend against a node that is up, reachable, and actively sending malformed,
forged, or contradictory messages (a **Byzantine** fault). Its safety proofs
assume every message a node acts on genuinely came from one of the fixed,
known cluster members.

The reason this matters for OJP specifically: **Mesh ON and Mesh OFF are not
just "reliable" vs. "unreliable" transports — they are transports with two
different trust perimeters**, and a consensus algorithm's failure-model
assumption has to match the trust perimeter of whatever transport actually
carries its messages, not just tolerate message loss:

- **Mesh ON (direct mesh, opt-in):** a fixed, operator-configured set of N
  OJP server processes, each holding a direct channel to every other
  configured peer (§5.3.2 of the messaging analysis). The trust perimeter is
  "the N configured servers" — the same set of processes a CFT algorithm like
  RAFT assumes.
- **Mesh OFF (client-relay, default):** the same N servers, but messages
  between them are carried by an unbounded, dynamically-changing population
  of application JDBC client processes that were never part of the cluster's
  membership and are reachable by anyone holding valid database credentials
  (§5.3.1/§5.3.5 of the messaging analysis). The trust perimeter here is
  fundamentally wider and, today, not authenticated at the message level —
  there is no signature or credential on an `Envelope` that distinguishes "a
  message a real peer server produced" from "a message any client crafted
  and published directly."

That second point is the crux of this whole analysis, so it is worth stating
as plainly as possible: **a Byzantine-fault-tolerant consensus algorithm is
not, by itself, a fix for the trust-perimeter problem client-relay creates.**
BFT algorithms tolerate up to `f` of their **N validators** behaving
maliciously — they do not turn an arbitrary, unvetted population of *outside*
processes into legitimate validators, and none of the algorithms below assume
their transport is trustworthy in the first place (see §5 below for the one
place this nuance cuts back in OJP's favor). Swapping RAFT for a BFT
algorithm does not, on its own, make it safe to route consensus traffic over
client-relay. This is explained in full in §5.

---

## 2. Candidate algorithms

### 2.1 RAFT (crash-fault-tolerant baseline; the algorithm assumed by the messaging analysis's examples)

- **Failure model:** Crash-fault-tolerant (CFT) only. Assumes non-Byzantine,
  fail-stop nodes.
- **Quorum:** Majority of `N` (`N = 2f+1` to tolerate `f` crashed nodes) —
  e.g. 3 nodes tolerate 1 crash, 5 nodes tolerate 2.
- **Communication complexity:** `O(N)` per round (leader broadcasts to
  followers, followers reply to leader) — cheap and simple.
- **Latency:** One leader→followers round trip per committed entry in the
  common case; election timeout typically 150–300ms (up to 600ms in some
  implementations).
- **Maturity/Java ecosystem:** Very mature as an algorithm (etcd, Consul,
  CockroachDB, Kafka's KRaft all use RAFT or a RAFT variant). For a Java-native
  implementation, **Apache Ratis** (Apache 2.0 license, actively maintained,
  used by Apache Ozone/Ratis-based projects) is the most credible off-the-shelf
  option; there are others but Ratis is the one with real production usage in
  the Java/Hadoop ecosystem.
- **Pros:** Simple to reason about, well-understood, smallest quorum size for
  a given fault tolerance, lowest communication/latency overhead of everything
  in this list, mature Java library available, easiest to operate and debug.
- **Cons:** Provides **no protection at all** if a message can be forged,
  replayed out of context, or injected by something outside the configured
  peer set — i.e., it is only as trustworthy as its transport's trust
  perimeter, which is exactly the mismatch problem with client-relay (§1).

### 2.2 PBFT (Practical Byzantine Fault Tolerance — Castro & Liskov, 1999)

- **Failure model:** Byzantine-fault-tolerant. Tolerates up to `f` malicious/
  arbitrary validators.
- **Quorum:** `N = 3f+1` — e.g. tolerating 1 Byzantine node needs 4 total
  validators, not 3.
- **Communication complexity:** `O(N²)` per decision (three all-to-all
  phases: pre-prepare, prepare, commit) — the original, foundational BFT
  protocol, but the one with the highest per-decision message cost in this
  list.
- **Latency:** ~3 network round trips per decision in the common case.
- **Maturity/Java ecosystem:** The original MIT implementation is C/C++;
  Java ports exist but are not actively maintained or production-hardened.
  PBFT today is mostly of historical/pedagogical importance — later
  algorithms (BFT-SMaRt, HotStuff) are direct, better-engineered descendants.
- **Pros:** The foundational, most thoroughly analyzed BFT protocol; if OJP
  wanted the most conservative, best-understood BFT choice academically, this
  is it.
- **Cons:** `O(N²)` messaging does not scale past a handful of nodes; no
  actively maintained, production-grade Java implementation; superseded in
  practice by BFT-SMaRt (same ideas, real Java engineering) and HotStuff
  (same ideas, linear messaging). Hard to justify choosing PBFT itself over
  either of those two for a new design today.

### 2.3 HotStuff (Yin et al., 2018 — basis for DiemBFT/Aptos/Cypherium consensus)

- **Failure model:** Byzantine-fault-tolerant.
- **Quorum:** `N = 3f+1`, same as PBFT.
- **Communication complexity:** **`O(N)`** per decision (and `O(N)` for a
  view change/leader replacement too) — HotStuff's headline contribution over
  PBFT is collapsing `O(N²)` all-to-all communication down to linear,
  leader-to-all/all-to-leader communication, typically using threshold
  signatures so the leader can aggregate votes into one compact certificate
  instead of every replica broadcasting to every other replica.
- **Latency:** Slightly more round trips per decision than PBFT in the basic
  (non-chained) form, but "responsive" — once the network is synchronous, it
  commits at actual network speed rather than waiting out a fixed timeout,
  and chained/pipelined variants (as used in production BFT blockchains)
  reduce the effective per-decision latency further.
- **Maturity/Java ecosystem:** Newer than PBFT/BFT-SMaRt; most production
  deployments and open-source implementations are in Rust or Go (it's the
  basis of several blockchain consensus layers), not Java. No mature,
  widely-used Java-native HotStuff library exists today, as far as this
  analysis could establish — this would likely mean building/adapting an
  implementation rather than adopting an off-the-shelf one.
- **Pros:** The best communication-complexity profile of any BFT algorithm in
  this list — the only one that scales sub-quadratically, which matters a lot
  if OJP's mesh could ever grow past a handful of nodes (already flagged as
  an open question in the messaging analysis, §8.1).
- **Cons:** Least mature option for a Java shop specifically — would likely
  require adapting a non-Java implementation or writing one from the
  specification, a materially bigger lift than adopting Ratis or BFT-SMaRt.

### 2.4 Tendermint (Buchman, 2016 — consensus layer of the Cosmos SDK)

- **Failure model:** Byzantine-fault-tolerant.
- **Quorum:** `N = 3f+1`.
- **Communication complexity:** Round-based propose/pre-vote/pre-commit,
  effectively `O(N²)` gossip in the general case (optimized in practice via
  gossip rather than naive all-to-all, but not linear like HotStuff).
- **Latency:** Typically 2–3 voting rounds per block/decision; designed for
  blockchain-style "finalize a block" semantics rather than a low-latency
  single-decision RPC, so its natural unit of work (a block of many
  transactions) doesn't map cleanly onto "elect a leader" or "invalidate one
  cache entry" without adapting the model.
- **Maturity/Java ecosystem:** Native implementation is Go (`tendermint/
  tendermint`, now `cometbft`); it is mature and heavily production-tested,
  but as a blockchain consensus engine, not a general embeddable Java
  consensus library. Java ports exist but are far less used/trusted than the
  Go original.
- **Pros:** Extremely battle-tested in a large, adversarial, public-blockchain
  setting (Cosmos ecosystem) — arguably the most "real-world adversarial
  conditions proven" option on this list.
- **Cons:** Designed around a blockchain/block-production model, not a
  general leader-election-and-broadcast primitive — would need real adaptation
  to fit OJP's much simpler use case; no practical Java-native path; almost
  certainly the least natural fit for embedding inside `ojp-server` of
  everything considered here.

### 2.5 BFT-SMaRt (Bessani, Sousa, Alchieri, 2014 — Java-native BFT state-machine replication library)

- **Failure model:** Byzantine-fault-tolerant.
- **Quorum:** `N = 3f+1`.
- **Communication complexity:** PBFT-derived (effectively `O(N²)` in the
  general case), but with real engineering optimizations — request batching,
  pipelining, and a tuned Java implementation — that give it materially
  better practical throughput than a naive PBFT implementation.
- **Latency:** Comparable to or better than PBFT in practice, largely due to
  batching amortizing the per-decision overhead across many requests.
- **Maturity/Java ecosystem:** **The most directly relevant option for an
  OJP-shaped, Java codebase.** It is a native Java library (Apache 2.0
  licensed), actively used in academic and some production/permissioned
  settings (it has, at various points, been evaluated/used as an alternative
  ordering-service backend in Hyperledger Fabric-adjacent work), and is
  specifically designed to be embedded as a state-machine-replication library
  rather than a full blockchain stack — the closest match to "drop this into
  `ojp-server` as a dependency" of any BFT option here.
- **Pros:** Only BFT option on this list that is both Java-native and
  reasonably production-tested; explicitly designed for embedding (not a
  blockchain node); Apache 2.0 license is compatible with OJP's licensing
  bar.
- **Cons:** Still pays the `3f+1` node cost and `O(N²)`-derived communication
  cost relative to RAFT; smaller community/ecosystem than RAFT's (Ratis,
  etcd, Consul, Kafka KRaft); adds real operational complexity (Byzantine
  agreement is harder to reason about/debug than crash-fault RAFT) for a
  benefit (defense against a malicious peer) that may not match OJP's actual
  threat model (see §4's opinion below).

---

## 3. Comparison table

| | RAFT | PBFT | HotStuff | Tendermint | BFT-SMaRt |
|---|---|---|---|---|---|
| Failure model | Crash-fault (CFT) | Byzantine (BFT) | Byzantine (BFT) | Byzantine (BFT) | Byzantine (BFT) |
| Quorum for `f` faults | `N = 2f+1` | `N = 3f+1` | `N = 3f+1` | `N = 3f+1` | `N = 3f+1` |
| Communication complexity | `O(N)` | `O(N²)` | `O(N)` | `O(N²)` (gossip-optimized) | `O(N²)` (batched/optimized) |
| Typical latency profile | 1 leader round trip | ~3 round trips | Responsive; more rounds than PBFT in basic form, mitigated by pipelining | 2–3 voting rounds, block-oriented | Comparable to/better than PBFT via batching |
| Java-native, production-usable library | **Apache Ratis** (Apache 2.0, mature) | No actively maintained Java implementation | No mature Java implementation found | Native is Go; Java ports immature | **BFT-SMaRt** (Apache 2.0, Java-native) |
| Natural fit for "embed as a library in ojp-server" | Yes | No | No (would require building) | No (blockchain-node shaped) | Yes |
| Tolerates a malicious (not just crashed) configured peer | No | Yes | Yes | Yes | Yes |
| Tolerates untrusted client-relay carrying its messages, unmodified | No | No* | No* | No* | No* |

\* See §5 — none of the BFT algorithms make client-relay safe *as designed
today*; they only make it *theoretically viable* **if** OJP separately adds
per-message peer signing, which does not exist yet.

---

## 4. Mesh ON: is RAFT still the right choice, or should OJP consider BFT here?

**My opinion, stated plainly: for Mesh ON as currently scoped, RAFT (via
Apache Ratis) remains the right default, and I would not reach for a BFT
algorithm unless a specific, named threat justifies it.** Reasoning:

- The direct mesh's peer set is exactly the set of OJP server processes an
  operator explicitly configured (`ojp.server.mesh.peers`), reached over a
  channel that (once §8.3's inter-server auth is built) is meant to be
  restricted to those peers specifically. That is precisely RAFT's assumed
  trust perimeter — "the N configured servers" — with no wider population in
  the picture at all. There is no architectural mismatch here the way there
  is with client-relay.
- A compromised or actively malicious *configured OJP server* is a much
  bigger problem than consensus safety: that same process already holds live
  database connections, credentials, and query results for every application
  using it. If an operator's threat model includes "one of my own OJP server
  processes might be compromised and start acting maliciously," BFT consensus
  is a narrow, partial mitigation — it protects leader-election integrity, but
  does nothing about that same compromised process reading/altering live
  query traffic, which is a strictly bigger exposure. I'd want to understand
  why an operator trusts RAFT's crash-only membership but not more before
  recommending they pay `3f+1` nodes and `O(N²)`-ish messaging for it.
- Apache Ratis is a mature, actively maintained, Java-native library with
  real production usage; none of the BFT candidates have an equally mature
  Java-native option except BFT-SMaRt, which is a real option but a smaller
  ecosystem and materially higher operational complexity.

**Where I would reconsider:** if OJP is ever deployed in a setting where the
configured peer servers themselves are **not** all operated by the same
trusted party — e.g. a scenario where different organizations each run one
node of a shared OJP mesh (a genuinely multi-tenant, mutually-untrusting
cluster) — then the "a compromised peer is no worse than a compromised OJP
server generally" argument above stops holding, because the peers are no
longer a single trust domain to begin with. In that specific scenario,
BFT-SMaRt (the Java-native, embeddable option) would be my recommendation
over HotStuff/Tendermint/PBFT, purely on the "actually shippable in a Java
codebase today" basis, accepting the `3f+1`/`O(N²)`-ish cost as the price of
that specific threat model.

**Question for the team:** is multi-organization / mutually-untrusting OJP
mesh deployment a real scenario being considered, or is "the operator
controls and trusts every configured peer" a safe assumption for the
foreseeable roadmap? My default assumption is the latter (single-operator
trust domain), medium-high confidence (70%), since nothing in the existing
messaging analysis or this codebase suggests a multi-tenant mesh is being
targeted — but I don't have a definitive answer and would rather ask than
assume.

---

## 5. Mesh OFF: does a BFT algorithm change the "never route consensus over client-relay" rule?

The messaging analysis's §5.3.5 rule is: RAFT (or any consensus traffic)
must not travel over client-relay, because client-relay's transport widens
the trust perimeter to an unauthenticated, arbitrary population of
application processes, and there is no message-level authentication today
that would let a receiver tell a genuine peer-produced message apart from
one an application client forged directly.

**This is where the nuance is worth spelling out carefully, because it's easy
to get wrong in either direction:**

- **Naively "wrong" direction:** assuming a BFT algorithm fixes this simply
  because "BFT tolerates malicious behavior." It does not, as designed today
  — BFT tolerates malicious *validators* (a fixed, known set), not an
  unbounded population of processes that were never validators to begin with.
  Routing HotStuff or BFT-SMaRt traffic over client-relay, with today's
  `MessagingService` contract, has the exact same forgeability problem RAFT
  has: nothing stops any authenticated JDBC client from publishing a
  hand-crafted `Envelope` with a spoofed `producer_id` claiming to be a
  validator vote.
- **Also worth stating honestly, the other direction:** BFT algorithms are
  *designed* for a network the protocol does not trust in the first place —
  partial synchrony and Byzantine-fault tolerance already assume the network
  can delay, drop, reorder, or be observed by adversaries. What BFT consensus
  actually requires from its transport is **message authenticity** (a
  receiver must be able to verify a vote/proposal genuinely came from the
  claimed validator and was not altered), not transport-level trust or a
  direct connection. **If** OJP were to add a per-`Envelope` cryptographic
  signature from the originating peer server's own key (a real inter-server
  PKI/identity scheme — which does not exist yet, per §8.3 of the messaging
  analysis), a BFT algorithm's messages could, in principle, ride over
  client-relay's untrusted carriers exactly the way BFT protocols already
  assume their network might be adversarial — the client is then just an
  unreliable, possibly-adversarial packet carrier, which BFT is already built
  to tolerate, rather than a member whose word must be taken on faith.
  **RAFT cannot make the same claim**, because RAFT's whole design assumes
  the messages it acts on are genuine by construction of a trusted channel,
  with no per-message authenticity mechanism of its own to fall back on.

**My honest opinion, given that nuance:** this is a real, technically
legitimate argument that a BFT algorithm is the *only* family of algorithms
that could ever make Mesh OFF viable for consensus traffic — but it is not
a reason to build that today. It requires: (1) a real per-peer signing
key/certificate scheme distinguishing "signed by a genuine configured peer"
from "published by any JDBC client" (a substantial new piece of
infrastructure, not a small addition); (2) accepting that liveness still
depends on client-relay's probabilistic, amplifying coverage (§5.3.1/§5.3.4
of the messaging analysis) — a signed vote that never gets relayed to enough
peers because too few clients happen to bridge them is still a liveness
failure, BFT's Byzantine tolerance does not fix a message that simply never
arrives; and (3) the `C×(N-1)` amplification cost the messaging analysis
already quantified applies unchanged (or worse, since BFT protocols with
`O(N²)`-class messaging multiply badly with `O(clients)` relay redundancy).
**Recommendation: keep the messaging analysis's existing rule as-is — no
consensus traffic, from any algorithm, over client-relay — and treat "signed
envelopes make BFT-over-relay theoretically viable" as a documented,
deliberately-not-pursued option, not a roadmap item**, unless a concrete need
for Mesh OFF-only deployments running consensus emerges (e.g. an operator
who genuinely cannot ever enable the mesh but still needs leader election —
which, per the messaging analysis's own recommendation, should push them
toward enabling the mesh rather than toward this considerably more complex
path).

**Question for the team:** is there a deployment scenario the team already
knows about where the direct mesh (Mesh ON) is expected to be permanently
infeasible (not just "off by default"), such that a client-relay-compatible
consensus scheme would actually be needed despite this analysis's
recommendation against it? If so, that changes the priority of building the
peer-signing scheme described above; if not, I'd treat it as a documented
possibility and nothing more for now.

---

## 6. Overall recommendation

1. **Do not assume RAFT is final.** Treat it as the reference CFT choice for
   Mesh ON, on record here as a deliberate choice with reasoning, not an
   accident of which algorithm the earlier messaging analysis happened to use
   as its running example.
2. **Mesh ON: recommend RAFT via Apache Ratis** as the default, pending the
   team confirming the "single trusted operator per mesh" assumption in §4.
   Revisit only if a genuinely multi-tenant/mutually-untrusting mesh scenario
   is a real target — in which case **BFT-SMaRt** (not PBFT/HotStuff/
   Tendermint) is the recommended BFT fallback, purely on Java-maturity
   grounds.
3. **Mesh OFF: no consensus algorithm should run over client-relay today**,
   BFT or otherwise — the blocker is the missing inter-server message
   authentication scheme (§8.3 of the messaging analysis), not the choice of
   consensus algorithm. Document the "signed envelopes could make BFT-over-
   relay viable" path as a considered-and-deferred option (§5), not a
   commitment.
4. Update `OJP_MESSAGING_PROTOCOL_ANALYSIS.md` (done, see cross-references
   added throughout) to stop treating RAFT as an assumed, final choice and
   point to this document wherever the algorithm choice matters.

**My biggest concerns, in order:** (1) none of this has been validated with
any actual load/latency testing — the comm-complexity numbers above are
textbook, not measured against OJP's real deployment shapes; (2) the
inter-server authentication scheme this whole analysis leans on twice (§4 and
§5) does not exist yet and is still an open question in the messaging
analysis, not a solved problem being assumed away; (3) I have low-to-medium
confidence (55%) in the HotStuff/Tendermint Java-ecosystem-maturity
assessment specifically, since that space moves quickly and a
newer/better-maintained Java port could exist that a broader search would
surface — worth a second look immediately before any implementation
decision, rather than trusting this document as the final word on library
availability.
