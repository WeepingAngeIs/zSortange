# Beta.6: minimal stale-unload ownership check and drainage diagnosis

Verified November 16, 2026. **All eight jobs in the final targeted workflow passed on one executable source commit. Keep this experimental. No replacement playable JAR is required.**

No installed profile, Java arguments, settings, player save, user PC or live server was accessed or changed. All Minecraft runs used disposable GitHub infrastructure. The playable beta.6 binary is unchanged.

## Exact checkpoint

Starting report checkpoint: `b02dc53cac18245c25e11bf1b24f48c3bbb94399`.

**Measured source:** `5952a6c6d1b31d7be1600ab936217f05b87939f0`, branch `feature/elemental-pockets`.

**Final workflow:** `43947328076`, Minimal stale ownership and targeted drainage.

| Required job | Job ID | Result |
|---|---:|---|
| Reversed pair, baseline first, plus separate drainage diagnostic | 133580336845 | Passed |
| Reversed pair, candidate first, plus separate drainage diagnostic | 133580336788 | Passed |
| Four dirty unload/reactivation cycles per policy and independent restart | 133580336904 | Passed |
| Preparation write/flush faults, delay, cancellation and revocation | 133580336730 | Passed |
| Selected preparation cuts and clean stop, each with separate restart | 133580336593 | Passed |
| Two actual clients: room construction and restart | 133580336790 | Passed |
| Two actual clients: unloaded-home/unsafe-fallback recovery | 133580336762 | Passed |
| Two actual clients: transitions, active shutdown and interrupted recovery | 133580336304 | Passed |

SHA-256:
- Unchanged playable beta.6: `5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11`.
- New separate minimal experiment: `1ad75739331cc71288ff7b3e021f86e1a8c762868d9b862cfa169911c64004d2`.
- Existing remote-ticket experiment, only in actual-client regression jobs: `6aa9f312cc2b526a86b2122510d50021845a9b12aa8039298af2012748d30bcd`.
- Exact measured source ZIP: `9eb3b294ee83a0bce51ea010ad725354c68568b4f29654dce5d00ab364a0d66f`.

All 74 production files, 12 remote-selector files, 10 acceptance Java files and 15 prior preparation/queue module files match the previous source snapshot. The prior experiment remains available; this candidate is a separate, opt-in module. A report-only follow-up does not change the tested executable.

## 1. The candidate is only an ownership guard, not a new unload scheduler

The runtime change is a pocket-dimension-only, server-thread check at the beginning of the pinned `ChunkMap.lambda$scheduleUnload$12` callback:

```text
If pendingUnloads.get(chunkKey) is not this exact holder:
    return from this obsolete callback.
Otherwise:
    execute Minecraft's original callback unchanged.
```

No generation-reference count, save future, ticket strength, pending-map entry or active-map entry is changed. There is no new queue, timer, save budget, asynchronous world access or retry mechanism. The still-owned path retains all original readiness, save and unload checks. This does not force a save while generation is using the holder.

The built experiment explicitly excludes the previous `durable/unload` package and `UnloadAuditMixin`; the archive verification fails if they are present. Therefore the old defensive queue and generation-reference tracing cannot account for this candidate's results. Its close audit reads, but does not empty, the actual pending-unload map and engine callback queue.

The predicate is intentionally independent of reference/future state. A callback that no longer owns the pending operation cannot legitimately save/unload that holder. If the holder is active again, future ordinary unloading remains Minecraft's responsibility. Still-owned callbacks are not cancelled.

All existing fresh permission/occupancy decisions, independent lease deadlines, strong visitor/home/recovery requirements and save-before-READY behavior remain. The preceding nonblocking-preparation module is reused unchanged: owner-thread snapshots, every chunk/changed-POI write receipt, both flush completions and final authorization/identity validation are still required before READY.

### Reduced measurement overhead

Lean runs remove the old per-callback/reference observer and readiness scans. They retain scalar per-tick event/CPU/allocation/heap/cache measurements, existing preparation/ticket assertions and a single candidate-only retired-callback counter. There is no JFR recording or active diagnostic method timer in lean cases. Some disabled instrumentation hooks and the existing preparation observer remain; this is lower overhead, not zero overhead.

A separate candidate case on each runner enables method spans and a bounded JFR recording only for drainage. These cases are not pooled with the lean timing comparison.

## 2. Repeated dirty inventory tests passed without the defensive queue

Two fresh worlds, one per policy, each complete **four dirty unload/reopen cycles**, alternating a held owned save and actual holder reactivation. Eight server-player fixtures with embedded packet sinks drive the load. **These are not real network-connected Minecraft clients.**

After room preparation has durably completed, the first chest of each of eight rooms receives 13 named diamonds: **104 total**. Other checked slots/chests are empty. Before every subsequent cycle, the test verifies the preceding 104 items and their names/components, then renames the same-count stacks for that cycle and checks the chunk dirty flag. It does not call `saveEverything` after inserting the samples; ordinary engine saving is not disabled.

A selected genuine pending holder receives one explicit, balanced synthetic generation reference:
- Cycles 0 and 2 retain that dependency for 20 ticks. The holder remains pending and the unchanged original callback continues to retry until release. This deliberately verifies the candidate does not cancel an owned save.
- Cycles 1 and 3 add a bounded test ticket, verify that the same holder becomes active again, then release the ticket/reference. The stale candidate callback is retired, and later ordinary unloading saves the contents.

Every cycle waits for **requests=0, cache=0, pendingUnloads=0 and engine callback queue=0**, then awaits the engine's successful asynchronous region flush. Authorized remote reopening verifies the previous items and original room/chest identities before starting the next cycle.

After the fourth completed cycle, `Runtime.halt(77)` cuts the process before clean shutdown can save the samples. A separately launched server recovers exactly **104 correctly named items, eight original room/cell/owner identities and 32 physical chest identities** for each policy. Inspection intentionally loads the destination, then drains again with exact engine-ticket/queue assertions. Named player inventory is checked during the seed cycles; it is not a claim that the restart fixture reconstructs all eight network clients.

The count is 104 conserved items per policy, not 416 newly generated items. The synthetic reference is test input, not a claim of natural generation behavior. In the held-owner cases the original busy-retry behavior remains visible, including millions of callback invocations before the bounded release. The minimal guard fixes obsolete callbacks, not a truly still-owned dependency that never completes.

The forced cut occurs after an explicit successful flush. It is not a guarantee for arbitrary power failure between player, ledger and region writes. General unload-write failure behavior is unchanged; preparation write failures are separately covered below.

## 3. Lean paired measurements

Four fresh-world/JVM cases on two runners reverse baseline/candidate order. Each prepares eight pockets for eight fixtures, visits, exits, remotely views for **400 transition ticks** and drains for **650 ticks**. There are **zero real Minecraft clients** in these measurement cases.

Both sides use the same prior nonblocking-preparation prototype and original strong ticket policy. The remote-ticket experiment is absent. Only the stale-ownership check differs. This is not a direct benchmark of every combined experiment against plain installed beta.6, nor a side-by-side comparison with the prior defensive queue.

All 400 transition ticks are included; p95 uses linear interpolation.

| Within-runner comparison | Original unload | Minimal guard |
|---|---:|---:|
| Xeon 6975P-C, baseline first: transition median / p95 | 49.847 / 50.306 ms | 2.374 / 6.752 ms |
| Mean server-thread CPU per transition tick | 48.229 ms | 3.229 ms |
| Cumulative transition thread allocation | 35.852 GiB | 0.520 GiB |
| Maximum drainage event tick | 234.088 ms | 422.024 ms |
| Whole-case sampled RSS peak | 1,252.4 MiB | 1,494.8 MiB |
| EPYC 9V45, candidate first: transition median / p95 | 49.843 / 50.507 ms | 2.372 / 6.586 ms |
| Mean server-thread CPU per transition tick | 47.873 ms | 3.170 ms |
| Cumulative transition thread allocation | 44.626 GiB | 0.552 GiB |
| Maximum drainage event tick | 310.587 ms | 387.634 ms |
| Whole-case sampled RSS peak | 1,527.4 MiB | 1,349.0 MiB |

The ownership-only guard preserves the large transition benefit with reduced observation overhead. Allocations are bytes created/reclaimed over time, not simultaneous live memory or RSS. Two paired cases and correlated ticks do not establish a general FPS/TPS figure.

The candidate retires 1,726 and 1,653 obsolete callbacks in the two lean runs. There is no baseline callback tracing in these lean measurements, so the old hundreds-of-millions counts are not claimed as newly measured here. Both policies peak at **8,481 raw pocket cache entries and 140 logical requests**. Final destination cache, requests, pending unloads and callback queues are zero.

**Residual result:** the largest lean drainage spike is worse for the candidate in both pairs, even though the repeated transition tail collapses. RSS reverses direction between runners again. Neither elimination of all pauses nor reduced peak RAM is established.

## 4. The remaining drainage spike is a bulk serialization burst

Separate candidate diagnostic cases reproduced two large drainage events. These runs add JFR and nested method timing and are not clean latency comparisons with the lean cases.

| Same-tick diagnostic observation | Xeon | EPYC |
|---|---:|---:|
| Tick number | 826 | 824 |
| Event-tick elapsed, CSV bracket | 908.842 ms | 939.806 ms |
| Server-thread CPU | 863.350 ms | 881.441 ms |
| `processUnloads`, inclusive | 892.643 ms | 926.223 ms |
| `ChunkMap.save`, inclusive | 817.342 ms | 852.483 ms |
| `ChunkSerializer.write`, inclusive | 805.522 ms | 845.040 ms |
| Save method calls | 2,305 | 2,433 |
| Actual serialization calls | **1,877** | **2,004** |
| Overlapping GC pause time | 24.129 ms | 23.988 ms |
| `saveAllChunks` span | 0 | 0 |
| Distance-manager update span | 0.007 ms | 0.010 ms |
| Pending unloads / engine callbacks at tick end | 2,000 / 2,000 | 2,000 / 2,000 |

These are nested elapsed spans and must not be added. Serialization occupies about 89% of the event in these recordings. Server-thread CPU is close to elapsed time; GC accounts for only a small portion.

JFR samples on the server thread include `PalettedContainer.pack`, `swapPalette`, codec encoding and, in the Xeon samples, `ZeroBitStorage.unpack`/array filling, through `ChunkSerializer.write -> ChunkMap.save -> scheduleUnload callback -> processUnloads`. There are **13 server execution samples in the Xeon peak and only one in the EPYC peak**, so the samples do not establish a precise per-instruction allocation of time. The direct method timers provide the stronger localization.

There were no recorded server-thread parks, monitor waits or file reads/writes over the configured 10-ms thresholds in these intervals. That does not exclude shorter waits, worker I/O, lighting or scheduling elsewhere. The evidence supports **a large server-thread serialization batch**, not a claim that physical disk activity was absent. The separate global save-all/force-flush barrier is not the observed cause of these two drain ticks.

### Why so much work runs in one tick

The pinned engine's `processUnloads` loop has a queue-size catch-up path: callbacks above a 2,000-entry threshold can continue to be drained even when the ordinary time supplier no longer allows more work. Both recorded peaks end with exactly 2,000 pending callbacks. The call count, save stacks and source condition are consistent with that catch-up batch causing the observed serialization burst.

This is distinct from the stale immediate-rescheduling loop. Here the callbacks perform actual saves. The guard correctly does not discard them or offload world serialization to an unsafe worker thread.

**No unload/save budget was patched in this phase.** The recordings identify an actionable target for a separate save-preserving budget experiment. They do not retrospectively decompose the earlier report's 615–723-ms spikes exactly, and the 909–940-ms diagnostic events are not substituted for the lower-overhead timing values.

## 5. Exact-candidate durability and actual-client regressions

All required jobs used the same final source and minimal module hash.

### Fixtures with real engine storage workers, not real clients

Preparation tests retain the unchanged completion fence and pass delayed publication, actual injected chunk/POI write exceptions, flush failure, logout-hook cancellation, permission revocation and reanchor epoch checks. The delayed case holds publication after real I/O, not a physically hung disk. Failures do not become READY or allow automatic regeneration of uncertain BUILDING data.

Clean stop with publication held and selected cuts before flush, after flush/before READY and after READY/before entry are each followed by independent server launches. The checked room/cell/owner identities, physical chests, player sample and saved exit-POI evidence remain conserved. These selected boundaries do not provide atomicity across Minecraft files or prove interruptibility of hung physical I/O.

### Two genuine Minecraft client JVMs over localhost TCP

The unchanged client Java fixtures use generated launchers loading the packaged beta.6 plus the exact minimal module. Private native-extraction directories and supervision remain. Fixture code seeds positions/materials and directs actions; this is automated client coverage, not human/WAN/full-pack testing.

- **Room:** delegated Build and storage rights, protected boundaries, added physical chest, 270-slot combined inventory, a single conserved deposit, socket kill/reconnect, safe return/reanchor and separate-server restart pass.
- **Cold recovery:** actual absent home/fallback chunks, both-unsafe queued retries, unresolved shutdown/restart, repaired landing and automatic safe return pass. The controlled login-spawn staging needed to keep fallback truly cold remains explicit. Seed shutdown removes 17 live requests to zero actual engine tickets.
- **Transitions/shutdown:** remote/visit transitions, overlapping/shared viewers, independent lease deadlines, existing same-hub policy-revision invalidation, revoked stale intents and actual client-process SIGKILL/reconnect pass. A stop with an active visitor and remote viewer audits **34 requests -> zero actual engine tickets**. A forced return cut after teleport but before saved-player confirmation retains its durable return record, and a fresh process/client reconnect recovers safely. Ten named diamonds stored plus one held, 17 named emeralds and original room/cell/owner identities survive. Final exact engine tickets, pending callbacks, cache and return records are empty.

Client jobs also load the unchanged efficient remote-ticket experiment, unlike the lean measurement lane. They run its candidate policy; this is not another counterbalanced ticket-performance comparison. OPAC 0.33.0 and Carry On 2.2.6.13 are present in the isolated client runtime, not certification of Anya's current pack or all mover/grave combinations.

The transition run records zero naturally retired callbacks in its final close summaries, so its pass demonstrates lifecycle compatibility and cleanup with the hook loaded, not that it recreated the high-count natural loop. The cold-recovery seed records 29 retired callbacks; the fixture measurement/dirty lanes supply the direct stale-path exercise.

## 6. Environment and bounded methodology

Primary runners: Intel Xeon 6975P-C and AMD EPYC 9V45, each four guest logical CPUs. Visible memory is about 15.33 and 15.61 GiB respectively. Microsoft virtualization, Linux 7.0.0-1019-azure, Java Temurin 21.0.13+9, Minecraft 1.21.1, NeoForge 21.1.249. Each paired comparison stays within its own runner; order is reversed across the two runners.

Server Xms256m/Xmx2048m, G1, ergonomic processor count 2 (not operating-system CPU affinity), view distance 3, simulation distance 2, flat seed860612, no spawn tickets. Fresh world/JVM per measurement case; libraries and OS cache are reused, with no cache clearing or forced GC. No user configuration was changed.

Primary measurement cases last about 82–84 seconds. Runtime ceilings remain 180 seconds per ordinary process, 300 for dirty seed/verify, 4 GiB sampled server RSS, at least 2 GiB available host memory, 92% used Java heap and 15,000 destination-cache entries. Dirty fixtures have a 3,800-tick ceiling. No final resource guard fired. Diagnostic recordings are separately bounded at 96 MiB/90 seconds; their sampled RSS and durations are not pooled into lean comparisons.

Per-tick CSV timing excludes final CSV output and lowest-priority fixture post-tick work, but retains executed hooks and preparation bookkeeping. CPU/allocation counters are current server-thread values; process CPU includes other JVM work, not clients. RSS/host availability is sampled once per second and is not a continuous maximum or an ownership census. No long soak, maximum real-client count, alternate world generator or full modpack is claimed.

## 7. Earlier attempts retained, not counted as passing coverage

- `43946306976` at `5f4bbb74d4fdf6b5212979f8103f5395ac475ef2`: diagnostic `Event` ambiguity between JFR and NeoForge prevented compilation. Explicitly naming `jdk.jfr.Event` repaired it.
- `43946868496` at `6d5ca4413c6a63ad0523f88447420a4ecd9132bf`: the new broad mixin package disallowed ordinary mod classes during startup. Separate narrow mixin packages and matching metadata repaired the experimental packaging. No game-world acceptance had run.

Only final `5952a6c6...` workflow `43947328076` supplies the primary measurements and required runtime regressions. No permission/save assertion was relaxed and no mutating inventory intent was blindly retried. Unrelated automatically triggered workflows are not substituted for this targeted result.

## Decision and remaining work

**Keep installed beta.6 unchanged; no replacement playable artifact is warranted in this phase.** The minimal guard is now independently useful without the defensive queue, with repeated dirty-save and exact-candidate recovery evidence. Production integration still needs a packaging/compatibility decision for the pinned private engine callback and broader interaction coverage. It is not a public cross-version engine API.

The remaining drainage issue is no longer an unexplained pause in these recordings: it is a large batch of real server-thread chunk serialization. The next bounded experiment should meter that work without dropping owned callbacks, dirty data, flush completion or shutdown obligations, and should test bounded backpressure plus unload/reactivation and restart. Do not weaken durability to flatten a chart.

A genuinely still-owned generation dependency can still busy-retry; this guard deliberately leaves it alone. Never-released real dependencies, physical I/O hangs, long soaks, arbitrary machinery/containers, unsupported movers and grave interactions remain unverified. Selected flush cuts do not solve non-atomic player/region/ledger persistence.

Saved formats, identities, inventory rules, artwork/UI, default-off gates, damage suppression and the non-live preview are unchanged. Live see-through portals remain unimplemented and no backend was installed. No human test is required for the independent next step.

Primary workflow: https://github.com/WeepingAngeIs/zSortange/actions/runs/43947328076
Measured source: https://github.com/WeepingAngeIs/zSortange/commit/5952a6c6d1b31d7be1600ab936217f05b87939f0
Pinned engine audit used for unload semantics: https://github.com/WeepingAngeIs/zSortange/actions/runs/43918683165

The evidence kit retains raw logs, CSVs, diagnostic JFR/JSON, screenshots, exact source, archive provenance and read-only verification/analysis scripts. It contains no executable JARs, native libraries, player worlds or decompiled engine source.
