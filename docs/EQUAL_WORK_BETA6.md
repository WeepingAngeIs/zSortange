# Beta.6: equal completed work with current save-safety protections

**Both controlled pairs and all four independent restarts passed. Verdict: retain the 32-serialization default and keep the candidate experimental.** Raising the existing cap to 64 shortened cold setup drainage, but did not consistently improve equal useful-work completion. Work-phase p95 and sampled memory were higher in both pairs. No production or existing experimental algorithm changed; no replacement playable JAR is required.

## Original acceptance gap

This step reopened SAVE_BUDGET_BETA6.md and BACKPRESSURE_BETA6.md at c72091d83082dc7309fb3de83d761b6fbcf1a3c3. The first established peak-stall redistribution with longer drain/higher p95. The second reduced average queued work partly by completing approximately31% fewer useful updates. Later safety matrices were not controlled performance comparisons.

Unfinished acceptance was equal completed durable work including admission and full drainage; actual dirty-to-write/flush latency; current journal/reset safeguards included; serialization versus residual-stall attribution; explicit save/stop cost; and independent persistence/identity checks. Universal latency targets, unlimited-arrival stability and full-pack performance were not established and are not claimed here.

## Exact source and comparison

Tested source: `64698d686ac8dbabd82b90540f7edd4cf8f42b7b`, branch `feature/elemental-pockets`.

Targeted workflow: **44048493150**, both jobs successful:
- baseline-first-force, job133920052071:32 then64, independent restart after each, explicit force-save tail.
- candidate-first-stop, job133920051596:64 then32, independent restart after each, normal stop tail.

Both use the same executable with the preexisting `anya.budgetSnapshots` property set to32/64. The original256callback/8ms soft allowances remain. Current nonblocking preparation, stale ownership guard, save budget, backpressure, journal publication/primary force/read-back/proof and reset-intent protections remain enabled. Strong tickets and permission/quarantine rules are unchanged. The remote-ticket weakening experiment is absent.

Eight server-player fixtures with embedded packet sinks prepare eight pockets per world. **Zero real Minecraft clients/network sessions** run in this profiling step. After setup and its eight durable initial samples, each owner completes six authorized-open/name-version-update/close cycles: exactly48 useful versions, with all matched ordinary write receipts and successful additional force-flush fences. Only one unconfirmed version per sample is permitted. Admission may retry, but mutations are not replayed.

These are name/component updates to104 conserved diamonds, not48 production inventory transfers or newly created items. Menus hold30ticks, cooldown20ticks, admission cadence5ticks in both arms. Work ends only after all48 versions are durable and actual runtime cache/queues/tickets are empty. Each work window executes exactly43,200 polled callbacks, one execution per poll.

An identical eight-version dirty tail then tests force-save or normal shutdown. Independent processes read physical primary before loading destination chunks and compare newest version-seven contents and journal proof, all8room/cell/owner identities and32physical chest identities. All checked items total104; other slots remain empty. Exact cleanup and APPLIED/unblocked matching journal snapshots pass.

## Equal useful-work measurements

| Metric | EPYC7763:32 first | EPYC7763:64 second | EPYC9V74:32 second | EPYC9V74:64 first |
|---|---:|---:|---:|---:|
| Completed updates |48|48|48|48|
| Elapsed through full drain |19.298s|19.496s|19.548s|18.999s|
| Confirmed updates/sec |2.487|2.462|2.456|2.526|
| Full tick median |2.969ms|3.282ms|2.705ms|3.164ms|
| Full tick p95 |7.700ms|7.821ms|6.232ms|7.318ms|
| Full tick maximum |32.970ms|50.758ms|35.450ms|21.461ms|
| Main-thread CPU sum |1.390s|1.526s|1.206s|1.328s|
| JVM CPU first/last sample delta |12.57s|15.22s|10.40s|10.08s|
| Admission grants/deferrals |48/37|48/24|48/27|48/29|
| Maximum admission wait |30ticks|20ticks|25ticks|30ticks|
| Peak sampled process RSS |905.0MiB|960.8MiB|909.7MiB|1,039.8MiB|
| Work peak used/committed heap |551.3/586MiB|600.4/644MiB|539.5/589MiB|676.6/727MiB|

64 is about1.0% slower in one pair and2.8% faster in the other. Work p95 and main-thread CPU sum are higher in both64 arms. Two pairs do not establish statistical significance or a general throughput benefit.

Useful work never reaches either serialization cap: the maximum is20–24snapshots/tick, with zero work ticks at32/64. The256callback ceiling is reached on155–162ticks per arm. Raising the snapshot limit therefore does not address the limiting admission condition in this warm workload. This does not establish that raising the callback ceiling would safely improve it.

## Save latency, queue residence and cold drainage

| Metric |7763:32|7763:64|9V74:32|9V74:64|
|---|---:|---:|---:|---:|
| Dirty to observed write median |1.697s|1.723s|1.697s|0.500s|
| Dirty to extra-fence median |2.169s|2.198s|2.070s|1.177s|
| Dirty to extra-fence p95 |3.005s|3.232s|3.108s|2.929s|
| Dirty to extra-fence maximum |3.300s|3.447s|3.547s|3.046s|
| Mean work callback queue |564.3|551.3|443.8|409.9|
| Peak work callback queue |5,152|5,152|5,152|5,408|
| First-observed callback residence p95 |701.3ms|699.9ms|751.3ms|750.3ms|
| Drain after final view closure |12ticks|19ticks|17ticks|14ticks|
| Last dirty edit to complete drain |42ticks|49ticks|47ticks|44ticks|

Receipt latency includes unchanged20tick batch fencing and owner-thread polling. The extra fences are active work. Protected ordinary write completion itself already includes current journal/primary force/read-back/proof work. The finite sample registry is not a universal save deadline or arbitrary saturated arrival test.

Queue residence is a lower bound from first observer sighting, not actual enqueue time. Unseen executions are counted separately; no callback is wrapped or removed. Cumulative work-window queue-scan overhead was5.7–6.7ms and is included in full ticks. Final observer-entry counts are zero.

Cold setup drainage does improve at64: **249->152ticks on7763 and252->175ticks on9V74**. Maximum initial-sample confirmation falls12.384->7.097s and12.140->8.669s. In that phase the cap binds. This is a distinct observed cold-batch tradeoff, not a universal loading speedup or a warm-work improvement. Initial preparation has minor variation in generated/cache work; setup must not be omitted from whole-run save-delay claims.

## Current journal and serialization costs

| Work-window measurement |7763:32|7763:64|9V74:32|9V74:64|
|---|---:|---:|---:|---:|
| Inclusive main-thread serialization sum |502.0ms|467.9ms|403.9ms|396.0ms|
| Serialization calls |3,244|3,245|3,102|3,236|
| Atomic journal publications |248|250|252|232|
| Atomic journal elapsed on worker |274.1ms|218.5ms|533.2ms|858.9ms|
| Bytes supplied to publications |3,143,952|3,169,956|3,195,960|2,935,920|
| Primary region-write worker elapsed |1,186.0ms|1,154.8ms|1,564.1ms|3,369.4ms|
| Primary region-flush worker elapsed |26.2ms|40.2ms|119.2ms|33.5ms|
| Region-flush calls |139|141|142|132|

Worker elapsed overlaps server work and is not server CPU. Atomic journal timing covers write/force/rename/directory-force within that method, excluding preceding encoding. Primary writes include unjournaled void chunks. Nested/inclusive spans must not be added as total tick cost. Journal payload ends at199,944bytes. There is no journal-off control, so these are measured costs, not isolated causal overhead. No explicit repair or reset occurs in the healthy workload; loaded reset safety code is not falsely charged as executed work.

## Remaining stall attribution

JFR/direct timers distinguish several causes rather than relabel every long serialization span as serialization CPU:

- Forward32's largest work ticks32.970/26.018/24.617ms overlap22.032/22.810/19.253ms of GC pause. Reverse64's21.461/19.310/18.459ms work ticks overlap18.217/16.170/13.388ms of GC. Those pauses occur inside elapsed method brackets and must not be added twice. GC logs corroborate them.
- Forward64's50.758ms work maximum has49.874ms server CPU, zero timed pocket serialization and only0.819ms timed pocket unload work. One execution sample is in `ChunkMap.processUnloads`, but lacks level/exclusive attribution. Reverse32's35.450ms maximum likewise has zero pocket serialization and insufficient sampled detail. These unresolved portions are not proof against the journal or grounds for raising its cap.
- Real force-save remains blocking:154.524ms at32 and146.179ms at64. Its full ticks160.837/153.973ms include106.601/95.833ms recorded server parks in `ChunkStorage.flushWorker -> CompletableFuture.join` and40.292/42.045ms serialization, with no overlapping GC. Values are nested. All force-save obligations are retained.
- Setup has mandatory ledger and initialization costs. Reverse64's398.700ms setup peak has six samples in file force under `PocketLedger.commit` plus a17.569ms recorded write. Other startup peaks include advancement/class initialization and GC. Sparse samples are not an exclusive per-instruction breakdown.

Whole-case peaks and exclusions are retained. No durability barrier is weakened to flatten the chart.

## Stop/force and restart evidence

The force pair confirms all eight tail versions after real `save(true)` and exact drainage, performs primary/journal read-back, then uses `Runtime.halt(77)` before clean shutdown. The stop pair requests ordinary shutdown with eight newly dirty samples and no manual preflush. Both stop arms execute7,200 unmetered callbacks. Full stop time is592.526ms at32 and818.426ms at64, including observer output/JFR dump and other stop listeners.

Tail receipt polling stops during shutdown, so those cases have no per-version tail durability timing. Their independent newest-version physical recovery establishes the checked persistence, not an invented latency. The tail begins with dirty loaded chunks, not a claimed preexisting saturated callback backlog.

All four fresh restart processes verify104latest named items,8room identities and32chest identities against physical primary plus journal before destination loading. Exact runtime cleanup follows100ticks. Required snapshots/proofs remain. Prior actual-client safety tests are historical; none were manually repeated as new profiling coverage.

## Bounds, exclusions and preservation

Runners: EPYC7763 and9V74, four guest logical CPUs/~15.61GiB visible RAM, Microsoft virtualization/Linux7.0.0-1019-azure, Temurin21.0.13+9, Minecraft1.21.1/NeoForge21.1.249. Server2GiB heap/G1, ergonomic processors2 rather than OS affinity, view3/simulation2, flat seed860612/no spawn tickets. Fresh JVM/world per arm; libraries/OS caches reused, no forcedGC/cache clear.

Seeds last62–69seconds; equal useful-work windows only19–20seconds. Limits:360seconds/process,7000ticks overall/3600phase,4GiB sampledRSS,2GiB host available,92%heap and15,000cache. No final guard fired. Minimum host availability exceeded14,025MiB. Whole-case cache peaks8,325–8,477, work peaks7,200, requests136.

Full tickServer includes fixture and legacy output but excludes sleep, early startup, its own final metric row collection/output. The48-work elapsed marker ends before terminal raw verification; the last full work tick includes that15.4–18.0ms fixture verification. Both definitions are explicit. Same bounded observers and64MiB/360second JFR recording run in both arms;20ms samples/5ms waits cannot identify every short operation. Worker deltas are between first/last samples. RSS sampling is1Hz, not continuous or a live-object census. Stop duration includes observer flush/dump. Hard-cut final tick-return data can be absent; terminal markers/restart checks supply that boundary. No uninstrumented FPS/TPS or memory-leak result is claimed.

Initial workflow44048182309 at6821af37b6c270215f994b292265572aeb2e0025 failed compilation before Minecraft: two new helpers omitted checked ledger IOException handling. Final fixture propagates those errors. Both initial artifacts remain preserved, not mixed into results. No storage assertion was relaxed.

All356files in the preceding tested interruption source remain byte-identical, including74production files. Its report already existed at the starting head. This phase adds15test/plan/build/workflow files; no production, repair/operator, default setting, format, item or artwork change.

SHA-256:
- Unchanged playable beta.6: `5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11`.
- Unchanged current operator/safety module: `e11ed33cfb44e53bb059dbbb0449f94a87a02fa0f7d6de3ec38477a35f840c57`.
- Equal-work test wrapper: `45cf1f1063d1dfbc0b8a3c502cb9847ea9bb8675d67fbf3ed08b7b56089a6a38`.
- Exact tested source ZIP: `58d39d962a0b22d3d0e914fdda5e9ddcac0eb7689eade31865af0c21c60e2e8e`.

## Decision

**Stop this comparison here; retain32, no algorithm/default change or replacement playable artifact.**64 helps cold batch drainage, but equal warm work does not show a consistent benefit, p95/memory are higher, and mandatory flush waits remain. No new operator/UI/recovery feature or production integration follows from these results.

The next performance decision must distinguish cold snapshot-bound bursts from warm callback/admission/receipt cadence and GC/ledger/flush costs. Any later optimization must preserve the same completed workload and durable obligations. This report does not authorize an immediate callback-cap increase or a tuning sweep.

Longer loaded-server performance, larger/variable payloads, actual-client/full-pack performance and saturated physical storage remain untested. No durable newest primary and no durable newest journal still means no recovery of that edit. Cross-file player/chunk/ledger/POI non-atomicity remains. No disk-fault or operator reset test is claimed as newly run.

No installed beta.6, user profile/settings/Java arguments, real world, live server, artwork/UI or production saved format was changed. No installation, unsafe downgrade or production integration. Nothing is needed from Anya. The evidence kit preserves four unchanged CI archives, exact source, raw CSV/JFR and read-only analyses, without executable JARs or player worlds.

Results: https://github.com/WeepingAngeIs/zSortange/actions/runs/44048493150
Source: https://github.com/WeepingAngeIs/zSortange/commit/64698d686ac8dbabd82b90540f7edd4cf8f42b7b
Initial compile failure: https://github.com/WeepingAngeIs/zSortange/actions/runs/44048182309
