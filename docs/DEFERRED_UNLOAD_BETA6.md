# Beta.6: generation-reference lifetime and deferred pocket unloading

Verified November 16, 2026. **Experimental only. The playable beta.6 JAR is unchanged and no replacement is required.** No installed profile, Java arguments, settings, player world or live server were accessed or modified. All Minecraft runs used disposable GitHub infrastructure.

## Exact provenance and CI status

Starting checkpoint: `99820d8832f6d291a77f45736ba5fb05059b0907`.

Measured Java/source commit: **`75a79dab34005a2804835a030e06b5ebffaa8a4d`**, branch `feature/elemental-pockets`.

Main targeted workflow **43921985243** passed seven of eight jobs. The transition job completed its baseline case, then its Python telemetry reader interrupted the candidate case. **This workflow remains red and is not described as all-green.**

Monitor-only repair source: **`dcbf12f96bfbc4dd25b2f6f44819da258616e7de`**. Focused workflow **43925836335**, job **133504916942**, passed the entire missing candidate lifecycle, completing at 18:19:02 UTC. Its Java modules and loaded mod binaries match the measured commit exactly. Only the monitor and its test/launch wrapper changed; gameplay assertions were not relaxed.

| Required lane | Job | Result |
|---|---:|---|
| Paired measurement, baseline first | 133491364533 | Passed |
| Paired measurement, candidate first | 133491364631 | Passed |
| Controlled owned wait and dirty unload/restart | 133491364571 | Passed |
| Preparation failure/cancellation/revocation | 133491365213 | Passed |
| Preparation process cuts/restart | 133491364653 | Passed |
| Two real clients: room construction/restart | 133491364681 | Passed |
| Two real clients: cold/unsafe recovery | 133491364663 | Passed |
| Original transition pair | 133491364175 | Baseline case passed; candidate interrupted by telemetry reader |
| Focused repaired candidate transition lifecycle | 133504916942 | Passed, bit-identical mod binaries |

SHA-256:
- Playable beta.6: `5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11`.
- Separate preparation/unload experiment: `4a6822977c2136f1499adaef2f5d36d0f2dcba96b63318ebbc98f86a4f8de2e9`.
- Existing efficient remote selector: `6aa9f312cc2b526a86b2122510d50021845a9b12aa8039298af2012748d30bcd`.
- Client fixture: `ce780484ceada6ebe30c52fe56106d611bfe69669d29eeba9fc1aba5566bbe13`.
- Measured source ZIP: `cfad918fce3210e935d6b92d8d97c2b1d377c51d3514d7de94912a121e1263a9`.
- Latest harness source ZIP: `0c77c201634ef1b02b40312642f54374105b0f0249a3c7911313a5a846323cf4`.

All 74 production files, 12 existing ticket-experiment files and 10 acceptance Java files match the preceding durable-preparation source. The new candidate is outside the playable JAR. All 15 durable-probe Java/resource entries also match between measured and repaired sources. This documentation-only follow-up is not a new executable.

## Specific observed cause

Pinned audit workflow **43918683165**, at `286067221007fc9b79c37ed556454beff34999aa`, inspects Minecraft 1.21.1/NeoForge 21.1.249. Resolved development binary SHA-256: `e73b1d34d83fe339e51884bd0620f031a2ac0e1850fb75f7d3bea2efc7ad0aaf`.

Generation tasks claim a neighborhood through `ChunkGenerationTask.create`, `StaticCache2D` and `ChunkMap.acquireGeneration`. Task completion/cancellation releases the claimed references. A positive reference is not automatically a leak, and forcing it to zero is unsafe.

The engine first moves a zero-reference holder from its active map to `pendingUnloads` and schedules an unload callback. A subsequent ticket-level change can remove the same holder from pending unload and reactivate it. New generation work then acquires references, but the old callback still exists.

The pinned callback checks saving readiness before checking exact pending-unload ownership. Its old save future can already be complete while the reactivated holder has new positive references. Attaching another callback to that completed future immediately requeues work. The obsolete callback no longer owns a pending save, but consumes the unload processing budget repeatedly.

In all four final natural cases, **every counted positive-reference/completed-future callback was stale, not the current pending-unload owner**. All 64 detailed samples per case also had exact active-map ownership. Acquisition/release totals balanced at cleanup, about 1.1 million each per case. This does not demonstrate a permanent reference leak in this workload.

Example: holder3035, chunk[317,252], became pending at tick439, was reactivated and reacquired at442, and at443 the old callback had eight refs with `pending_owns=false` and `updating_owns=true`. Worker-Main-1 released refs to zero at733, with269 acquisitions and269 releases. It became genuinely pending again at844. Acquisition stacks include `prepareAccessibleChunk -> getChunkRangeFuture -> scheduleChunkGenerationTask -> ChunkGenerationTask.create`. This traces the lifetime without assigning all worker delays to a particular generation layer.

## Narrow retry policy

Only the pocket dimension is affected. The candidate operates on the server thread and only intercepts the observed predicate: positive references and a normally completed save-sync future.

- If the pending map does not own the exact holder, retire only that obsolete callback. Do not remove a pending-map entry, save/unload the active holder, alter tickets or change reference counts.
- If the holder still owns its pending entry, retain one deferred identity. Fair rotation checks at most256 entries per `processUnloads` invocation. When the blocking predicate changes, rearm the original engine path with its original readiness, ownership and save/unload checks.
- If an identity is replaced/reactivated, retire only obsolete deferred work. Incomplete/exceptional futures retain original behavior. Rearm exceptions restore owned queue state.

The capacity is8192 entries. It never evicts a save; overflow retains original handling and fails the fixture resource guard. Close retires stale entries only and asserts that no owned deferred work remains. It does not clear unfinished work to manufacture a clean shutdown.

The natural performance benefit is the **stale-ownership check**, not a naturally observed owned-save delay: natural owned-wait and queue-peak counters were zero. The defensive owned path is separately tested with an explicitly synthetic dependency. Strong visitor/home/recovery tickets, fresh permissions, independent deadlines, preparation completion and save-before-READY remain unchanged.

The pure owner-thread queue passed2016 assertions for capacity, rotation, identity replacement, incomplete futures, rearm failure and off-thread rejection. These are not2016 Minecraft sessions.

## Dirty inventory survival through unloading

Two new fixture cases insert13 named diamonds into the first chest of each of8 already READY rooms,104 total. This occurs after preparation saves; there is no `saveEverything` call after insertion. Each selected chest chunk is verified dirty.

One balanced synthetic generation reference deliberately holds a real pending owner for20 ticks. In the hold case the deferred identity remains and normal save rearms once after release. In the reactivation case a bounded test ticket makes the same holder active, so the obsolete deferred entry retires; after ticket/reference release, ordinary later unloading saves the contents. This injection is disabled in measurement/client runs and is not claimed to be the natural loop frequency.

Before cutting the process, the fixture requires zero pending unloads, zero engine unload callbacks, zero deferred entries and no requests, then awaits successful asynchronous engine region flush. `Runtime.halt(77)` happens before clean shutdown can save the samples. Fresh server processes recover exactly104 correctly named items, original8 room/cell/owner IDs, all32 physical chest identities and empty other checked slots. Restart inspection intentionally loads chunks, then drains again.

The first test attempt, workflow43921213127 at4c4a1193..., failed because raw visible/cache count reached zero while one owned deferred callback remained. Source75a79dab... corrects the cut-point observation to await all actual queues. No item/save assertion was removed and no mutating request retried. Visible/cache zero alone is insufficient to declare completed save work.

The successful cut follows explicit flush. It is not an arbitrary power-loss guarantee across player, region and ledger files. Shutdown with a genuinely never-released generation dependency remains unverified and is not forced through.

## Paired measurements

Four fresh-world/JVM cases, reversed baseline/candidate order on two runners, prepare8 pockets with8 server-player fixtures, then visit, exit, remotely view for400 ticks and drain650 ticks. **Zero real Minecraft clients are used in the measurement lane.** Both policies use the identical prior nonblocking-preparation prototype and original strong tickets. Only unload retry behavior differs; this is not a direct benchmark of all combined changes against plain installed beta.6.

All400 transition ticks are included.

| Within-host measurement | Original unload | Candidate |
|---|---:|---:|
| EPYC9V74, original first: transition median/p95 | 49.732/49.999 ms | 1.811/6.183 ms |
| Mean server-thread CPU per transition tick | 35.334 ms | 2.770 ms |
| Cumulative server-thread allocation in transition | 33.089 GiB | 0.564 GiB |
| Whole-case callback invocations | 145468165 | 12022 |
| Xeon8573C, candidate first: transition median/p95 | 49.750/50.317 ms | 2.733/7.113 ms |
| Mean server-thread CPU per transition tick | 46.536 ms | 3.689 ms |
| Cumulative server-thread allocation in transition | 23.535 GiB | 0.526 GiB |
| Whole-case callback invocations | 117257626 | 11972 |

Cumulative allocations are bytes created/reclaimed over time, not simultaneous live/RSS memory. The observer adds work to callbacks, so these are instrumented rates, not universal FPS/TPS figures. Both sides share observation, but less repeated work also means less observer work.

Both policies peak at8481 raw cache entries and140 logical requests. Final cache, engine-owned requests, deferred queue and reference imbalance are zero. No permanent loading conceals the loop.

**Residual costs:** whole-case maximum event ticks remain633/723ms on the EPYC baseline/candidate and399/615ms on the Xeon pair, all during drainage. Their internal cause was not traced here. Sampled RSS is mixed:1207.6->1358.7MiB and1336.3->1288.5MiB. No general RAM reduction or elimination of all cold pauses is established. Preparation takes383/373ticks and375/373ticks; its algorithm was held constant, so this is not a separate preparation improvement claim.

## Real-client and durability regressions

Room, cold-recovery and transition jobs each use two genuine Minecraft client JVMs over localhost TCP with generated launchers loading the packaged beta.6 and exact experiments. Server fixtures seed/direct conditions. This is not human/WAN/full-pack testing. Those client jobs also include the unchanged efficient remote selector, unlike the measurement lane.

Room checks retain delegated Build/storage, added physical chest/270-slot view, a single conserved deposit, client-process interruption/reconnect, returns/reanchor and separate restart. Cold recovery checks truly absent home/fallback chunks, both-unsafe retries, unresolved restart, repaired landing and automatic safe return; controlled login-spawn staging is explicit.

Transition coverage retains independent/shared viewers, remote/visit changes, unchanged same-hub revision invalidation, revoked stale intents, real client-process SIGKILL/reconnect, active server shutdown and interrupted return confirmation. Named counts remain10 stored+1held diamonds and17 emeralds, with original room/cell/owner identities. Final exact tickets/cache/return records are empty.

The original transition job's Python `beat[1]` access raised IndexError on an incomplete telemetry snapshot. Failed bytes were not captured, so empty versus one-field data is unknown. No gameplay FAILED marker preceded it. The baseline case had fully passed; candidate seed passed and candidate after-stop supervision was interrupted.

The dcbf12f... repair uses a pure missing/incomplete label parser, tested with14 cases. Resource/time ceilings, process supervision, strict `before=34 after=0` shutdown checks and gameplay assertions remain. It does not retry transfers. The targeted workflow checks identical module hashes before running only the missing candidate from a fresh world. All three legs passed: active stop, forced recovery cut after teleport/before saved-player confirmation, then restart/cleanup. Seed stop audits34requests->0, zero deferred work and1296737 balanced ref acquisitions/releases. Final close audits zero deferred work/overflow and180600 balanced acquisitions/releases. Two actual client SIGKILL/reconnect cycles are recorded.

Failure/cancel and preparation-cut lanes use simulated connections with real storage workers. Injected chunk/POI write and flush errors withhold READY; delayed publication, cancellation/revocation and reanchor epoch reject late publication. Before-flush, post-flush/pre-READY and post-READY/pre-entry cuts and unresolved-publication shutdown preserve previous fail-closed behavior. Held publication is not a physically hung disk.

## Environment, bounds and limitations

Primary hosts:4 guest CPUs/~15.61GiB RAM, Microsoft virtualization/Linux7.0.0-1019-azure; EPYC9V74 baseline-first, XeonPlatinum8573C candidate-first. JavaTemurin21.0.13+9, MC1.21.1, NeoForge21.1.249, G1/Xms256m/Xmx2048m, ergonomic processors2 (not OS CPU pinning), view3/simulation2, flatseed860612, no spawn tickets. OPAC/CarryOn are absent in load fixtures; client jobs retain0.33.0/2.2.6.13 respectively, not broader compatibility certification.

Primary processes lasted85-87s. Bounds:180s/process,2100regular ticks,1900synthetic-persistence ticks,4GiB sampled RSS,minimum2GiB host available,92%heap,15000cache,8192retry capacity. No final paired cutoff fired; host available stayed above13685MiB. Client-specific group/time guards also remain unchanged.

Per-tick event/thread/process CPU, allocated bytes and heap; RSS1Hz. Timing excludes final CSV and lowest-priority post-tick fixture actions but includes executed observer hooks. Reference tracing has weak holder keys/scalar values,20000holders,64detailedchunk examples,16reacquisition stacks. No forced GC/OS cache clearing. Two pairs and correlated ticks are not a long soak or more-than-two-real-client load test.

## Decision

**Keep installed beta.6 unchanged. No replacement playable artifact.** The repeated stall is explained and experimentally corrected with persistence/recovery evidence. The private synthetic-method hook remains pinned and full-pack interaction is unverified.

Next useful bounded step: isolate the minimal stale-ownership check from the defensive queue and confirm repeated dirty unload/reactivation with reduced instrumentation. Other unfinished work includes never-released real dependencies at shutdown, long soaks, arbitrary movers/graves and the separate615-723ms drainage spikes. Do not force refcounts, drop owned callbacks or remove durability barriers to make these green.

Saved formats, identities, inventories, art/UI, default-off gates, damage suppression and non-live preview are unchanged. Live see-through portals and unrestricted room machinery/containers remain unimplemented. No portal backend installed and no human test required for independent work.

## Retained artifact provenance

The evidence kit includes raw logs/measurements/screenshots, original failures, source snapshots and read-only verification scripts; no engine binaries/decompilation, executable JARs, native libraries or player worlds.

| Artifact | ID | ZIP SHA-256 |
|---|---:|---|
| baseline-first | 14253478556 | 83a4d249bc10e080ed98d32cb216f2aea9c0211ca4eaef0c94639f0eb6544007 |
| candidate-first | 14253263675 | eb8a9ebac777d96ec5408bf1b12c7eef7eeae16674abfb563d56e5c327f25f1a |
| owned-save | 14253333687 | 682bfa953da4b4eb7a9f69e670245063ae23ef1d2116e1d5ea159ff23183345f |
| failure-cancel | 14255256130 | 3628467041bcc2eeff67d017566e588fbf30eea62739f30ceecf8262a8a86462 |
| cuts-restart | 14254951459 | e8ffa9fdaf9626622b6894a699f3bb3acf1c07303842054a09dfd679a7f2bcfc |
| clients-room | 14254901286 | 07363e3c47686d4866ec337af087e3d3f306bcb5a3fcbfcae96f847ed6b61c94 |
| clients-cold | 14254633751 | 704d3b1634fd532a9d19129358a6d6107d0488d854c98ff8d08a811fdd5167a0 |
| original interrupted transition | 14253904869 | dc42828e1a92fb08409fc0389276e037dcc2751083c9c2650d400f4908593d4a |
| successful focused transition repair | 14255987778 | 35a9d6ccb732f897b0b0e06f5c5ad22e059c0c9c11517058c17797d1223ba90d |

Workflows: https://github.com/WeepingAngeIs/zSortange/actions/runs/43921985243 and https://github.com/WeepingAngeIs/zSortange/actions/runs/43925836335 . Automatically triggered ordinary suites are not new unload evidence.
