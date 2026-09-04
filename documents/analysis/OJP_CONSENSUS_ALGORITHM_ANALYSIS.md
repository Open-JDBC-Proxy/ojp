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
  membership (§5.3.1/§5.3.5 of the messaging analysis). The trust perimeter
  here is fundamentally wider and, today, not authenticated at the message
  level — there is no signature or credential on an `Envelope` that
  distinguishes "a message a real peer server produced" from "a message any
  client crafted and published directly." **Important nuance, addressed in
  full in §6 below: "wider population" is not the same claim as "untrusted
  population" — application clients are the operator's own software, not
  strangers, and that distinction matters, but not in the way it might first
  seem.**

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

### 5.6 Would encrypting the envelope (servers-only key) work instead of signing?

This is a legitimate alternative to the asymmetric-signing idea above, and
worth answering precisely rather than lumping it in with "signing" — it's
realistic, but only if one specific, easy-to-get-wrong distinction is
respected: **the property that actually matters here is integrity/
authenticity, not secrecy.**

**Why "encryption" needs to be unpacked before answering "would it work":**
plain encryption (e.g. AES-CBC, or any cipher used only for confidentiality)
answers a different question than the one that matters. It stops a client
from *reading* an envelope it can't decrypt, but naive/unauthenticated
encryption does not, by itself, stop a client from tampering with the
ciphertext or replaying an old one — some cipher modes (CBC without a MAC,
for instance) are explicitly malleable: an attacker who cannot read the
plaintext can still flip bits in a predictable way and produce a different,
still-"validly-decrypting" plaintext, or a client could simply replay a
previously-observed valid ciphertext verbatim. **RAFT's problem was never
confidentiality — nobody in this system needs a vote request kept secret
from the client carrying it — the problem is *forgery*: can a client
produce, on its own, a message the receiving OJP server will accept as
genuine.** So "encryption" only helps here to the extent it also provides
**authenticated encryption (AEAD — e.g. AES-GCM, ChaCha20-Poly1305)**, or
plaintext plus a MAC computed with a servers-only secret. Used that way, it
is functionally the same mechanism as the signing idea in §5 — a receiver
can verify "only a holder of the servers-only key could have produced this"
— just built from a **shared symmetric secret** instead of **per-peer
asymmetric key pairs**. Framed this way: **yes, this is a realistic,
concrete, and honestly simpler alternative to full asymmetric signing for
solving the exact same problem**, and it deserves to be documented as a
serious option, not a footnote to §5's signing idea.

**How it would actually have to work, concretely:**

1. All N configured OJP servers (Mesh ON peers) share one or more symmetric
   keys, distributed and rotated out-of-band (the same key-distribution
   problem any of these schemes has — nothing here avoids needing *some*
   secure channel to hand out the initial secret, typically the same
   operator-controlled config/secrets-management path used for database
   credentials today).
2. A publishing OJP server computes an AEAD ciphertext (or a MAC over the
   plaintext) for `cluster_scope=true` / consensus-topic envelopes using
   that shared key, and includes it in the `Envelope`.
3. Every OJP server that receives the envelope (whether directly, via the
   mesh, or after client-relay hops) verifies the AEAD tag/MAC before
   trusting the message; if verification fails, it discards the envelope
   without acting on it.
4. Client-relay carriers never need the key at all — they just forward
   opaque bytes, exactly like they do today for any other topic — which is
   precisely the property being asked about: **a relaying client cannot
   alter the message undetected, because it cannot compute a valid tag
   without the shared key**, even though it can still read the plaintext if
   the scheme only uses a MAC without confidentiality (which is fine, since
   secrecy from the client was never the actual requirement).

**Does "consensus algorithm + encrypted/authenticated messages" work as a
combination? Yes, at the level that actually matters:** this closes
argument 1 of the messaging analysis's §5.3.5 (the trust-perimeter/
forgery problem) in essentially the same way the asymmetric-signing path
does — a receiver can now tell "produced by a real, key-holding OJP server"
from "crafted directly by a client," which is exactly the missing piece
RAFT's crash-only assumption needs to hold over client-relay.

**Where it's genuinely simpler than asymmetric signing (a real advantage,
worth being fair about):** no PKI, no certificate issuance/rotation
tooling, no per-peer key pairs to manage — symmetric primitives are cheaper
to compute and conceptually simpler to reason about for a small, fixed
cluster size. For a small N (a handful of OJP servers), this is a real,
non-trivial simplicity win over standing up asymmetric signing
infrastructure from scratch.

**Where it's genuinely weaker than asymmetric signing (the honest
trade-offs, not just "it's less secure" hand-waving):**

1. **Blast radius on key compromise is worse with a single shared secret.**
   If every server holds the *same* symmetric key, compromising or leaking
   it from *any one* of the N servers (or from wherever it's stored in
   config/secrets management) lets an attacker forge messages that appear
   to come from *any* peer — there is no way to tell "genuinely produced by
   server A" from "forged by whoever leaked the shared secret, claiming to
   be server A." With per-peer asymmetric keys, compromising server A's
   private key only lets an attacker impersonate server A specifically;
   every other peer's signature remains trustworthy. This is fixable —
   derive a per-peer subkey from a master secret plus the peer's ID (HKDF or
   similar), so each server signs/MACs with its own derived key instead of
   one flat shared secret — but that is additional design the naive "one
   shared key" version of this idea does not have for free, and it's worth
   being explicit that "shared symmetric key" and "per-peer symmetric key"
   are different points on this trade-off, not the same proposal.
2. **No non-repudiation.** With a genuinely shared key, any server that
   holds it *could* have produced any message attributed to any other
   server — there's no cryptographic way to prove which specific server
   actually did, only that *some* key-holder did. Per-peer derived keys (as
   above) recover attribution, but a flat shared key does not. Asymmetric
   signatures give this for free, since only the claimed signer's private
   key could have produced a valid signature.
3. **Replay protection is not automatic in either scheme and must be added
   either way.** AEAD/MAC verification proves a message wasn't tampered
   with; it does not prove a message is fresh. A client could still record
   and later re-publish a previously-observed, validly-authenticated
   envelope. This needs a monotonic sequence number or timestamp window
   checked by receivers regardless of whether the scheme ends up symmetric
   or asymmetric — not a point in favor of either option, just a shared gap
   neither closes by default.
4. **It does not touch argument 2 (amplification) at all**, same as
   asymmetric signing — `C×(N-1)` redundant relay RPCs is a cost problem,
   not a trust problem, and no cryptographic scheme changes that arithmetic.
   It also doesn't change §5's liveness point: a genuinely-authenticated
   vote that simply never gets relayed to enough peers because too few
   clients happen to bridge them is still a liveness failure.

**My recommendation, concretely:** if OJP ever does build the "make
Mesh-OFF-consensus viable" path flagged as deferred in §5, a **per-peer
derived symmetric key + AEAD** is worth recommending *ahead of* full
asymmetric signing as the first thing to prototype — it gets the same
trust-perimeter fix, is simpler to build and operate for a small, fixed
server count, and avoids standing up PKI machinery. Asymmetric signing only
becomes clearly worth its extra complexity if OJP later needs properties
symmetric keys don't give cheaply — e.g. a third party auditing which
specific server produced a message without being trusted with the ability
to forge as any server, or a much larger/more dynamic peer set where
distributing one evolving shared secret becomes its own operational
headache. Either way, **this remains a deferred, considered option, not a
change to the recommendation in §5** — the messaging analysis's rule (no
consensus traffic over client-relay today) stands until *some* such scheme
is actually built, and this section exists to make sure "encryption" is
evaluated as the concrete, evaluable option it is instead of being waved
away or conflated with confidentiality.

**Confidence:** high (85%) that authenticated encryption (AEAD/MAC) is
mechanically sufficient to fix argument 1's forgery problem, since it is
the same cryptographic property signing provides, applied differently.
Medium (60%) on the "prototype this before asymmetric signing" ordering
recommendation specifically — that depends on operational factors (how
often OJP's peer set actually changes, whether third-party auditability of
per-message provenance ever becomes a real requirement) this analysis
doesn't have deployment data on.

---

## 6. Does it matter that "clients" are applications, not strangers?

This is a fair, direct challenge to §1/§5's framing and deserves an honest
answer rather than a restatement of the existing conclusion: **yes, it
matters, but it changes the *characterization* of the risk more than it
changes the *recommendation*.** Worth separating carefully into what changes
and what doesn't.

**What's correct in the challenge:** application clients are not random
strangers off the internet. In the overwhelming majority of real OJP
deployments, they are the operator's own software — provisioned with
database credentials the operator itself issued, typically deployed through
the operator's own CI/CD pipeline, running inside the operator's own network
boundary. Calling them an "arbitrary, unvetted population reachable by
anyone holding valid database credentials" (as an earlier draft of this
document and of the messaging analysis did) overstates it — that phrasing
reads as if any random internet attacker could just show up, which is not
the threat this argument is actually about, and I should not have implied
otherwise. To that extent, this is a legitimate correction, and both
documents' phrasing is being softened accordingly.

**What doesn't change, and why "trusted to some extent" still isn't the same
question as "trusted for this":**

1. **Trust is scoped to a purpose, and consensus is a different purpose than
   querying a database.** An application is trusted (and provisioned) to run
   SQL against the specific schema/database its credentials grant access to.
   That is a deliberate, narrow grant. Nothing about that grant was ever
   evaluated against a different question: "should this same process also be
   allowed to influence which OJP server believes itself to be the cluster
   leader?" Those are different privileges with different blast radii if
   misused, and conflating them — "the app is trusted, therefore it's fine to
   let it carry cluster-governance traffic" — is exactly the kind of
   privilege-scope creep that least-privilege design exists to prevent. A
   bank teller being trusted to handle customers' cash doesn't mean they
   should also be trusted to approve the bank's own credit decisions; both
   are legitimate trusts, but they are not the same trust, and conflating
   them is the actual mechanism of the risk here — not an assumption that the
   teller is a thief.
2. **The gap is about verifiability, not about anyone's good faith.** RAFT
   (and BFT without added signing, per §5) has no mechanism to distinguish "a
   message a genuine peer produced" from "a message an application produced,"
   however trustworthy that application's operators are. A perfectly
   well-intentioned application with a bug — a stale library that
   accidentally double-publishes a relayed envelope with a corrupted
   `producer_id`, a dependency-confusion or supply-chain compromise the
   app's own team didn't cause or know about, a misconfigured test harness
   pointed at production — produces the exact same observable effect on the
   consensus algorithm as a deliberately malicious one: an envelope claiming
   peer authorship that didn't actually come from a peer. **The argument in
   §1/§5 was never really "your application developers might be adversaries"
   — it's "the protocol has no way to tell the difference between a trusted
   mistake and an untrusted attack, so it can't rely on trust as a substitute
   for a missing verification mechanism."** That framing survives the
   challenge intact, because it doesn't depend on how trustworthy the
   applications are in the first place.
3. **Aggregate exposure scales with population size, independent of
   per-application trust.** Even granting every single application 100%
   good-faith trust, OJP's own value proposition (many application instances
   behind a shared connection-pooling proxy — see the OJP architecture docs)
   means the *population* of processes that could carry a forged or buggy
   envelope is, by design, much larger and much less uniformly operated than
   the small, fixed set of N OJP server binaries: different teams, different
   release cadences, different dependency trees, different security posture,
   potentially different organizations if OJP is ever run as a shared/
   multi-tenant proxy tier. A single vulnerable dependency in *any one* of
   however many connected application processes is a materially bigger
   attack surface than a single vulnerable dependency in *any one* of a
   handful of OJP server processes the operator directly controls and
   patches — this is true regardless of whether every application team is
   acting in good faith, because it's a statement about surface area, not
   about intent.
4. **Operational asymmetry compounds this.** OJP servers are one binary,
   released and patched on one cadence by (presumably) one operations team
   that also owns the mesh's security posture. Applications are whatever
   each app team builds, on whatever cadence they choose, often without the
   OJP operator having visibility into their dependency health at all. "The
   organization is trusted" doesn't imply "every application in that
   organization is operated with the same security rigor as core cluster
   infrastructure" — and in practice it usually isn't, simply because most
   application teams' job is the application, not being part of OJP's trust
   boundary.

**Does this change my recommendation? Only partially, and here's exactly
where:**

- **It does not weaken the mechanism argument (point 2) at all** — that
  argument is independent of trust level by construction, so "the clients
  are trusted" doesn't touch it.
- **It does not touch the amplification-cost argument (§1's second reason, in
  the messaging analysis's §5.3.5) at all** — `C × (N-1)` redundant RPCs
  defeating RAFT's heartbeat timing budget has nothing to do with anyone's
  trustworthiness; it's arithmetic.
- **It does meaningfully lower my estimate of the *likelihood* of a
  deliberately malicious exploit specifically** (as opposed to an accidental
  one) in a typical, single-organization, well-run deployment — which is
  worth stating plainly since the previous draft's confidence numbers didn't
  separate "is the mechanism gap real" (yes, high confidence, unaffected by
  this challenge) from "how likely is someone to actually exploit it
  maliciously" (lower, and now further lowered for the common case where the
  operator runs both the OJP cluster and the applications).
- **It raises, rather than lowers, my concern about the multi-tenant/shared-
  proxy case specifically** — because that's exactly the deployment shape
  where "the applications are the operator's own trusted software" stops
  being true by construction (different customers'/teams' applications,
  sharing one OJP cluster, with credentials the *OJP operator* issued but
  where the *application code and its authors* are outside the OJP
  operator's own trust boundary). This is the same scenario flagged in §4 for
  the mesh-peer question, and it's worth treating as one coherent open
  question rather than two separate ones: **is OJP ever deployed as a shared
  connection-pooling tier across mutually-untrusting applications/tenants,
  and if so, does that same untrusted-third-party concern apply to the
  application population, the peer population, or both?**

**Recommendation, revised in light of this:** keep §1/§5's "no consensus
traffic over client-relay without message-level authentication" rule as-is —
the mechanism gap and amplification cost are unaffected — but stop
describing the reason as being about untrusted strangers. The accurate
framing is: *client-relay lets a broader, less uniformly-operated, and
harder-to-verify population than "the N configured servers" produce
messages the consensus algorithm cannot distinguish from genuine peer
traffic, and that gap exists regardless of how trustworthy any individual
application actually is.* That framing survives this challenge, is more
honest about what the actual risk is, and doesn't require assuming bad faith
on anyone's part.

**Question for the team:** does OJP expect single-operator deployments only
(one org runs both the OJP cluster and every connected application), or is a
shared/multi-tenant proxy tier (one OJP cluster, multiple independently-
operated applications/customers) a real target? This is the same open
question raised in §4, and the answer changes how much weight the
trust-perimeter argument should carry in practice — for a genuinely
single-operator deployment, the residual risk here is closer to
"defense-in-depth against your own bugs" than "defense against an
adversary," which is still worth having but is a different priority than if
multi-tenant sharing is a real, near-term scenario.

---

## 7. Overall recommendation

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
4. **The trust-perimeter argument in §1/§5 is about privilege scope and
   verifiability, not about assuming applications act in bad faith (§6)** —
   worth stating as its own recommendation because it changes how this
   should be communicated to the team: don't frame this as "we don't trust
   your apps," frame it as "the protocol can't yet tell a trusted app's
   message apart from a forged one, and that gap doesn't shrink just
   because the app is trusted for something else."
5. Update `OJP_MESSAGING_PROTOCOL_ANALYSIS.md` (done, see cross-references
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
