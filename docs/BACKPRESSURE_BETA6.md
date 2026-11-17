# Beta.6: sustained admission and dirty-save backpressure

**The final targeted workflow passed all seven jobs. This report completes analysis of the existing checkpoint; no Minecraft scenario was rerun during report completion. Keep the candidate experimental. No replacement playable JAR is required.**

All runtime tests were conducted in disposable GitHub infrastructure. No installed profile, user Java arguments/settings, real save, PC or live server was accessed or changed. The playable beta.6 binary, production formats, identities, inventory rules, artwork/UI, default-off gates, damage suppression and non-live preview remain unchanged.

## 1. Exact provenance

**Tested executable source:** `aea2817ed43c387187d30f722ea29e7cf1d4addb`, branch `feature/elemental-pockets`.

**Targeted workflow:** `43972849850`, Sustained admission and dirty-save backpressure, all seven jobs successful.

| Required job | Job ID | Result |
|---|---:|---|
| Baseline-first sustained pair plus independent restarts | 133669217126 | Passed |
| Candidate-first sustained pair plus independent restarts | 133669217173 | Passed |
| Actual worker write/flush failure and restart | 133669216734 | Passed |
| Dirty-backlog shutdown and explicit flush/restart | 133669217142 | Passed |
| Two actual clients: room construction and restart | 133669217130 | Passed |
| Two actual clients: genuinely cold/unsafe recovery | 133669217102 | Passed |
| Two actual clients: transitions, active shutdown and interrupted return | 133669217097 | Passed |

Supporting workflows on the same commit are green: build/verification `43972850055`, packaged room construction `43972849953`, and multipocket isolation `43972849895`. These automatically triggered supporting regressions are not new load coverage or substitutes for the targeted workflow.

SHA-256:
- Unchanged playable beta.6: `5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11`.
- Separate admission/backpressure experiment: `90a76787e8b7322ba1974ea58fd7379e9bdc5c24b30114def7def7495bbc3336`.
- Exact tested source ZIP: `ca7f2115e259cd4d69dd2c2d0a8305411a2a8ac742e748a9eca18d53c7de8a09`.

The seven downloaded final artifacts identify this same commit, source ZIP and experiment/playable hashes. The review independently checked their digests, markers, source and CSVs. All 74 production source/resource files, eight preceding budget files, 11 minimal-module files, 15 durable-preparation files, 12 remote-selector files and 11 acceptance files match the completed save-budget source. A report-only follow-up is not a new measured executable.

## 2. What the admission prototype changes

The candidate sits after the existing authorization/stage checks and before closing a menu, acquiring a lease or requesting tickets. It can defer activation of a new distinct pocket. It never owns or removes a save, chunk, inventory, callback or storage lease.

Admission closes when an observed worker failure occurs, the larger of the pending-unload/callback counts reaches 2,048, cache entries reach 12,000, the oldest monitored dirty sample reaches 200 ticks, or used heap exceeds 80% of maximum. It reopens only below the corresponding low-water conditions: no failure, queue/pending at most 512, cache at most 9,500, monitored age at most 80 ticks and heap under 65%.

At most 32 waiting player intents are retained. Retrying preserves an intent's place; abandoned intents expire after 200 ticks. Once pressure eases, queued distinct activations are admitted in FIFO order at most once per tick. Healthy unqueued requests are not artificially restricted to one per tick. The caller still must retry; this is not an automatic delayed menu opening.

Authorized sharing of a pocket that is already active bypasses the admission gate, while the original capacity and permission checks still apply. Existing views and independent lease deadlines are not reset, revoked or evicted to reduce pressure. Return recovery follows its original separate path. Save budgets, original engine save/flush obligations, strong home/recovery/visitor tickets and save-before-READY remain unchanged.

**This is admission pacing, not a hard cap on required save work.** The queue may exceed 2,048 while already admitted work drains. The experiment preserves that work instead of deleting entries to satisfy a number.

The owner-thread policy checks pass **20,012 assertions**, including FIFO, bounded intent capacity/expiry, failure/heap hysteresis, active-pocket sharing and thread ownership. Those are pure policy checks, not thousands of connected-user tests.

## 3. Workload and the meaning of durability measurements

The main comparison contains four fresh-world/JVM cases, reversing policy order on two runners. Both policies use the already-tested save budget, minimal ownership guard and nonblocking-preparation prototype. The remote-ticket selector is absent in these load cases. Only admission backpressure differs.

Each case uses **20 ordinary server-player fixtures with embedded packet sinks, zero real Minecraft clients**, 12 pre-created pockets, at most eight active pockets and at most 16 leases. Two remote viewers remain open. Ten other owners repeatedly open, dirty, close and reopen their own physical storage for **3,600 ticks**, nominally three minutes. A cycle writes a new name/version on the same 13-item sample, not 13 newly created items each time. Each contender waits for its previous sample version to be confirmed before dirtying again. There are therefore bounded arrivals and at most one unconfirmed version per tracked sample, not an unrestricted stream of arbitrary player writes.

The active limit is deliberately reduced from eight to four for 600 ticks, then restored. This exercises lower limits without hiding existing stored items. Initial preparation of the 12 rooms runs before admission backpressure is enabled. The later recovery phase begins after the sustained dirty samples are all confirmed; it retains the two independent remote viewers, but does not overlap an ongoing dirty-arrival stream.

### Observed write completion, not inferred from unloading

The fixture matches each named sample in actual serialized block-entity data and retains its `IOWorker.store` completion future. After that write succeeds, it periodically issues the real `synchronize(true)` fence and confirms only after its successful completion. Failed writes or flushes are never counted as durable.

The delay is from the server tick/time when the sample is dirtied to the tick/time when both successful write and flush are observed. It includes up to roughly 20 ticks of fence batching and subsequent polling. Both policies use the same monitoring.

**Those fences are active test work, not passive observation.** Their I/O cost changes the workload, and the sample registry covers at most 16 explicitly registered samples rather than every container in a real pack. The result is a measured maximum for this finite workload, not a guaranteed maximum for all saves or arbitrary storage failure. The observer's uninitialized sample-age values during initial setup are not actual dirty-save latencies; reported durability values come from matched receipts, not that setup-age signal.

## 4. Queue growth, throughput and save delay

All rows below summarize the entire 3,600-tick sustained-arrival phase. Percentiles use linear interpolation; all raw records and the analysis script are retained.

| Within-runner metric | EPYC 7763 baseline | EPYC 7763 candidate | EPYC 9V45 baseline | EPYC 9V45 candidate |
|---|---:|---:|---:|---:|
| Mean queued callbacks | 524.4 | **121.0** | 335.6 | **115.2** |
| Queue p95 | 2,064 | **644** | 1,420 | **644** |
| Peak queue in sustained phase | 4,120 | 4,120 | 3,864 | 3,864 |
| New versions successfully written and flushed | 480 | **333** | 480 | **330** |
| Dirty-to-confirm median | 1.806 s | 2.014 s | 0.998 s | 2.492 s |
| Dirty-to-confirm p95 | 1.951 s | 2.501 s | 2.499 s | 2.502 s |
| Dirty-to-confirm maximum | 2.848 s / 57 ticks | 2.648 s / 53 ticks | 2.849 s / 57 ticks | 2.798 s / 56 ticks |
| Admission refusals by the new gate | 0 | 1,562 | 0 | 1,584 |
| Maximum waiting intents | 0 | 7 | 0 | 8 |

Average callback occupancy falls about **76.9% and 65.7%**, but successful churn falls **30.6% and 31.3%**. This is work being paced/refused, not the same volume being saved that much faster. The per-pair queue peak is unchanged. The intent counter records attempts, not that many distinct players or lost operations.

Including the 12 initial version-zero setup samples, maximum observed dirty-to-confirm delay is **9.044/9.785 seconds in the baselines and 9.490/9.339 seconds in the candidates**. The largest candidate receipt delay is therefore 9.490 seconds in this test, not a sub-three-second whole-run guarantee. Neither observed maximum is a hard bound. A failed write has no successful latest-version confirmation at all.

All four complete cases peak at **6,377 queued/pending entries** during setup. Whole-case raw cache peaks are 9,000 on the 7763 pair and 8,481 on the 9V45 pair. Logical requests peak at 137. Admission does not make these numbers interchangeable, and the initial setup peak is not controlled by the disabled setup gate. Windowed measurements show repeated drainage and re-entry rather than unbounded growth under the tested finite stream. They cannot prove stability for sustained arrivals exceeding disk service capacity.

After the final receipts/flushes, actual ticket audits and empty pending/callback/cache checks, each stress server is cut with `Runtime.halt(77)` before clean shutdown can rescue data. A new server verifies **156 correctly named items, all 12 original room/cell/owner identities and 48 physical chest identities**, with other checked slots empty, then drains again. These are 156 conserved items, not hundreds of new item copies corresponding to the version counter.

## 5. Memory and tick costs

| Within-runner metric | 7763 baseline | 7763 candidate | 9V45 baseline | 9V45 candidate |
|---|---:|---:|---:|---:|
| Whole-process sampled peak RSS | 1,288.6 MiB | 1,251.7 MiB | 1,214.0 MiB | 1,088.9 MiB |
| Peak used Java heap | 875.0 MiB | 849.2 MiB | 826.0 MiB | 717.0 MiB |
| Peak committed Java heap | 949 MiB | 910 MiB | 875 MiB | 762 MiB |
| Sustained event median / p95 | 3.147 / 7.168 ms | 2.531 / 6.835 ms | 2.916 / 6.017 ms | 2.561 / 6.092 ms |
| Maximum sustained event tick | 19.151 ms | 26.715 ms | 47.330 ms | 38.770 ms |
| JVM process CPU during sustained interval | 65.87 s | 53.39 s | 51.34 s | 48.41 s |

RSS is lower in these two pairs, but fewer successful cycles were admitted. This is not a demonstrated per-save efficiency gain, live-retention reduction or general FPS/TPS improvement. RSS is resident process memory; committed heap is JVM capacity, and used heap includes not-yet-collected objects. No live-object histogram or forced GC was performed in this phase.

Used heap never exceeded **42.8% of the 2-GiB maximum**, so the 80% emergency heap branch was not exercised by actual memory exhaustion. Its behavior was tested only with synthetic policy inputs. Cache pressure also never reached its 12,000-entry admission threshold in these runtime cases. Monitored dirty age during the gated sustained phase stayed below 200 ticks; the natural queue threshold and FIFO backlog are the runtime admission-pressure evidence.

The preserved 32-serialization/256-callback/8-ms soft save budget still spreads work rather than dropping it. Explicit save and shutdown remain potentially blocking. These comparisons are against that already-budgeted candidate, not plain beta.6 and not the older single large-drain scenario. Historical peak-stall numbers are not substituted into a cross-workload speedup calculation.

## 6. Fairness, existing viewers and recovery

The two established remote viewers retain their original lease deadlines and authorized contents throughout load. Guarded withdraw-and-return transactions run repeatedly through the production transaction checks. Lowering the active cap does not silently close those views or truncate storage.

Each of the ten contenders completes at least **30 cycles with backpressure**, ranging 30–36 on the 7763 and 30–35 on the 9V45. Baseline contenders range 37–58. The candidate distributes fewer completed cycles more evenly in this finite scenario. The longest request-to-grant observations are **650 ticks** for candidates and 640/650 for baselines; these include the deliberate 600-tick lower-cap window, not just gate waiting. There is no unconditional latency/fairness guarantee under unlimited arrivals or non-retrying users.

Eight unsafe-return guests are then serviced at **one attempt per tick**, with exactly **100 ticks between retries per guest**. Every guest receives four attempts. After the fixture repairs the home landings, all eight return records clear within **58 ticks**; checked existing-view transactions and deadlines remain intact. The recovery path is not throttled by the new admission gate.

**Coverage boundary:** this eight-user recovery phase starts only after all preceding dirty samples are durable. It demonstrates bounded recovery contention alongside existing viewers, not recovery against a simultaneously failing/overloaded dirty-write stream. That combined stress case is still untested and must not be inferred from these separate passing phases.

## 7. Failure evidence: rejection is not recovery of a failed update

The final injector targets the exact pocket dimension, chunk-store type and monitored dirty chunk. It throws in the real region I/O worker, not on the world thread. Each injected failure is confirmed in the raw log and associated write/flush receipt.

**Write failure:** the latest version-one sample write fails; it remains unconfirmed. New inactive-pocket admissions are refused, while the two other established viewers and guarded transactions remain valid. The failed sample's chunk is unloaded before shutdown, so an in-memory close-time save cannot rescue it. Restart intentionally expects and recovers **the previous durably saved version zero**, with 104 total items and eight/32 room/chest identities intact.

This is a passed detection/refusal test, **not restoration of the newest failed edit**. The sample count remains 13 but the latest name/version is absent. In real use, a failed disk write can lose changes, including items. The gate is not an item journal or write-retry system.

**Flush failure:** the write can complete but the failed fence leaves version one unconfirmed. New inactive admissions remain blocked. A subsequent normal server shutdown successfully flushes the original storage path; restart recovers version one. This does not prove survival if storage continues failing or power is lost before a later successful flush.

Both failure runs verify **104 named items, eight room/cell/owner identities and 32 chest identities** against their explicitly different expected versions, then empty the real engine queues. Neither failure is hidden as a successful latest-version receipt.

The failure latch is **in-memory and process-local**. There is no persistent degraded-storage journal, operator repair workflow, automatic write retry or safe clearing protocol. Already-active pockets bypass admission, so existing writes are not globally quarantined after a detected fault. The receipt registry is fixture-bounded, not universal production tracking. Those are concrete blockers to shipping the admission prototype as a general save-safety feature.

## 8. Dirty-backlog stop, force flush and actual-client regressions

Both stop/force cases detect **eight dirty chunks and 7,192 queued callbacks**, then prove a new inactive-pocket request is refused before activation.

The normal stop is requested without manual pre-save or pre-drain. Shutdown retains the original unmetered save/flush path; 7,192 force/bypass callback executions are recorded and the actual pending/callback queues close empty. A new server recovers the expected latest 104 items and eight/32 identities.

The explicit force-save case invokes the real `save(true)` bypass, waits for recorded write/flush completion and exact drainage, then cuts the process before clean shutdown. Independent restart recovers the same checked latest data. No required callback is evicted to make the admission metrics look bounded.

Separate jobs run **two genuine Minecraft client JVMs over localhost TCP** with the exact final experiment. The client Java fixtures are unchanged. They pass room construction/storage/deposit/restart; genuinely cold-home/unsafe-fallback recovery and unresolved restart; remote/visit transitions, independent/shared viewers, revocation/stale packets, real client SIGKILL/reconnect, active-session shutdown and the selected post-teleport/pre-save-confirmation return cut.

The active transition shutdown still audits **34 requests to zero actual engine-owned tickets**; final cache, pending callback and return records are empty. Named samples and original room identities remain conserved. The client runtime includes the unchanged efficient remote-ticket experiment, OPAC 0.33.0 and Carry On 2.2.6.13, unlike the fixture-only load lane.

These actual-client jobs establish lifecycle compatibility of the final admission hook, **not 20 connected users under sustained overload**. Their per-sample durability/fault registry is not populated as in the stress fixture, so they do not exercise the injected write-failure/dirty-age path. They are not full-pack, human-interface or WAN-latency certification.

## 9. Earlier failures preserved

Only final workflow `43972849850` supplies the primary measurements. Earlier failed evidence is retained separately.

- **`43970511482` at `3e923ae14197ed895f339f645dad22f47fd83b26`:** the first admission policy enforced one fresh distinct activation per tick even with no pressure/backlog. The unchanged two-client transition fixture failed at `independent-remotes: Wrong remote menu`. This was an experimental admission-policy defect, not a beta.6 storage failure. The repair permits healthy unqueued requests immediately while retaining FIFO pacing for backlogged work. Client assertions were not relaxed or blindly retried.
- The same run's failure lane and **`43971340166` at `5a4b7a41c50e3f32b6a39b63a0e23f3873e8ecd4`** hit the fixture tick ceiling because the original fault target relied on formatted NBT text containing the sample name. It never reached the intended exception. The final target uses exact dimension/type/chunk identity; its real fault log records `legacy_name_match=false`. This repairs fault injection rather than redefining a successful write as failure or swallowing an exception.

Raw failed controllers, server logs and earlier source snapshots remain in the kit. Earlier green lanes were not pooled with the final results. The report completion did not repeat a passed scenario or alter a Minecraft executable.

## 10. Environment, limits and next decision

Paired runners: AMD EPYC 7763 and EPYC 9V45, each four guest logical CPUs and approximately 15.61 GiB visible RAM. Each sequential baseline/candidate pair stays on one runner, with order reversed on the other. Linux 7.0.0-1019-azure under Microsoft virtualization; Temurin 21.0.13+9, Minecraft 1.21.1, NeoForge 21.1.249. Server Xms256m/Xmx2048m, G1, ergonomic processor count two (not OS CPU pinning), view distance three/simulation distance two, flat seed 860612, no spawn tickets.

Stress processes last approximately **291–297 seconds**, including startup, cold setup and cleanup. The sustained-arrival measurement itself is 3,600 ticks. Every run has an explicit 620-second process limit and 10,000-tick fixture ceiling; sampled server RSS above 4 GiB or host available memory below 2 GiB stops the run. Existing prototype cache/heap monitoring also remains. No final cutoff fired; host available memory in the primary cases stayed above **13.45 GiB**.

Memory sampling is once per second, not a continuous maximum. Tick metrics retain instrumentation and exclude final CSV/lowest-priority post-tick fixture work. Both sides include receipt polling and periodic flush fences. Fresh worlds/JVMs reuse libraries and OS caches, with no cache clearing. The raw per-tick buffers may omit a few final rows after an intentional `Runtime.halt`; separately written terminal markers and successful restart/engine assertions establish the final boundary. All 3,600 sustained samples are present.

**Decision: retain the experiment, no replacement playable artifact.** Queue occupancy and demand pacing improve in this bounded workload, at the cost of lower completed throughput; hard save deadlines, sustained arbitrary overload and memory exhaustion are not established. The failed-write test demonstrates detection and previous-version recovery, not restoration of the unsaved version. General tracked-save receipts, durable degraded-state/retry policy and the combination of recovery with ongoing dirty overload remain unresolved production requirements.

There is no capability blocker to finishing this report and nothing is needed from Anya. The next narrowly scoped independent step is to test recovery while a controlled dirty backlog remains unresolved and define persistent failure/quarantine semantics before promoting any admission feature. Do not add a full-pack safety claim or weaken existing viewer/return protection to satisfy the gate.

Minecraft's separate player, ledger, chunk and POI files remain non-atomic. Selected successful-flush cuts do not prove arbitrary power-loss safety. Live see-through portals, unrestricted room machinery/containers, exact-pack grave/mover interactions and long-duration soak coverage remain outside this milestone. No portal backend was installed or bundled.

Primary workflow: https://github.com/WeepingAngeIs/zSortange/actions/runs/43972849850
Tested source: https://github.com/WeepingAngeIs/zSortange/commit/aea2817ed43c387187d30f722ea29e7cf1d4addb

The evidence kit retains raw measurements/logs/screenshots, exact source, read-only analysis and verification scripts, archive digests and earlier failures. It contains no executable JARs, native libraries, player worlds or decompiled engine sources.
