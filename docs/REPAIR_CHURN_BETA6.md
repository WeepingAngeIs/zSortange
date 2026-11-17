# Beta.6: bounded concurrent snapshot and multi-key repair churn

**Both final stress jobs passed on the same executable source. The new workload adds queued/executing supersession, repeated saturation, persistent-failure restarts and selected repair/reset interruptions. The repair algorithm and playable beta.6 are unchanged. Keep the candidate experimental; no replacement playable JAR is required.**

All Minecraft execution used disposable GitHub environments. No installed profile, user PC, Java argument, setting, real save or live server was accessed or changed. There was no installation or force push.

## 1. Exact checkpoint and provenance

Starting report checkpoint: `306454e17749c656013d203ef52af1fe18a657a5`.

**Tested executable source:** `aca97511e49a7355857f6388c4dd6f5cd799cdd9`, branch `feature/elemental-pockets`.

**Targeted workflow:** `44007662967`, Bounded concurrent snapshot and repair churn.

| Lane | Job ID | Final result |
|---|---:|---|
| Forward rotating key order | 133786780707 | Passed all four server processes |
| Reverse rotating key order | 133786780535 | Passed all four server processes |

These are two orders of the same tested algorithm, not a baseline/candidate performance comparison. Other build/room/isolation workflows triggered automatically; they are not counted as new concurrent-failure coverage.

SHA-256:
- Playable beta.6: `5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11`.
- Unchanged underlying repair experiment: `49370865822761b72241aad6679ebce8f10a523f47397e93b961e3f25c9eb1b4`.
- New standalone churn/fault-observation module: `a39821c99dbe619b1af0d8693486322b52a4c3ff41022fd011dfb09727c59b6e`.
- Exact tested source ZIP: `dedea03805f948dcf7b85479cd7644fca78c934a376d47664f0298450a20fbf6`.

The downloaded final archives identify the same source and hashes. All previous executable source is byte-for-byte unchanged, including 74 production files, 22 repair-module files, the repair-client scenario, 11 acceptance files and the preceding ticket/preparation/budget/backpressure modules. This phase adds nine files only: an isolated source set, workflow and wrappers. The previous report document is also present in the source snapshot. A report-only follow-up is not a newly tested executable.

## 2. New workload and its scope

Each lane starts a fresh world and uses eight ordinary server-player fixtures with embedded packet sinks. Two unaffected owners retain remote storage views; subsequent server processes reconnect only those two fixture identities. **There are zero real Minecraft client processes or network sessions in this phase.** Existing real-client results remain historical evidence for the unchanged underlying code, not newly executed client tests.

Nine distinct chunk columns in six rooms contain 13 diamonds each, for **117 conserved diamonds**. The other two rooms contain 17 emeralds each, for **34 conserved unrelated items**. The fixture records eight room/cell/owner identities and all 32 physical chest identities.

Across **12 rounds per lane**, it submits two generations for each of the nine keys in one server tick. It then submits an additional newer generation for two keys while one older repair is executing and another older repair has been dispatched. This totals **240 snapshot submissions per lane**, not 240 independent item transfers or new stacks. Each snapshot only changes the name/version of the same-count sample.

The submissions are detached complete chunk snapshots sent through the real `IOWorker.store` path on the server thread, while the target columns are unloaded. They model overlapping already-produced saves. They do not bypass a gameplay permission check to declare live quarantined-container edits safe. The engine worker serializes its I/O, and same-key coalescing is permitted: concurrent outstanding submissions do not mean nine parallel disk threads or a retained history of every intermediate version. The required invariant is preservation of the newest expected snapshot.

The executing repair is held immediately before its physical region write for one observed server tick, using a worker-only latch with a five-second deadline. The main server thread submits newer data and releases it. The target remains under injected write failure during that overlap. The test observes both the ordinary worker failure and subsequent latest journal publication. This is a controlled failing-repair overlap, not arbitrary scheduling or a physical disk hang. A healthy-executing-repair variant with different completion order is not separately established.

No repair algorithm, writer guard, callback ownership, dirty flag, save future or primary fallback behavior is replaced. The observation hook makes a stale physical generation a test failure; it is not shipped protection. There were no such violations in the final runs.

## 3. Supersession, saturation and retry fairness

Each round schedules eight repair intents and attempts a ninth. The ninth is refused at the existing quota, while its required snapshot/token remains unchanged. The queue later drains and that key receives repair service. The key order rotates each round and is reversed in the second lane.

One repair key is allowed to succeed while the others still fail. The fixture requires that healthy progress, fresh journal generations and correct quarantine before clearing the artificial fault. Old queued tokens and premature reset requests are rejected. After the fault is explicitly cleared, new bounded repair intents are issued, each key is reset only with current verified proof, and a direct primary-region read after flush must match the latest generation before another round starts.

Both final lanes independently recorded:

| Observation | Per-lane result |
|---|---:|
| Completed failure/saturation rounds | 12 |
| New snapshot submissions | 240 |
| Newer submissions during a held executing repair | 12 events, two keys each |
| Refused ninth intents | 12 |
| Maximum pending repair intents | 8 |
| Repair dispatches, across all four processes | 334 |
| Maximum new repair dispatches per server tick | 1 |
| Maximum attempts per explicit intent | 3 |
| Injected sample-region write failures | 356 |
| Independent guarded withdraw-and-return pairs | 64 |

For retry attempts within the same intent, observed gaps were **21–24 ticks from attempt one to two**, then **41 ticks from two to three**. A later explicit repair request after fault removal is a new intent; the same durable token can legitimately have more than three total lifetime attempts. It is not an infinite automatic retry loop.

The read-only evidence verifier checks dispatch bounds, monotonically nondecreasing generations among successful physical-write returns within each process, the twelve latest-snapshot checkpoints, exact restart markers and final cleanup. Raw physical read-back after successful resets is required as well, rather than relying only on journal overlay reads.

Fairness is established for this finite rotated nine-key workload: healthy keys progress amid failures, refused keys retain data and later get service, and each failed intent stops after its bounded attempts. It is not a guarantee under unlimited arrivals, non-retrying callers, permanently hung I/O or arbitrary parallel writers.

## 4. Existing viewers and lifecycle boundaries

The two unaffected remote menus and their original lease deadlines are checked throughout the active phases. Every 80 ticks the fixture performs a production guarded withdrawal and return, validating exact item identity/count. There are **64 successful withdraw-and-return pairs per lane** across the active processes. No mutating player command is blindly retried.

The new stress run does not add another unsafe-landing visitor scenario. It uses normal initial returns and preserves the previous unsafe-return/client coverage unchanged. It does not establish eight actual recovering clients under simultaneous storage overload.

Four server processes are used per lane:

1. **Seed:** six rounds, then normal shutdown with the newest failure snapshots still pending and the two unrelated viewers active. The next boot finds nine quarantined keys. Required snapshots are retained; shutdown does not claim to have repaired them. The engine ticket/queue audit still closes empty.
2. **Resume:** the newest records and original identities are checked with targets initially unloaded. Seven write faults are re-armed in the new worker to model continuing storage failure. Each key receives three attempts. After explicit fault clearance, the server is forcibly terminated after a repaired region write/flush but before recording its journal repair proof.
3. **After-repair-cut:** another process repeats the complete snapshot repair, not a deposit/withdrawal, finishes explicit resets, and runs the remaining six rounds. The final round is cut after durable reset publication but before acknowledgement.
4. **Final:** startup sees exactly eight still-quarantined records because the first reset had durably completed. It completes the remaining explicit resets, reads primary region data, loads the physical chests and verifies **117 latest named diamonds, 34 unrelated emeralds, all eight original rooms and all 32 chest identities**. Latest sample generations are 26–28. Other checked slots remain empty.

Final closure plus 650 drainage ticks reaches zero pocket-cache entries, pending unloads, engine callbacks, Anya-owned ticket requests, pending/in-flight repair work and unfinished return records. Both clean stops retain the original flush/close behavior. Forced cuts deliberately skip it and are followed by independent recovery.

The persistent state being tested is the newest journal data and quarantine. The injected failure flags are deliberately reapplied after restart; this is not evidence that a real disk fault persisted or was repaired by a flag change.

## 5. Backlog and memory over the bounded run

The journal retained exactly **16 latest records** throughout the measured churn, not a growing record per submission. Payload rose only from **200,010 to 200,019 bytes**, consistent with longer version strings. At most nine were pending and at most eight repair intents were queued. Each round drained the retry intents before its next explicit repair phase. When storage was allowed to succeed, latest proofs and resets were completed rather than discarding required work.

This is small, bounded retention: roughly 195 KiB of payload, far below the existing 64-MiB quota. Neither full-journal behavior nor physical disk exhaustion is newly exercised. Previously verified full/corrupt/no-durable-copy cases remain separate evidence.

| Sampled resource | Forward order | Reverse order |
|---|---:|---:|
| Maximum server RSS across the four processes | 894.2 MiB | 848.6 MiB |
| Maximum used heap during churn samples | 568.5 MiB | 513.5 MiB |
| Maximum committed heap during churn samples | 602 MiB | 544 MiB |
| Lowest sampled host-available memory | 14,117.4 MiB | 14,195.1 MiB |
| Sum of last per-process elapsed samples | 238.1 s | 229.2 s |

Within the first six rounds, committed heap remains 602 MiB on the forward runner and 544 MiB on the reverse runner. Used heap rises and falls with collection rather than records accumulating per round. After the repair cut, the fresh JVM has its own heap/collection history; memory across those restarts is not one continuous-retention measurement. This is not a live-object histogram, long-soak leak proof or claim about peak resident memory on Anya's pack.

The final PASS marker's counters are process-local. Its zero transaction/queue-peak counters describe the short last process, not the full run. The per-process CSVs and log aggregation establish 64 transaction pairs and an eight-intent peak over the full lane.

## 6. Tick-cost distributions and measurement limits

These measurements describe the new workload; there is no algorithm change or controlled performance comparison against a different candidate. Both key-order runs include instrumentation, explicit fault exceptions, journal flushes and fixture control.

| Measured churn-phase event times | Median | p95 | p99 | Maximum |
|---|---:|---:|---:|---:|
| Forward, first six rounds plus initial return/view setup | 1.587 ms | 9.971 ms | 18.164 ms | 60.715 ms |
| Reverse, first six rounds plus initial return/view setup | 1.639 ms | 9.053 ms | 16.362 ms | 50.973 ms |
| Forward, post-cut repair/reset plus last six rounds | 1.339 ms | 2.808 ms | 13.710 ms | 172.505 ms |
| Reverse, post-cut repair/reset plus last six rounds | 1.604 ms | 4.065 ms | 14.166 ms | 68.104 ms |

The 172.5-ms forward maximum occurs at restart-observer setup, not in a specifically attributed journal worker operation. The maximum recorded event across all phases, including earlier preparation, is 172.5 ms forward and 141.7 ms reverse. No claim that all pauses are fixed is made.

The timing bracket excludes the new lowest-priority post-tick fixture actions, including direct snapshot construction/submission, test transactions, some checks and final CSV output. It includes engine/repair-service work earlier in the bracket. Process RSS captures the whole running JVM. These event percentiles are therefore **not full end-to-end tick cost or FPS/TPS**, and the controller has not measured every allocation or OS stall. Raw per-round and per-process data are retained, with linear percentile interpolation.

Forced `Runtime.halt` can leave a few final CSV rows buffered. Separately flushed events/cut markers and independent restart assertions establish those final boundaries; the report does not infer their completion solely from the last periodic row.

## 7. Limits, failures and preservation

Forward runner: AMD EPYC 7763; reverse runner: AMD EPYC 9V74. Both expose four guest logical CPUs and approximately 15.61 GiB RAM under Microsoft virtualization, Linux 7.0.0-1019-azure. Temurin 21.0.13+9, Minecraft 1.21.1 / NeoForge 21.1.249, Xms256m/Xmx2048m, G1, ergonomic processor count two, view distance three/simulation distance two, flat seed 860612. OS caches are not cleared; worlds are new per lane and reused only for deliberate restarts. No actual clients or extra full-pack dependencies run in these fixture lanes.

Predefined limits: 480 seconds per process, 8,000 scenario ticks, 1,600 ticks per phase, five seconds for the worker-only gate, 92% used heap, 15,000 cache entries, 4 GiB sampled server RSS and at least 2 GiB available host memory. No final resource or time guard fired. The worker gates were held for one observed server tick, not five seconds. Cache/request peaks during setup were 8,381/136, falling to 1,800/34 while the two unrelated remotes remained active, then zero at final cleanup. These metrics are different units, not interchangeable chunk counts.

### Initial failed evidence is retained

Initial source `86d6817b0b054ee7302af2a63c83afb61d2e4bd8`, workflow **44007317184**, compiled both lanes but failed their first post-fault repair phase. The new audit incorrectly limited three attempts across a token's entire lifetime, including a later explicit repair after fault removal. It threw at the store-submission hook after earlier accounting had run, leaving the fixture unable to finish. Both lanes hit the bounded phase deadline.

The correction reads the actual retry intent's attempt field and checks 1–3 attempts for that intent, while retaining the one-dispatch-per-tick assertion and logging total token attempts separately. No journal, storage, permission, item, generation or cleanup assertion was loosened. No previously existing code changed, and no mutating transfer was retried to obtain a green result. Failed artifacts remain separate and are not pooled into successful timing data.

## 8. Achieved boundary and remaining blockers

The new evidence demonstrates latest-snapshot retention and final physical recovery through the **tested** same-worker queued/executing failure overlaps, finite repeated saturation, explicit recovery requests, persistent quarantine across restarts and selected repair/reset cuts. It does not provide a history of every intermediate snapshot, eliminate ordinary same-key coalescing or make a save submission durable at submission time.

A newest edit that acquires neither a durable journal copy nor a durable primary copy still cannot be recovered. This phase does not repeat or overturn that earlier negative test. Primary region, player inventory, ledger and POI files remain non-atomic. Complete chunk replay is not a cross-file inventory transaction. Physical filesystem failure can destroy both copies, and permanently hung I/O can prevent flush/shutdown completion.

No production-blocking storage defect was newly observed in these final cases. The remaining requirements include healthy-execution race orderings beyond this injected failure overlap, actual network clients under sustained fault churn, more keys/longer saturation, uncontrolled live direct writers, operator-facing inspection/repair/reset and retention management, and supported filesystem behavior beyond this tested environment. Fresh permission/occupancy decisions and existing safe return protections remain unchanged.

**No production integration or replacement artifact is warranted here.** Saved formats, all previous source, inventories, artwork/UI, gates, damage suppression and non-live preview remain unchanged. Live see-through portals and unrestricted machinery/container support remain unimplemented. Nothing is needed from Anya for this completed bounded validation.

Primary run: https://github.com/WeepingAngeIs/zSortange/actions/runs/44007662967
Tested source: https://github.com/WeepingAngeIs/zSortange/commit/aca97511e49a7355857f6388c4dd6f5cd799cdd9
Initial failed evidence: https://github.com/WeepingAngeIs/zSortange/actions/runs/44007317184

The evidence kit includes exact source, both final orders, both initial failures, raw logs/metrics, archive digests and read-only analysis/verification scripts. It contains no executable JARs, native libraries, player worlds or decompiled engine source.
