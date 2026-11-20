# Beta.6 allocation, retention and cold-transition investigation

Verified November 16, 2026. **Both final jobs passed all eight profiling cases. Production and the efficient candidate are byte-identical to the prior checkpoint. No replacement JAR is required.** This is a measurement/report checkpoint, not a release or a new gameplay acceptance claim.

No user PC, profile, installed JAR, Java argument, setting, player world or live server was accessed or changed. All Minecraft execution used disposable GitHub environments. No portal backend was installed.

## Exact provenance

Starting report `9961adc4fd7743cb96da3dbd57faf26b01f3eb81`.

**Measured source `7a3f6142f6677783978b319e1954d0d8de307b8e`**, branch `feature/elemental-pockets`.

**Workflow 43857495564**, Bounded allocation and cold-transition investigation:
- Candidate-first job **133271819555**, passed four cases, finished 2026-11-16 11:58:21 UTC.
- Baseline-first job **133271820067**, passed four cases, finished 2026-11-16 11:58:47 UTC.

The workflow queued behind the earlier bounded attempt. Queue time is not workload time. This documentation-only follow-up is not another measured executable.

SHA-256:
- Playable beta.6: `5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11`.
- Existing efficient experiment: `6aa9f312cc2b526a86b2122510d50021845a9b12aa8039298af2012748d30bcd`.
- Separate observer: `80ddd1c6863f00c2d7e543358c5c5909b09a02f3e1daad6f788b3a3a5871e212`.

The runner rejects changed playable/candidate hashes. All74 src/main files,12 ticket-experiment files and11 client-acceptance files match `cb41aca54fb5e8cfc1d194b6b09bb03e457a8970`. Exact candidate real-client safety remains prior workflow43847949879; it is not relabelled as newly executed client coverage. Automatic ordinary workflow repetitions are not the new result.

## Controlled method

Two fresh-world/JVM lean pairs reverse baseline/candidate order. Each runner then executes the reversed diagnostic pair. Eight complete cases total. Diagnostic cases are separate, not additional clean timing replicates.

The existing maximum-cap churn workload uses **17 server-player fixture identities with embedded packet sinks, zero real client JVMs/network sessions**, at most eight active pockets/16leases. It includes preparation, three mixed cycles, finite admission, independent deadlines, guarded inventory transfers, eight-user unsafe recovery contention and final drainage. Reusing its assertions controls correctness, not new gameplay coverage. Lean runs measure about5732–5737ticks; diagnostic runs add73–88 explicitly labelled hold ticks. No hours-long soak.

The new observer suppresses the old periodic ChunkHolder readiness scan and periodic telemetry audit, retaining original stage safety assertions. It times whole MinecraftServer.tickServer and inclusive spans for service, prepare, sync, recovery, player save, arrival, getChunk, chunk save, ledger commit, chunk maintenance, fixture and audits. Thread CPU/allocation are recorded per tick; heap/cache every20ticks, RSS1Hz, PSS0.2Hz. Lean cases have no forced GC, JFR or NMT.

Diagnostic cases add capped raw JFR, NMT and live class histograms at empty/loaded/final-idle checkpoints. Logs confirm exactly three Heap Inspection Initiated full GCs per diagnostic case and none in lean cases. Fixture progression pauses during labelled diagnostics, but engine/service still tick. JFR analysis streams raw recordings at64-frame depth; shallow default exports can hide caller chains.

Histograms measure global shallow live bytes, not a pocket-owned dominator graph. Heap-info is a later read and includes fresh allocations. NMT does not track all native allocations or explain every RSS byte. Inclusive method spans overlap and cannot be summed as exclusive costs. Exceptional child exits lack a RETURN span; enclosing tick/service timing remains. Final observer_ns excludes CSV write/flush. The new whole-tick bracket differs from the old event bracket, so exact prior observer overhead is not inferred by subtraction.

## RSS reversal reproduced, but live objects tell a different story

Warm summaries use trailing300ticks from each of three post-transition remote phases,900 correlated samples/case, not900 independent trials.

| Lean paired host/order | Policy | Tick median/p95 ms | Thread CPU mean ms | Allocated KiB/tick | Peak committed MiB | Peak RSS MiB |
|---|---|---:|---:|---:|---:|---:|
| Xeon, first | Baseline | 1.797/2.252 | 1.843 | 344.9 | 1338 | 1664.9 |
| Same host, second | Candidate | 1.273/1.592 | 1.300 | 335.3 | 1192 | 1511.1 |
| EPYC, first | Candidate | 1.357/1.660 | 1.382 | 333.6 | 1348 | 1673.4 |
| Same host, second | Baseline | 1.660/2.122 | 1.551 | 342.8 | 1222 | 1555.5 |

Warmed tick medians improve29.2%/18.3% within pairs, but two instrumented pairs are not universal FPS/TPS claims. Candidate RSS is153.9MiB lower on Xeon,117.9MiB higher on EPYC. Heap commitment moves146MiB lower/126MiB higher. Samples at the RSS peak's recorded tick show the same committed values, with only369.7–604.5MiB used heap. This supports commitment and GC/allocation history as a substantial component of this measured reversal, not exact retroactive attribution of every older RSS byte.

### Loaded diagnostic checkpoints

| Host/policy | RSS before GC MiB | Global live shallow MiB | Committed before→after MiB | RSS after MiB |
|---|---:|---:|---:|---:|
| Xeon baseline | 1225.2 | 257.5 | 908→874 | 1192.9 |
| Xeon candidate | 1811.9 | 235.7 | 1472→800 | 1141.9 |
| EPYC baseline | 1294.2 | 254.2 | 960→860 | 1182.5 |
| EPYC candidate | 2079.4 | 239.3 | 1878→814 | 1013.8 |

Candidate has21.8/14.8MiB less shallow live data despite higher pre-GC RSS. EPYC candidate used heap drops from1310.7MiB to245.3MiB at the later heap-info read. Diagnostic GC perturbs state; this is not advice to force GC in a profile or a normal-memory guarantee.

Final idle live totals converge to200.6–200.9MiB, commitment680–688MiB and post-inspection RSS1002–1045MiB. StoreMenu and PocketService.Ticket objects are absent. Storage entities drop41→9, matching retained fixture home hubs;17fixture players and9durable pocket records remain intentionally. Global chunk objects also remain around home/player fixtures. Actual pocket cache and exact engine-owned tickets are independently zero. Histograms cannot prove dimension ownership of every remaining object; this supports checked cleanup, not a universal no-leak guarantee.

## Initial remote tail: unload callbacks and transient allocation

The first remote phase after initial visits differs from warmed phases. Three lean cases have median48.65–49.87ms whole ticks while pocket service medians are0.127–0.145ms. Chunk maintenance dominates the timed bracket. The fourth case has1.785ms median but49.966ms p95, so tail duration varies.

Across its501ticks, current-server-thread cumulative allocation is43.78GiB Xeon baseline,23.48GiB Xeon candidate,27.44GiB EPYC candidate and18.03GiB EPYC baseline. These are repeatedly reclaimed cumulative bytes, not resident memory, and exclude other threads. Candidate does not consistently reduce this transition total.

All four final diagnostic traces show prominent server execution/allocation through:

```
ChunkMap.processUnloads
 -> CompletableFuture completion callbacks
 -> ChunkMap.lambda$scheduleUnload$12
 -> ChunkMap.scheduleUnload
 -> thenRunAsync / whenComplete / ConcurrentLinkedQueue.offer
```

Allocation samples include lambdas, completions/futures and queue nodes. Explicit preparation, chunk-save and ledger timers are absent in this phase. This localizes the tail to **engine unload scheduling/completion churn**, not repeated eligibility scans or item transfers. The exact condition causing repeated rescheduling and variable duration remains unproven. Do not label it a generic engine bug, attribute the whole phase to disk serialization, or present JFR allocation weights as exact retained bytes.

## Cold preparation: synchronous loading and durable flush

Largest lean cold getChunk spans are418.8–530.8ms within423.8–534.6ms ticks; server-thread CPU is only64.7–73.3ms on those ticks. Workers show generation/heightmap/biome and lighting work, but samples do not provide a complete exclusive generation/lighting/I/O breakdown.

Two final diagnostic cold examples identify the save-before-READY barrier:

| Case | Tick ms | Inclusive chunk-save ms | Flush wait ms | GC overlap ms |
|---|---:|---:|---:|---:|
| Xeon baseline, tick464 | 871.4 | 838.9 | 815.6 | 0 |
| EPYC candidate, tick465 | 1521.4 | 1476.0 | 1386.8 | 45.5 |

Recorded server wait chain:

```
Unsafe.park / LockSupport.park
 -> CompletableFuture.waitingGet / join
 -> ChunkStorage.flushWorker
 -> ChunkMap.saveAllChunks
 -> ServerChunkCache.save
 -> PocketService.prepare
 -> PocketService.tick
```

This is a synchronous save/flush completion barrier. GC cannot explain the first and overlaps only part of the second. Wait/GC spans overlap and are not additive. Worker backlog, serialization, writes and scheduling can contribute; physical disk latency is not isolated. These examples occur outside explicit diagnostic GC holds, but JFR/NMT overhead still exists. They are not lean speedup comparisons.

Old732–931ms events cannot be retrospectively decomposed without old recordings. This pass reproduces comparable stalls and a larger diagnostic one. Whole-case peaks can also include initializing17fixtures simultaneously, not ordinary player preparation. **Do not remove the durability barrier merely to improve timing.** READY must remain unpublished until required durable completion.

## Safety, environment and limits

All8cases preserve original inventory/identity/deadline assertions, max8pockets/16leases, peak146requests/8481cache,58824shellwrites, no warm regeneration, and1696candidate rank transitions. Final cache, requests, leases and unfinished returns are zero. Stop checks an already-empty engine ticket set; this is not a newly added active-session shutdown case.

Lean host1: Xeon6975P-C,4guestCPUs,15.33GiB,KVM/AWS,Ubuntu26 image20261111.82.6/kernel7.0.0-1016-aws. Host2: EPYC9V45,4guestCPUs,15.61GiB,Microsoft virtualization,image20261111.196.1/kernel7.0.0-1019-azure. Compare pairs within hosts, not CPUs against each other.

Temurin21.0.13+9,MC1.21.1,NeoForge21.1.249;Xms256/Xmx3072MiB,G1,ergonomic processors2,view3/simulation2,flatseed860612,spawnchunks0. ProcessorCount is not CPU pinning/quota. Optional cpu.max is unavailable and recorded. OS caches/libraries reused, no forced OS cache clearing; no real clients/full pack in this profiling run.

No predefined limit fired:540s/case,40min/job,5GiBRSS,<2GiBhostavailable,92%heap,15000cache,35s/jcmd,60s/diagnostic hold. Lowest sampled available host memory12921MiB; maximum sampled RSS2092MiB,commit1878MiB. These are sampled peaks, not continuous maxima. JFR capped96MiB. Runtime200MiB evidence guard ends with the stress process; later expanded textual JFR JSON is hundreds of MiB. Kit retains compact raw recordings and streaming analysis instead.

Uncertainties: native allocator details beyond NMT, no dominator paths, observer effects, two lean pairs, short duration, no current full-pack renderer/grave/mover configuration. Selected tests do not solve non-atomic Minecraft player/region saves.

## Retained setup failures

43855084917: Gradle init script scope error. 43855608405: missing observer-class closing brace. 43855817159: optional cgroup metadata absence. 43856199156: candidate-first workload completed then procfs sampling raced its process exit; baseline-first completed4cases, overall workflow still red. Final repair accepts disappearance only after confirmed exit and still requires exit0/PASSED/exact cleanup. Preliminary data is not pooled into primary comparisons. No gameplay assertion, permission, item retry, save barrier or candidate decision changed.

## Decision and next meaningful work

**No production promotion or replacement JAR.** Warmed benefit and a smaller sampled live set remain encouraging, but cold preparation and unload rescheduling are the actionable costs. RSS reversal is partly explained by commitment/reclaimable allocation, not evidence of retained pocket contents.

Next bounded target: nonblocking preparation/readiness with explicit durable completion before READY, plus a focused test of the unload-rescheduling condition. Preserve strong visitors/home/recovery, fresh authorization, bounded admission/cancellation and exact cleanup. A changed candidate requires exact-final-commit real-client regressions. More repetitions of the unchanged broad suite or changing Anya's Java arguments would not resolve this diagnosis.

Beta.6 formats/identities/art/UI/default-off gates/damage suppression/non-live preview remain unchanged. Live portals and arbitrary editable machinery/containers remain unimplemented. Full-pack/grave/unsupported-mover and arbitrary-power-loss guarantees remain unavailable. No human test is required for independent next work.

## Artifacts and primary references

Baseline-first artifact14223078723 SHA256 `00a5d3a9bdc91b8cbf132a9ca82d7d51a0763f9c38d7891228c1ed30fb4e03d9`.
Candidate-first artifact14223163578 SHA256 `23ef4183e01ce52d3f384f10b6da3eb2335fa1bfc4e200cb0c99d2a87f862a90`.

Kit: exact source, rawCSV/GC/NMT/histograms/JFR, derived comparisons, read-only scripts and setup failure logs. No JARs/natives/class files/worlds; no redundant huge JFR JSON. JFR metadata checked for credential-like fields. Source preservation and marker verification retained.

Workflow: https://github.com/WeepingAngeIs/zSortange/actions/runs/43857495564
Measured source: https://github.com/WeepingAngeIs/zSortange/commit/7a3f6142f6677783978b319e1954d0d8de307b8e
Prior exact-candidate client safety: https://github.com/WeepingAngeIs/zSortange/actions/runs/43847949879
JDK command semantics: https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html
NMT limitations: https://docs.oracle.com/en/java/javase/21/vm/native-memory-tracking.html
