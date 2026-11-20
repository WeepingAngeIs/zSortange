# Beta.6: bounded nonblocking preparation and durable publication

Verified November 16, 2026. **All seven final jobs passed on the same source. Keep this prototype experimental; no replacement playable JAR is required.**

No user PC/profile, installed JAR, Java arguments, settings, save or live server was accessed or changed. All Minecraft execution used disposable GitHub environments. No portal backend or unload-engine patch was installed or bundled.

## Exact provenance

Starting report: `82a53db39ebd8a3796e30d4bd3038279c1e203ca`.

**Measured executable source:** `46bf42910165c256435da04e37f13f11b5a3bf30`, branch `feature/elemental-pockets`.

**Workflow 43894333534**, Durable preparation semantics and bounded prototype, all seven jobs passed:

| Lane | Job |
|---|---:|
| Paired cold preparation, baseline first | 133393677938 |
| Paired cold preparation, candidate first | 133393671249 |
| Delayed/failed completion, cancellation and reanchoring | 133393676758 |
| Selected process cuts and separate-process restart | 133393670635 |
| Two real clients, room construction and restart | 133393675948 |
| Two real clients, cold/unsafe recovery | 133393673203 |
| Two real clients, transitions, active shutdown and interrupted recovery | 133393671939 |

The last job completed at 15:31:13 UTC. This documentation-only follow-up is not a new executable.

SHA-256:
- Playable beta.6: `5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11`.
- Separate new preparation experiment: `edbbffa5377b9a69c9d2c6fd9a9fcd7951238e29578007392f6524992e153723`.
- Existing efficient ticket experiment: `6aa9f312cc2b526a86b2122510d50021845a9b12aa8039298af2012748d30bcd`.
- Actual-client fixture: `ce780484ceada6ebe30c52fe56106d611bfe69669d29eeba9fc1aba5566bbe13`.

All 74 main files, 12 existing ticket-experiment files and 10 acceptance Java files match the starting allocation source. The new module is outside the playable JAR and refuses non-CI execution. Ordinary automatically triggered workflows are not the new candidate result.

## Completion semantics from the pinned binary

Audit workflows **43886596858** and **43891771275** inspect Minecraft 1.21.1/NeoForge 21.1.249 signatures, bytecode and decompilation, including auxiliary POI storage. The resolved development binary SHA-256 is `e73b1d34d83fe339e51884bd0620f031a2ac0e1850fb75f7d3bea2efc7ad0aaf`.

`ServerChunkCache.getChunkFuture` calls managedBlock on the server thread. A future-returning method is not necessarily nonblocking. The prototype instead lets the existing strong tickets drive engine loading/generation, observes `getChunkNow`, and returns to the tick loop while a needed chunk is absent. No unsafe background world access replaces the wait.

`ChunkHolder.isReadyForSaving` requires zero generation references and completed save dependencies; these are not disk receipts. Exceptionally completed dependencies are rejected.

`ChunkStorage.write` returns a raw `IOWorker.store` future. The worker removes a pending entry before its write, so a failed removed write may be absent from a later pending-write snapshot. **A later flush alone is not proof that every intended write succeeded.** The experiment retains every raw receipt and requires all to succeed before submitting flush.

The audited `IOWorker.synchronize(true)` queues flushing through the engine I/O mailbox and propagates failure. `RegionFileStorage.flush` reaches region-file flush and `FileChannel.force(true)`. The synchronous `ChunkStorage.flushWorker` wrapper joins the result; the prototype does not call it or the original synchronous `save(true)` barrier. It polls completion without blocking the server thread.

This is the pinned engine/Java completion boundary, not an atomic transaction across player, ledger and region files or an arbitrary filesystem/controller/power-loss guarantee.

### Auxiliary POI durability

The original chunk save also flushes point-of-interest data. The exit lodestone creates a POI. The initial chunk-only prototype passed tests but omitted that auxiliary boundary and was therefore not accepted as the final durable result.

The final prototype strictly encodes already loaded POI sections in the actual changed exit column, retains the POI write receipt, and requires its own flush. It never treats unknown cache entries as empty or clears untouched columns. A registry guard rejects other POI-producing palette blocks rather than silently broadening unverified scope. Encoding errors fail closed.

This pinned `SectionStorage` adapter is not a general arbitrary-room/container/entity-store or cross-version persistence API. Generalization requires explicit touched-store/column tracking.

## Thread ownership and state machine

All world/block/entity access, serialization, save-event dispatch, authorization, identity checks and ledger publication remain on the server thread. NBT is detached before I/O submission. Worker callbacks record only scalar evidence, never READY or game state.

The durable BUILDING marker remains. The prototype snapshots at most one of 16 destination columns per tick, bounded to two MiB per snapshot. The changed exit POI column adds one receipt, making 17 receipts per room. It polls those writes, then both asynchronous flushes, then revalidates the exact pocket record/epoch, anchor, preparation token, live lease and authorization on the server thread. Only then does the unchanged ledger commit BUILDING -> READY and the existing arrival path run.

The ledger commit, block work, serialization and existing callbacks still execute synchronously. This is not a claim that every possible preparation operation is asynchronous. The targeted missing-chunk and whole-store flush waits have been replaced, not moved wholesale to an unsafe worker.

Cancellation invalidates publication. Already queued I/O can finish, but late completion cannot publish or enter a cancelled/reanchored room. Existing BUILDING restart refusal remains instead of automatically regenerating uncertain data. A failed partial room can require repair/restoration; it is not silently reused.

The pure owner-thread fence passed 2,900 assertions for delays, failures, cancellation and ownership. These are property checks, not 2,900 runtime/client cases.

## Runtime failure and interruption evidence

Fault/cut lanes use ordinary server-player fixtures with embedded packet sinks, not real clients. The engine storage workers and production ledger/recovery still execute.

- Publication held for 60 ticks after actual I/O completion preserved BUILDING, no entry and no return record. Release permitted READY/entry. This tests delayed observation/publication, not a 60-tick physical disk stall; pure fence checks separately delay writes/flush futures.
- Injected chunk faults caused 16 real `RegionFileStorage.write` exceptions on I/O-worker threads; a POI-specific test caused one `type=poi` write failure. Raw receipts failed and READY remained withheld.
- A one-shot region flush exception propagated and withheld READY. The log does not identify which store won that injection, so this is not claimed as two independently tested flush exceptions.
- Failed BUILDING rooms refused regeneration. Exact tickets/jobs drained and named player inventory remained conserved.
- Cancellation uses a logout hook; revocation temporarily changes hub ownership. Both invalidate the token. Reanchoring changes the epoch but retains the cell; completing the old held future afterward cannot publish READY. These fixture operations are not a new network logout or permission-UI claim.

A clean stop with unresolved publication preserves BUILDING and clears jobs/tickets. Actual I/O had completed before that held-publication stop; it does not prove shutdown can interrupt hung physical I/O without waiting.

Three `Runtime.halt(77)` cuts occur after all raw writes/before flush, after both flushes/before READY, and after committed READY/before entry. Separate processes retain the original pocket/cell and named player inventory. BUILDING cuts refuse regeneration/entry; READY permits entry without rebuilding. For post-flush cuts and clean stop, raw POI region data is read **before loading the destination chunk**, verifying lodestone type/coordinates so reconstruction cannot conceal a missing save. Four physical chests and their identities are also checked. No raw-POI durability promise is made for the pre-flush cut.

Exact engine-ticket and job cleanup is asserted. Measurement/fault lanes additionally reach zero destination caches. Restart chest inspection deliberately loads chunks and is not mislabelled as a no-load block-entity inspection. Selected cuts do not solve Minecraft's non-atomic file saves.

## Measured cold latency and tradeoffs

Four fresh-world/JVM cases, paired in reversed order on two runners, prepare eight pockets for eight server-player fixtures. **Zero real Minecraft clients** run in the measurement lane. The efficient remote-ticket module is absent: both policies retain the same original strong ticket policy, isolating preparation. They then exit, remotely view for 400 ticks, and drain for 650 ticks.

| Within-host comparison | Baseline | Candidate |
|---|---:|---:|
| Xeon: maximum per-tick preparation-method time | 841.7 ms | 13.0 ms |
| Xeon: maximum cold-preparation event tick | 864.3 ms | 118.2 ms |
| Xeon: preparation-phase ticks for eight rooms | 212 | 371 |
| Xeon: sampled RSS peak | 1,105.8 MiB | 1,260.5 MiB |
| EPYC: maximum per-tick preparation-method time | 624.3 ms | 8.8 ms |
| EPYC: maximum cold-preparation event tick | 638.2 ms | 46.3 ms |
| EPYC: preparation-phase ticks for eight rooms | 213 | 367 |
| EPYC: sampled RSS peak | 1,266.2 MiB | 1,517.2 MiB |

Lower preparation pauses trade against longer admission, roughly 7.7-8.0 additional seconds at 20 ticks/second. Snapshotting is deliberately spread across ticks. These are two instrumented pairs, not a universal speedup.

Each candidate case records 128 chunk-column snapshots plus eight auxiliary exit-column snapshots. Timed chunk-snapshot maxima are 0.90/0.66 ms; that sub-timer excludes preceding POI capture, which remains included in overall preparation time. READY ledger commits peak at 1.85/1.46 ms.

Other pauses remain: candidate whole-case event peaks outside preparation are 386.2 ms during drainage and 199.9 ms during return, with only 0.015/7.3 ms in the pocket service. RSS is higher in both current pairs by about 14.0%/19.8%. CPU/allocation/JFR were not compared in this targeted phase. It does not establish lower global CPU/RAM, FPS/TPS benefit or the elimination of all blocking.

All four cases reach 8,481 cache entries and 140 logical requests at peak. Final measurement drainage reaches zero requests, cache, exact engine tickets and preparation jobs.

### Environment and bounds

Baseline-first uses Xeon Platinum 8573C; candidate-first uses EPYC9V45. Both expose four guest CPUs and about15.61GiB RAM under Microsoft virtualization/Linux7.0.0-1019-azure. Temurin21.0.13+9, MC1.21.1, NeoForge21.1.249, Xms256/Xmx2048MiB, G1, ergonomic processors2, view3/simulation2, flatseed860612, no spawn tickets. Processor count is not CPU pinning. Worlds/JVMs are fresh; libraries/OS cache are reused.

Fixture bounds:180s/process,2100ticks,4GiBRSS,minimum2GiBhostavailable,92%heap,15000cache; save token400ticks; jobs25min (client jobs40min). No cutoff fired. Measured RSS peak1517.3MiB, minimum hostavailable13537MiB. No long soak/full-pack/network-load claim.

Event timing excludes lowest-priority fixture actions/final CSV output but includes unload-observer work. RSS1Hz, per-tick timing/heap/cache. The observer is bounded in stored identities, not zero CPU overhead. Different prior-profiler brackets cannot be subtracted as an exact observer-cost estimate.

## Unload rescheduling: specific observed condition

Runtime evidence now proves that **the exact same holder and already-completed save-sync future is repeatedly requeued within one tick while generation references remain positive**. For example, candidate-first tick439 observes chunk[309,253],14generation references, completed future and repeated same identity. Baseline traces show the same condition.

The pinned callback reschedules if `isReadyForSaving` is false. Attaching to the completed future immediately queues another callback without waiting for generation references to reach zero; `processUnloads` drains that queue under its existing budget. This identifies the proximal loop condition, not why references were reacquired/persisted or a universal engine defect.

| Case | Observed callbacks | Positive refs/completed future | Repeated same identity, tracked subset |
|---|---:|---:|---:|
| Xeon baseline | 61,100,171 | 61,087,816 | 9,486,943 |
| Xeon candidate | 171,230,528 | 171,217,967 | 42,348,738 |
| EPYC candidate | 280,935,268 | 280,922,732 | 42,530,732 |
| EPYC baseline | 145,588,389 | 145,576,198 | 16,525,785 |

These are invocations, not unique chunks. Identity tracking is capped at64holders/tick; repeated-identity counts are a subset. Example log threshold labels count repeats for the tick, not per-holder. The observer adds work to each callback, so rates are not uninstrumented production benchmarks.

Preparation does not solve this churn; candidate callback totals are higher and cold-to-remote event ticks still approach50ms. **No unload callback, queue, generation reference or save ordering was patched.** A future fix must preserve both readiness prerequisites and exact cleanup, not drop callbacks or force reference counts to zero.

## Exact-final-candidate real clients

Three separate jobs use two genuine Minecraft client JVMs over localhostTCP, generated launchers and the packaged mod plus exact experiment. Automation creates conditions/directs actions; this is not human/WAN/full-pack acceptance. Client Java fixtures are unchanged.

Room construction passes delegated permissions, added physical chest/270slotview, a single conserved deposit, client SIGKILL/reconnect, return/reanchor and separate server restart. Clean restart can flush data; the selected forced-cut lanes provide stronger boundary-specific evidence.

Cold recovery passes absent-home/absent-fallback, both-unsafe queued retries, unresolved stop/restart, repaired-spawn automatic return, inventory/identity and cleanup. Controlled login-spawn staging is explicit, not a claim that ordinary login leaves spawn cold.

Transitions pass remote/visit changes, shared/independent viewers, same-hub revision invalidation and revoked stale intent, real client SIGKILL/reconnect, active stop with34requests removed to zero engine tickets, and forced return interruption before confirming saved player position. Restart retains10nameddiamonds stored+1held,17namedemeralds,owners/room IDs/cells and safe return. Final requests/cache/return records empty.

These lanes also load the unchanged efficient remote selector. Their baseline/candidate labels describe that ticket policy; **both use new preparation**, not extra preparation-baseline timing pairs. OPAC0.33.0/CarryOn2.2.6.13 are present with retained hashes, not certification of Anya's pack. Native isolation/process supervision remain; no mutating item intent is blindly retried.

## Superseded attempts and decision

Initial full suite43890183852 atf7a54ee3ee121ecb43c26fcbfb1577fad0572d5f passed but omitted auxiliary POI durability. It is not the final result.

First auxiliary suite43893270932 at8c2a6d0b5a46e0877984a3b1a72441ff141b8242 waited on unknown POI caches in untouched void columns and safely timed out, retaining BUILDING. Full chunks do not imply every POI column cache was read. Final repair covers the actual changed exit column and rejects expanded scope; it never assumes unknown sections empty or weakens flush requirements. Only final workflow43894333534 supplies primary evidence.

**No production promotion or replacement JAR.** The prototype supports pinned fixed-room async write/flush completion and selected failure safety, not a public API for all modded stores. Residual synchronous work, longer preparation, higher RSS, disk-hang uncertainty, partial-room repair availability and unload churn remain. A held publication gate is not a physical disk hang. General power-loss atomicity, more real clients, full pack, grave/mover and long-soak behavior remain unverified.

Next bounded target: trace the reference lifetime and test a deferred retry only for the observed completed-future/positive-generation-reference condition, preserving saves and exact unload/reload/ticket safety. No broad engine patch or human test is needed for independent investigation.

Beta.6 formats/identities/inventories/art/UI/default-off gates/damage suppression/non-live preview remain unchanged. Live portals and unrestricted room machinery/containers remain unimplemented.

## Final artifacts

- baseline-first14238702212 SHA256 `96e1c2d2a8de503a395f9e49115066def7031e9f7784bbf61fd38e43c72ed40e`
- candidate-first14238422415 SHA256 `13ed974b15a221cae759d9670a628677e5df24b2ed0afdf641e4940f2e62d3d6`
- failure/cancel14239410872 SHA256 `3d2720f1292b0855bea3a4ff3e064e876981e2753c922beaadefbe48ba503ea8`
- cuts/restart14239396216 SHA256 `1f158bc531ff672420ba383a4fc3833e99b4d87033f826686137d27a4fe84c3d`
- room14237808377 SHA256 `949a32990d06086a5baf3680fd02966f7b4ee14f65a983b3c960bd8b6a2beb47`
- cold14237948986 SHA256 `7d06cae7dfd59569d22fe369a85668973ef068ad1159d9fb4f787d810510436a`
- transitions14238803301 SHA256 `9f5d90592f8aedb24b410a3cd9c43469fcde30e665e3324e03a5a3cb5f336b0d`

The delivered evidence kit retains exact source/rawlogs/CSVs/screenshots, provenance and read-only verification/analysis scripts, not executable JARs, player worlds or full third-party decompiled classes. The pinned audit scripts and hashes reproduce the semantic investigation.

Workflow: https://github.com/WeepingAngeIs/zSortange/actions/runs/43894333534
Exact source: https://github.com/WeepingAngeIs/zSortange/commit/46bf42910165c256435da04e37f13f11b5a3bf30
Engine audit: https://github.com/WeepingAngeIs/zSortange/actions/runs/43886596858
POI audit: https://github.com/WeepingAngeIs/zSortange/actions/runs/43891771275
