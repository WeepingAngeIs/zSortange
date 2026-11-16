# Beta.6 remote-only ticket safety experiment

Measured November 16, 2026. **Both counterbalanced lifecycle jobs passed. Test-only intervention, not a production optimization. No replacement playable JAR is required.**

## Provenance

Starting report checkpoint `1a03661960802bd4791a3c5534c3bff75c0656b8`; exact measured source **`450ab991f8c490cf1ea16a08915da0a242b30594`**, branch `feature/elemental-pockets`.

New workflow **43830237468**, `Remote-only ticket lifecycle and measurements`:
- Baseline-first job **133175370549**, successful, completed 2026-11-16 08:38:54 UTC; artifact **14209706249**, ZIP SHA-256 `9dabf8cad6211c6e19726cb8d1d99db8c7ac4312601742750b0015a43a592585`.
- Candidate-first job **133175370794**, successful, completed 2026-11-16 08:40:43 UTC; artifact **14208984259**, ZIP SHA-256 `78ff67842ed3391c7ea17e78f0aaaad325fe501923c0dbc12dcf68a6b6494a82`.

Unchanged beta.6 playable JAR: `5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11`.
Separate experiment JAR: `81961a93f48b9fc5cad269e9f311fa9516e84317ba1d66b2542f396c3e04edf3`.
Separate acceptance JAR: `ddc6bea9a9bdc25e299226b47f13a7d144f3bfae759b7a850dec8797853616f1`.

Downloaded archives, source comments, case/leg markers and actual server/client loaded-JAR hashes were independently checked. All 74 retained src/main entries match the previous beta.6 source. No production format, inventory, identity, artwork/UI, gate or damage rule changed. No user profile, installed JAR, Java argument, setting, save or live server was accessed or changed. This report-only follow-up is not a newly measured executable. The old 45-stage scenario and automatically triggered regression workflows are not the new coverage.

## Scoped candidate

The experiment lives outside src/main and refuses non-CI execution. Both policies run the same auditing/telemetry. Only the candidate uses argument 0 (FULL) instead of original argument 2 for READY pocket-destination keys with no preparation, exclusively REMOTE leases, and no server player physically occupying the destination ring. Pending VISIT leases or released visitors awaiting safe return retain stronger requests. Missing/unknown state retains the original requirement.

**Visitor, preparation, home and recovery requests retain argument 2.** Desired keys, permissions, lease deadlines, range and saved data are unchanged. The broad previous experiment is not reused.

Applied levels are remembered per dimension/chunk/pocket. A level change adds its replacement before removing the exact former level. Ordinary release and the separate stop path remove the recorded level. Read-only inspection of actual DistanceManager ticket sets must match expected identities/levels. Candidate seed cases recorded 32 existing-key transitions. Active-session stop audited **34 live requests -> 0**, including an empty actual engine-owned ticket set.

## Actual client coverage

Four fresh-world cases, two per policy, used **two real Minecraft client JVMs over localhost TCP**. Clients use generated development launchers but load the packaged playable JAR, not main output directories. An automation fixture sends directives and the clients send ordinary entry/remote/menu/exit traffic. Offline mode is confined to loopback. No FakePlayer or embedded connection replaces these final clients.

Each case has three server process legs: a normal stop with active users, a restart followed by intentional recovery interruption, then another restart and cleanup. There are 37 checked seed stages, six completed post-stop stages before the cut, and eight post-cut stages: **51 sequential checkpoints per case**, not 204 independent suites.

Passed:
- Distinct rooms/owners with independent remote views and repeated close/reopen. Three 500-tick remote steady windows per case.
- One real client withdrawal conserves 11 named Alpha diamonds as **10 stored + one held**; 17 named Beta emeralds remain unchanged, with components and room ownership checked again after restarts.
- A visitor joins an existing remote viewer: destination tickets upgrade without resetting the existing remote deadline. The visitor exits and tickets downgrade. Same-player remote-to-visit and visit-to-remote transitions also work.
- Two shared remote viewers do not duplicate requests. Revoked stale intent and denied reopening move nothing. **A same-hub policy revision intentionally invalidates every older view of that hub, including the owner.** The owner explicitly reopens against the new revision; this beta.6 safeguard was not relaxed.
- SIGKILL of an actual remotely viewing client, and separately of a visiting client, followed by fresh client processes and real reconnects. Relevant leases release, visiting recovery completes, and the independent view stays usable.
- Server stop with Alpha remotely viewing and Beta visiting: exact-level ticket removal observes 34 -> 0 without a pre-drain. Restart has no resurrected destination loading. Reconnected clients preserve room IDs/cells/owners and samples.
- Explicit **Runtime.halt(77)** after return teleport but before confirming saved player position. The cut asserts outside=true, a durable unfinished return record and audited tickets. Another actual reconnect recovers successfully without duplicating rooms/items.
- Alpha's destination unloads while Beta remains usable; after Beta closes and 650 cleanup ticks, requests, applied ranks, destination rings/cache and unfinished returns are zero.

Fixture APIs seed owners/positions/samples, change policy and invoke process controls. This is not human interaction, WAN latency or full-pack certification.

## Repeated measurements

The preselected steady summary takes the trailing 300 ticks of each 500-tick remote window: 900 ticks per case. Windows within a case are correlated, not independent replicates. Both cases of each policy show the same steady counts:

| Two remote-only rooms | Baseline | Candidate |
|---|---:|---:|
| Logical requests | 34 | 34 |
| Strong destination requests | 32 | 0 |
| FULL-only destination requests | 0 | 32 |
| Home keys, original strength | 2 | 2 |
| Raw cache entries | 1800 | 1352 |
| Ready full chunks | 128 | 32 |
| Block-ticking-ready | 72 | 0 |
| Entity-ticking-ready | 32 | 0 |
| Actual tickChunk calls/tick | 0 | 0 |

Remote-only cache reduction is **24.89%**, ready-full reduction **75%**. These are not RAM/CPU/FPS percentages. Baseline remote-only actual chunk ticking was already zero. Visitor counts remain identical: 2145 cache, 242 full-ready, 162 block-ready, 98 entity-ready and 98 tickChunk calls/tick. Both policies' cross-replicate whole-case peak is 2575 cache entries. No reduction in visitor-driven peak loading is established.

| Runner/order | Policy | Event median/p95 ms | Thread CPU mean ms/tick | JVM core equivalents | Seed RSS peak MiB |
|---|---|---:|---:|---:|---:|
| EPYC 9V74, first | Baseline | 1.448 / 1.915 | 1.456 | 0.112 | 1281.4 |
| Same runner, second | Candidate | 1.345 / 1.824 | 1.362 | 0.136 | 1006.2 |
| EPYC 7763, first | Candidate | 1.813 / 3.384 | 1.807 | 0.177 | 1305.5 |
| Same runner, second | Baseline | 1.614 / 2.223 | 1.603 | 0.115 | 1042.3 |

RSS peaks cover the entire seed leg, not just selected steady samples. **Timing/RSS outcomes reverse between jobs**; process CPU is higher for the candidate in both steady summaries. Reflective per-ticket selection raises service medians from 0.090 to 0.167 ms and from 0.109 to 0.253 ms. This is not a demonstrated general performance win. Native per-pocket computation may avoid the fixture overhead, but that is unmeasured.

Long seed pauses remain: candidate maxima 698.6/871.0 ms during cold visits; baseline 407.4 ms during a cold visit and 1449.4 ms in fixture initialization. These are not steady remote costs. No instruction-level causal attribution is claimed.

## Environment and method limits

Two separate runners, each four guest logical CPUs and about 15.61 GiB visible RAM, Microsoft virtualization, Ubuntu ubuntu26 image 20261111.196.1, kernel 7.0.0-1019-azure. Baseline-first used AMD EPYC 9V74; candidate-first EPYC 7763. Compare paired values within a runner; this is not a controlled CPU comparison.

Temurin 21.0.13+9, Minecraft 1.21.1, NeoForge 21.1.249. Server initial/max heap 256/2048 MiB, G1GC, ergonomic processor count 2 (not OS pinning), view distance 3, simulation distance 2. Two clients use 1000 MiB heaps, software GL, render distance 3 and a 25 FPS cap. Client simulation-distance request 2 is rejected to Minecraft's client range; the dedicated-server value remains 2.

OPAC 0.33.0 and Carry On 2.2.6.13 are loaded in the isolated runtime. Exact hashes are in runtime-inputs.json; their presence is not a new exhaustive compatibility certification.

Per-tick metrics include event/thread/process/service durations, heap/GC, tickets, cache and actual ServerLevel.tickChunk invocations. ChunkHolder readiness is sampled every 20 ticks without loading chunks. Readiness is not execution. Event timing includes fixture/audit work in its bracket and excludes the separately recorded final telemetry segment. Process CPU still includes instrumentation/GC/asynchronous work and excludes clients. This is not an uninstrumented benchmark.

RSS/host memory sampled each second after server readiness. Group RSS sums test process groups, not PSS. No forced GC or OS-cache clearing. Fresh world/JVM per case; libraries/OS cache reused, and restart/warm phases deliberately reuse saved regions.

No resource guard fired. Final runs peak at 3859.6 MiB summed process-group RSS and 1305.5 MiB server RSS; host available remains above 11411.4 MiB. Cutoffs include >6000 destination cache, >92% used heap, >3 GiB server RSS, >6 GiB group RSS, <2 GiB host available and timeouts. These are sampled peaks, not continuous hardware maxima.

## Earlier failures and exclusions

43826887200: Gradle opt-in/source-set name collision, fixed in test build only. 43827075901: stale fixture home block-entity references after startup unload, fixed by refreshing setup references without weakening loaded-only access. 43827669849: wrong same-hub revision assumption, corrected to preserve snapshot invalidation. 43828870794: baseline-first initial interaction timed out during client synchronization; the exact vanilla rejection instruction was not traced. A client now waits for a synchronized target/range/stable readiness and sends one intent, not blind mutation retries. Its candidate-first job already passed both cases, but that overall failed workflow is not called green or pooled into the primary comparison. **Only final same-source workflow 43830237468 supplies the primary measurements.**

## Decision and concrete remaining gap

**No new playable artifact is warranted yet.** Focused safety and deterministic loading reduction pass, but the reflective candidate is not promoted. Production source/hash/gates and non-live preview remain unchanged.

The homes share an already loaded chunk, and restart setup explicitly loads it before login. Thus interrupted return recovery is genuinely tested, but **recovery-ticket allocation for an absent home chunk is not**. Nor is an entirely unsafe home plus spawn. The predicate excludes recovery keys and encountered home keys retain original levels; that is not a substitute for a cold-home runtime test.

Next bounded work: unloaded-home recovery, then an efficient native per-pocket selection prototype under maximum-cap churn. More than two real clients, long soaks, WAN conditions, grave mods, arbitrary movers and other save cut points remain separate. The selected recovery cut does not solve Minecraft's non-atomic player/region saves. No human test is required for the next independent work. Live see-through portals remain unimplemented; no backend was installed or bundled.

Primary evidence: https://github.com/WeepingAngeIs/zSortange/actions/runs/43830237468
Measured source: https://github.com/WeepingAngeIs/zSortange/commit/450ab991f8c490cf1ea16a08915da0a242b30594
