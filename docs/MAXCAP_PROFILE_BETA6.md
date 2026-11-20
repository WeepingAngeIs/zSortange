# Beta.6 maximum-cap cold preparation and resource profile

Measured November 16, 2026. **Evidence and test-fixture changes only. No replacement playable JAR is required.** No user profile, Java arguments/settings, player world or live server was accessed or changed.

## Provenance

Starting checkpoint: `2653d6df2a886f8a677a2f8e88b4a04584380aac`.
Exact measured source: `d8e879043ff655d312e1a292612ac2711b130e72`, branch `feature/elemental-pockets`.
Successful new workflow: **43821102122**, job **133144240573**, completed **2026-11-16 07:21:05 UTC**.
Artifact **14205690915**, ZIP SHA-256 `e2761ee348119317bd2e996416d015ecda20999cab4660492eef4775dde41fe1`.
Unchanged beta.6 JAR SHA-256: `5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11`.
Separate profiling-fixture SHA-256: `2e44ceba475dd45172b8acc1d70a813361619cc8d4c47e8c26095df559726f99`.

Both cases verified the loaded production JAR hash. All 74 retained src/main entries match the starting source. No production code, format, identity, inventory, artwork/UI, gate or damage rule changed. This report-only follow-up does not alter the measured source. Automatically triggered unchanged regression/multi-pocket suites are not new coverage. The old 45-checkpoint scenario is not the basis of this report.

## Actual workload and hardware

Both baseline and full-only-test wrote PASSED markers and reached **eight active pockets, 16 leases, 16 pending identities and one preparation job**. No resource guard fired. They recorded **9,533 and 9,529 ticks**, spanning **476.601 and 476.402 measured seconds** before shutdown.

The engine runs used **17 ordinary ServerPlayer fixture identities, zero real Minecraft client JVMs and zero real network sessions**. Embedded connections discard outgoing traffic. Paired visitors are co-located at the protected landing. This is not 16-client, spatial worst-case, network or graphical acceptance. Only Anya and the profiler were loaded, not OPAC/Carry On/full-pack dependencies.

Final paired runner: **Intel Xeon 6975P-C, four guest logical CPUs, KVM x86-64, 15.33 GiB visible RAM**, Ubuntu image **20261111.82.6** (`ubuntu26`), kernel `7.0.0-1016-aws`; **Temurin 21.0.13+9-LTS**, Minecraft **1.21.1**, NeoForge **21.1.249**. Both cases used **256/3,072 MiB initial/maximum heap**, G1GC, Java ergonomic processor count **2**, server view distance **3**, simulation distance **2**, no natural spawning or retained spawn chunks. The Java option is not OS CPU pinning or a hard quota. Probed cgroup-v2 quota files were unavailable.

Each case used a fresh JVM/world, with empty/home baselines, a 1 -> 2 -> 4 -> 8 pocket ramp, 600-tick steady states, mixed visit/remote use, remote-only use, a ninth-owner admission after capacity release, and two 16-viewer warm-reopen cycles. Finally, **eight new never-prepared cells** received 16 simultaneous requests. This later burst is cold room/region data in a warm JVM, not a cold OS cache.

Each of four drains waited 650 ticks and checked every destination ring and the entire pocket cache at zero. No guard fired: protection covered >15,000 cache entries, sustained >85% heap use, >5 GiB RSS, <1.5 GiB host-available memory, timeouts and bounded shutdown escalation. Observed host-available memory stayed above 12.95 GiB.

## Different counters, not interchangeable costs

Per-tick telemetry records Pre-to-Post event wall time, server-thread CPU, JVM process CPU, PocketService.tick wall time, heap/GC, requests/cache and shell writes. A required test-only mixin counts actual pocket-dimension ServerLevel.tickChunk invocations. Every 20 ticks, ChunkHolder futures identify completed full/block-ticking/entity-ticking readiness. Readiness is not execution. RSS and host memory are sampled each second.

| Steady state | Active / leases | Anya requests | Raw cache | Full-ready | Block-ticking-ready | Entity-ticking-ready | Actual tickChunk calls/tick |
|---|---:|---:|---:|---:|---:|---:|---:|
| Baseline: eight visited rooms | 8 / 16 | 136 | 8,481 | 968 | 648 | 392 | 392 |
| Baseline: eight remote-only rooms | 8 / 8 | 136 | 7,200 | 512 | 288 | 128 | 0 |
| Candidate: eight visited rooms | 8 / 16 | 136 | 8,481 | 968 | 648 | 392 | 392 |
| Candidate: eight remote-only rooms | 8 / 8 | 136 | 5,408 | 128 | 0 | 0 | 0 |
| Final idle: both cases | 0 / 0 | 0 | 0 | 0 | 0 | 0 | 0 |

Both entire runs peaked at **8,514 raw cache entries and 141 logical Anya requests**, including temporary recovery requests. Requests include home positions and are not unique chunks or all engine tickets. Thousands of cached holders were not thousands of ready full chunks executing tickChunk. Cache ownership still costs memory/loading work; zero tickChunk calls is not zero total server activity.

## Timings, CPU and memory

Steady timing rows use the final **400 ticks** of each 600-tick state. Event timing excludes sleep and the profiler's final sampling/control work. Process CPU includes asynchronous/GC work between samples; CPU seconds divided by elapsed seconds is core equivalents. Full-phase summaries and raw data are retained.

| Baseline state | Event tick median / p95, ms | Mean server-thread CPU ms/tick | JVM process core equivalents |
|---|---:|---:|---:|
| 17 identities at home | 0.335 / 0.883 | 0.553 | 0.162 |
| 1 visited room | 0.396 / 0.578 | 0.419 | 0.073 |
| 2 visited rooms | 0.467 / 0.608 | 0.487 | 0.054 |
| 4 visited rooms | 0.841 / 1.033 | 0.859 | 0.060 |
| 8 visited rooms | 1.553 / 1.783 | 1.579 | 0.075 |
| 8 mixed visit/remote rooms | 1.905 / 2.441 | 1.956 | 0.110 |
| 8 remote-only rooms | 1.149 / 1.321 | 1.160 | 0.072 |
| Warm 16-viewer cycle 2 | 1.162 / 1.548 | 1.206 | 0.064 |
| Final idle | 0.167 / 0.238 | 0.175 | 0.044 |

Cold/drainage spikes remain important. The baseline eight-new-room burst peaked at **253.503 ms/event tick**, including **248.489 ms** inside PocketService.tick, with zero shell writes that tick. The synchronous chunk-request path is a plausible cause, not a proven instruction-level attribution. The longest baseline event tick was **627.456 ms during drainage**, with only **7.318 ms** inside PocketService.tick; the full post-to-post interval reached **676.077 ms**. Most of that measured event cost was outside the service method and needs further engine-level attribution.

| Whole measured case | Baseline | Test-only candidate |
|---|---:|---:|
| Process CPU seconds | 59.77 | 57.45 |
| Sampled heap-used peak, MiB | 1,024.86 | 977.05 |
| Sampled RSS peak, MiB | 1,562.23 | 1,426.02 |
| Maximum event tick, ms | 627.456 | 250.978 |
| Maximum post-to-post interval, ms | 676.077 | 579.030 |
| GC count / milliseconds at end | 74 / 1,282 | 69 / 1,229 |

GC counters include startup. No forced collection or OS cache clearing occurred. Peaks are sampled, not continuous maxima. After final cache/ticket cleanup, baseline still showed about **1,012 MiB used heap and 1,521 MiB sampled RSS**; candidate about **611 MiB and 1,394 MiB**. Heap used includes uncollected garbage: these figures neither establish nor exclude an object-retention leak. Zero chunk cache does not mean zero JVM memory.

## Admission, preparation and cleanup checks

Both cases prepared 17 different rooms, recording **111,112 shell writes**, at most **256/tick**; warm reopens did not regenerate shells. This counter excludes final chests/exit, generation, lighting, saving and other engine work. One preparation job does not bound all async engine tasks or guarantee a short tick.

In the simultaneous burst, owner/guest pairs arrived after **29, 55, 81, 107, 133, 159, 185 and 211 ticks** in both cases. Every admitted identity completed. A ninth owner was admitted after capacity release while seven remote views remained valid. Lowering the admission cap preserved existing sessions and permitted sharing an already active room while denying a new distinct room. This is finite-scenario evidence, not a fairness proof under unbounded adversarial arrivals.

Eight guarded withdraw-one/redeposit pairs conserved named samples in each case. Both warm cycles preserved named stored contents. Four cleanup checkpoints per case reached zero destination cache/rings, requests, leases and pending work. These tests do not solve Minecraft's non-atomic player/region saves.

## Candidate finding, not adoption

A separate profiling mixin changes syncTickets add/remove region-ticket arguments from 2 to 0. NeoForge derives the ticket level from FULL minus this argument. It affects destination and home/recovery calls through that path, not exclusively remote pockets. The playable JAR is unchanged.

Remote-only cached entries fell **7,200 -> 5,408 (24.89%)**, and full-ready chunks **512 -> 128 (75%)**. Candidate block/entity-ticking-ready counts there were zero. Actual tickChunk calls were already zero in baseline, so this does not remove 128 actively executed chunk ticks. Visitor-driven counts and the global **8,514** cache peak did not fall.

Warm remote cycle 1/2 medians were **1.092/1.162 ms baseline vs 0.823/0.790 ms candidate**, with p95 **1.426/1.548 vs 1.120/0.950 ms**. Candidate visited-room p95 was higher, **2.069 vs 1.783 ms**. Candidate RSS in the first remote-only phase was also higher. One sequential cloud-runner pair is not statistically established speedup or universal memory savings; UUID/scheduling, JIT, GC and OS caches differ.

No production optimization is promoted. Real-client transitions, revoke/disconnect, interrupted recovery and active-session shutdown have not been regressed under this intervention. The separate stop() removal path is not changed by this test mixin; all measured endings first drained to zero. Any eventual production policy must reconcile all add/remove paths.

One RSS row caught an empty heartbeat; it remains in whole-run peaks but cannot be phase-assigned. A zero-RSS sample after process exit is not zero live-JVM memory. Future harnesses should replace heartbeat files atomically.

## Next independent work and renderer boundary

The next narrow experiment is a **remote-only ticket policy retaining visitor/recovery behavior**, followed by actual-client transitions and active-session shutdown regression. The drainage pause also warrants engine-level attribution. No human test is required for that independent work. Higher distances, dispersed users, full-pack load, hours-long stability and arbitrary crash consistency remain outside this profile.

Live see-through portals remain unimplemented. Pinned CE source `ad2e7ee5d73e4ff78bd0befade4f797265e65939` was reopened: properties identify source **6.1.4 / MC1.21.1 / NeoForge21.1.228**, and the backend contract has a vanilla/no-shader lane. PortalAPI exposes per-player add/remove chunk loaders; removal is **by object reference**, not equality. Portal.visible=false does not stop loading/collision/teleportation. This is source inspection, not runtime/shader compatibility.

The smallest future adapter fixture is one prepared room with a view-only home-side portal, two actual clients plus an unauthorized-observer case, retained per-viewer loader instances, bounded View leases, disabled crossing/interaction, packet privacy inspection and actual loader/entity removal on expiry/revocation/disconnect. No global loader or visibility-only cleanup. CE tickets must be measured separately from Anya requests. No portal dependency/adapter was installed or bundled here; the non-live fallback remains unchanged.

## Primary references

- https://github.com/WeepingAngeIs/zSortange/actions/runs/43821102122
- https://github.com/neoforged/NeoForge/blob/1.21.1/patches/net/minecraft/server/level/DistanceManager.java.patch
- https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/event/tick/ServerTickEvent.java
- https://github.com/UpperMoon0/ImmersivePortals/blob/ad2e7ee5d73e4ff78bd0befade4f797265e65939/gradle.properties
- https://github.com/UpperMoon0/ImmersivePortals/blob/ad2e7ee5d73e4ff78bd0befade4f797265e65939/docs/renderer-backends.md
- https://github.com/UpperMoon0/ImmersivePortals/blob/ad2e7ee5d73e4ff78bd0befade4f797265e65939/src/main/java/qouteall/imm_ptl/core/api/PortalAPI.java
- https://github.com/UpperMoon0/ImmersivePortals/blob/ad2e7ee5d73e4ff78bd0befade4f797265e65939/src/main/java/qouteall/imm_ptl/core/portal/Portal.java
