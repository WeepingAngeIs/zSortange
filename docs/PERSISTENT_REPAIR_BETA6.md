# Beta.6: persistent latest-snapshot repair after an asynchronous write failure

**All nine final targeted jobs passed on the same executable source. The isolated candidate recovers the newest durably journaled chunk snapshot, instead of only the preceding region-file version. It remains experimental. No replacement playable JAR is required.**

All Minecraft execution used disposable GitHub infrastructure. No installed profile, user Java arguments/settings, real save, PC or live server was accessed or changed. The production beta.6 binary, formats, identities, inventory rules, artwork/UI, default-off gates, damage suppression and non-live preview remain unchanged.

## Exact provenance

Starting report checkpoint: `0da1d592cd5081b9ae7a0597382c0664703609e4`.

**Final tested source:** `df620d79f9b199c6202d0c15f8cfff5d0ed4b269`, branch `feature/elemental-pockets`.

**Final workflow:** `43994872898`, Persistent latest-snapshot repair and quarantine, completed successfully 2026-11-17 01:30:23 UTC (November 16 in Jamaica).

| Required job | Job ID | Result |
|---|---:|---|
| Baseline-first healthy write comparison and restart | 133744334974 | Passed |
| Candidate-first reversed comparison and restart | 133744334575 | Passed |
| Ordinary write/flush failure with independent restart | 133744335041 | Passed |
| Recovery while latest edit remains unresolved, bounded retries and reset | 133744335029 | Passed |
| Journal/region/repair process cuts and clean stop | 133744334972 | Passed |
| No durable newest copy, and journal-only failure fallback | 133744334999 | Passed |
| Two actual clients: room construction and restart | 133744335151 | Passed |
| Two actual clients: cold/unsafe recovery | 133744335047 | Passed |
| Two actual clients: transitions, active shutdown and interrupted return | 133744334953 | Passed |

SHA-256:
- Unchanged playable beta.6: `5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11`.
- Separate experimental repair module: `e11659b1b7a7b1280a3675eeff34e97d1594a95d86f0b16f3319bd5e882daace`.
- Exact tested source ZIP: `a1673e1a0d9bb82de94a75e619ff258b08f7650e28af01ef980233c89e41ba0f`.

All nine downloaded artifacts agree on the source and binary hashes. Independent comparison confirms the preceding 74 production files, 10 backpressure files, eight save-budget files, 11 minimal-ownership files, 15 durable-preparation files, 12 remote-selector files and 11 acceptance files are unchanged. New code lives in a separate repair source set. A documentation-only follow-up is not a new measured executable.

## 1. What is protected, and the exact durability boundary

This is a bounded write-ahead snapshot journal for supported pocket-storage chunk columns. It is not an inventory-command log and does not replay deposits, withdrawals, purchases or crafting. The record stores a complete serialized chunk snapshot, including physical chest contents and components.

The candidate adds a separate `anya_repair_experiment` sidecar directory only in disposable worlds. It does not change the existing player, ledger, region or POI formats. The sidecar itself is new experimental data and must not be removed as a purported repair.

The sequence is:

1. On the world-owner thread, Minecraft produces the chunk snapshot and submits the ordinary save. Supported transactions touching that chunk are blocked from submission until confirmation, with a generation counter invalidating plans prepared before the state change.
2. On the real region I/O worker, detached data is encoded into a bounded, checksummed record with a journal binding, coordinates and fresh token. A temporary file is forced, atomically renamed, and its parent directory is forced. Only after this publication is the newest journal copy considered available for recovery.
3. The original region write runs, followed by its force/flush and exact NBT read-back. A separate durable completion proof then changes the journal state to APPLIED. Errors remain errors, not successful save acknowledgements.
4. If writing the region or its flush fails, the PENDING/locked record survives. A separate repair request may restore that exact full snapshot through the original storage worker. Successful repair leaves a persistent REPAIRED lock; it does not silently resume writers.
5. Explicit reset requires the same current token, unloaded target, matching room identity and another original-store flush/read-back. Only then is the unlocked proof durably published.

The journal retains the latest applied snapshot rather than deleting its only copy during state transitions. It has no inventory-command replay and does not remove engine-owned callbacks. Minecraft can mark its in-memory chunk clean during the ordinary asynchronous save; the outstanding logical obligation is retained by the journal, not by pretending that engine dirty flag never changed.

**Conditional guarantee:** once the newest supported snapshot's journal publication has completed, the tested primary write failures and process cuts can restore that snapshot on restart. This is not a guarantee for edits not yet serialized/journaled, simultaneous loss of both copies, arbitrary hardware failure or atomicity across different files.

Java's file-force and atomic-move operations have filesystem/provider limitations. The prototype refuses unsupported publication rather than silently falling back to a weaker move. It was exercised on the Linux GitHub environment, not Windows, network filesystems or physical power cuts. The journal and primary files share the same filesystem; this is not an independent-disk backup.

## 2. Existing active writers and safe repair/reset

Supported transaction commits are checked at the current chunk state. Both an already-prepared withdrawal and a newly constructed withdrawal after failure were refused without moving items. Original permissions, claim checks, room identity and transaction conservation checks remain in place. Unrelated authorized viewers continue operating and retain their original lease deadlines.

New entry to an affected room and supported construction are refused. Existing visitors can still use return recovery. A pending latest snapshot can supply read-only chunk-load data so recovery does not require using the older region contents; repair validation still requires an unloaded target before replacing primary data.

Repair checks exact snapshot token, coordinates, current PocketId, owner and room bounds, and rejects loaded chunks or writes still in flight. It does not overwrite an active live container to make the disk match. Reset refuses pending data, wrong tokens, missing repair proof and global journal faults. These are internal experimental APIs exercised by fixtures, not a finished operator menu or player-accessible repair command.

Quarantine covers the known transaction/build paths. It is not a universal barrier against arbitrary mods directly mutating block entities. Full-pack movers, unsupported containers and automation remain outside the approved subset.

## 3. Newest edit recovered after actual worker failure

The fixture prepares eight rooms, establishes a durable version-zero sample, then changes the first chest in one room to a newly named version-one snapshot. All rooms together contain 104 diamonds, 13 per sample, and 32 physical chests. It invokes Minecraft's ordinary `ChunkMap.save` path, observes the actual IOWorker receipt and injects an exception in the real region-storage worker.

| Case | Restart result |
|---|---|
| Previous tested stack, journal disabled, newest region write failed | Original room/chest identities retained, but target is previous version zero. |
| Journal enabled, newest region write failed | Persistent latest version-one snapshot repaired, read back, explicitly reset, then verified from the physical region. |
| Journal enabled, region flush failed | Latest version one retained by the journal and repaired after restart. |
| Journal and primary write both fail before the newest copy is durable | Latest version one is not recovered. Previous version zero remains, with a persistent fault and reset refusal. |
| Secondary snapshot encoding fails, primary storage works | Original primary save/flush still runs; restart verifies version one, but the global journal fault remains visible and cannot be blindly reset. |

The failing seed processes are terminated with `Runtime.halt(77)` after the affected chunk has unloaded and without a clean shutdown saving the sample. The candidate then validates/repairs while the destination is absent. Inspection occurs after reset, reading the repaired physical chunk rather than merely showing an in-memory journal overlay.

Checks retain 104 items, all eight room/cell/owner identities and all 32 physical storage identities, with the remaining checked slots empty. The edit used to distinguish old/new snapshots is a changed item name/version with the same count. It demonstrates newest component-bearing snapshot recovery and no duplication in this fixture. It does not establish a transaction spanning a real player's inventory and a chest as crash-atomic.

## 4. Recovery while the newest write remains unresolved

The new combined scenario closes the previous coverage gap: six visitors occupy the affected room while its latest journal is PENDING and its primary writes continue to fail. The home and fallback landings are made unsafe in the disposable fixture. Two unrelated remote viewers remain active.

Observed recovery attempts occur at one per tick, with exactly 100 ticks between each guest's attempts. The final run records attempts at ticks 397–402, 497–502, 597–602 and 697–702, all with `latestPending=true`. After repairing the landing at tick 646, every guest returns by tick 702, a 56-tick interval. Existing-view contents, guarded withdrawal-and-return operations and original deadlines remain valid throughout.

Only after visitors have returned and the target unloads can repair start. Three real worker repair attempts fail at ticks 705, 726 and 767. The scheduled retries stop; the full durable snapshot and lock remain. The delays include 20 and 40 ticks after observing the preceding failure, not an unbounded busy loop. No transfer intent is retried.

After the fixture clears the injected physical-write fault, an explicit new repair request succeeds, followed by a separately verified reset. Reopening the production remote view displays version one. Another forced termination and separate restart retain version one, all checked inventories/identities and empty final ticket/callback/cache state.

The service permits at most eight queued repair intents and dispatches at most one new attempt per tick. Runtime failure contention here involves one failed chunk and six recovering players, not eight simultaneously failing repair keys. Multi-key fairness under persistent storage saturation remains untested.

**Coverage distinction:** these fault/combined-recovery scenarios use ordinary server-player fixtures with simulated packet sinks, not six real Minecraft clients. The separate real-client regression coverage is below.

## 5. Persistent state, selected cuts and limits when storage fails

Three selected forced-termination boundaries were exercised through the original storage path:
- after the latest journal is forced/published but before the region write;
- after the region has been forced/read back but before journal completion proof;
- during repair after the region write/flush, before repair proof.

Independent server launches retain the unfinished token and recover version one. Repeating the full snapshot after the second cut remains idempotent in the test, without replaying a mutation. A normal shutdown with a failed, unloaded target also preserves its repair record and original engine cleanup obligations, followed by successful restart repair.

Stopping prevents new retry scheduling; it does not cancel already submitted worker writes or discard the retained journal. The original worker close/flush can still block. The test does not demonstrate safe interruption of a physically hung disk operation, nor a clean stop during every possible journal/region substep.

A persisted global fault survives restart in the dual-failure and journal-only cases. In the deliberately total-copy-failure case, the recovery API refuses to manufacture version one or clear the fault. A volatile emergency copy is not counted as durable evidence. If the device also cannot persist the failure marker itself, even the marker's survival cannot be promised.

The journal core's 27 checks cover state transitions, stale reset, checksum/binding mismatch, 64-record and per-record size limits, and seven selected publication-error cut points. A leftover incomplete temporary file causes fail-closed startup instead of silent promotion or deletion. These are filesystem/API tests, not power-loss tests or a comprehensive fault model. The binding detects records copied into a mismatched manifest; copying an entire journal is not cryptographic proof of the target world's identity. Runtime repair separately validates its room identifiers and location.

## 6. Bounded resources and comparison to the tested stack

Limits are two MiB per retained snapshot, 64 latest chunk records and 64 MiB of total retained payload. Required pending records are not evicted to meet a quota. Exceeding a limit refuses journal protection, retains failure state and still attempts the original primary save; no secondary feature should suppress that original obligation. A global hard fault requires inspection; there is no automatic unsafe reset.

The healthy comparison uses the preceding preparation/minimal-unload/save-budget/backpressure stack on both sides, with only the journal flag changed. It comprises two reversed-order pairs on separate AMD EPYC 9V45 runners, four guest logical CPUs each, Java 21/NeoForge 21.1.249, a two-GiB server heap, view distance three and simulation distance two. The efficient remote-ticket module is absent in these fixture measurements.

Each fresh world prepares eight rooms with eight fixture players, then performs 12 sequential ordinary sample writes with receipt and force confirmation. The fixture polls on ticks, so the roughly 100-ms results contain strong tick/polling granularity. It does not generate the previous 3,600-tick arrival workload or establish full-load throughput.

| Paired measurement | Baseline first | Journal second | Journal first | Baseline second |
|---|---:|---:|---:|---:|
| Samples | 12 | 12 | 12 | 12 |
| Median observed confirmation | 99.643 ms | 100.709 ms | 100.169 ms | 99.764 ms |
| p95 observed confirmation | 130.367 ms | 124.681 ms | 147.809 ms | 106.621 ms |
| Maximum observed confirmation | 150.572 ms | 128.071 ms | 148.336 ms | 112.023 ms |
| Whole-case sampled peak RSS | 861.5 MiB | 916.1 MiB | 949.0 MiB | 884.2 MiB |
| Peak used heap | 528.9 MiB | 583.0 MiB | 600.9 MiB | 548.1 MiB |
| Peak committed heap | 567 MiB | 620 MiB | 642 MiB | 582 MiB |

The journal adds real encoding, checksumming, atomic publication, extra force calls and read-back. These small, sequential cases do not resolve its per-save I/O overhead at saturation; RSS is higher in both pairs. No speedup, lower-memory or FPS/TPS claim is supported.

Measured journal payload peaks around 199,985 bytes across 16 chunk records. The configured quotas were not filled in Minecraft runtime. RAM pressure is not inferred from record count: detached NBT, in-flight futures and JVM/native memory also consume resources. Capacity exhaustion and corruption refusal have core-level evidence, not a saturated full-pack run.

Fixture processes enforce a 4,200-tick ceiling and 300-second wall timeout, sampled four-GiB RSS ceiling and two-GiB host-available minimum, plus inherited cache/heap monitoring. No final limit fired. Across fixture lanes the largest sampled RSS was approximately 1,020.7 MiB; the longest was 270.3 seconds; minimum host-available memory exceeded 13.59 GiB. Each healthy seed measurement ran about 60–62 seconds including setup/drain. Sampling is once per second, not a continuous hardware peak. No OS-cache clearing or forced GC was used.

## 7. Exact-final-candidate actual-client regressions

The final workflow separately ran three jobs with two genuine Minecraft client JVMs over localhost TCP, loading the unchanged beta.6 JAR and exact final repair module. No FakePlayers replace these client processes. Private native extraction and bounded process supervision remain. The client runtime also loads the unchanged efficient remote-ticket experiment and the isolated OPAC/Carry On dependencies; fixture load lanes do not.

- Room construction, delegated permissions, protected boundaries, added physical storage, one conserved deposit, disconnect/reconnect and separate-server restart passed.
- Genuinely unloaded home/fallback recovery, both-unsafe retries, unresolved shutdown/restart and automatic return after repairing a landing passed.
- Remote/visit changes, overlapping/shared viewers and independent deadlines, revoked stale actions, real client-process termination/reconnect, active-session shutdown and the selected interrupted-return cut passed. The active shutdown still removes 34 requests to zero actual engine-owned tickets. Named samples and original room/cell/owner identities survive the restart.

These are healthy-path compatibility and lifecycle regressions with the repair hook loaded. The injected failed-write, read-only transition and explicit repair/reset are exercised by server fixtures, not by these real-client fault sessions. No human, WAN, full-pack, shader, grave or arbitrary inventory-mover certification is claimed. Exact client policy assertions were not relaxed and mutating item transfers were not blindly retried.

## 8. Earlier checkpoints and the required safety correction

The initial compile-only run `43990913640` at `82a916bf10a372053ddeb79ac748e716fea69173` failed with protected RegionFileStorage.write access and a Retry field mistakenly called as a method. The pure journal checks passed. A pinned invoker and field-access correction repaired the experimental adapter; this failure is preserved in the evidence kit.

The first complete matrix `43993079159` at `0266769922abebcd220c5e34a58755ce0a343fbe` passed all nine jobs. Subsequent source review found that snapshot encoding occurred outside the save-obligation catch. An oversized/encoding failure could therefore bypass the fallback primary-save attempt. It was not acceptable to ship that despite the green matrix.

The final correction moves encoding inside the guarded path and adds a journal-only encoding-failure fixture. It proves the original primary write/flush still occurs, version one survives forced termination/restart, and the lack of journal health is not hidden by reopening writes. The final nine-job workflow reruns the changed candidate's relevant tests. No preceding success is used as a substitute for this final executable.

The new journal-only injector emulates the encoding-error path, not an actual multi-megabyte Minecraft chunk. The pure size-limit test and runtime fallback test are distinct evidence. Earlier failed logs and the pre-correction successful source/provenance are retained separately.

## 9. Decision, achieved boundary and production blockers

**Achieved:** recovery of the newest supported full chunk snapshot after an ordinary asynchronous region write/flush failure, when its journal publication succeeded; persistent affected-writer quarantine; explicit verified repair/reset; recovery of players while that latest write remains unresolved; bounded retries; selected restart-cut idempotence; unchanged exact-candidate client lifecycle behavior.

**Not achieved:** durability before the snapshot has reached the journal, atomic transactions across player inventory and storage, protection against losing the whole filesystem, automatic repair of a corrupt/full journal, a user-facing operator repair workflow, safe handling of arbitrary mod writers, multi-key failure fairness at capacity, long soaks or storage-hang shutdown guarantees.

The critical remaining boundary is between a gameplay mutation and first durable snapshot publication, plus consistency across player/chest/ledger/POI files. A complete snapshot journal must not be advertised as solving that transaction boundary. The test restores the latest named item snapshot, not a blindly replayed transfer, but an independent player-file rollback can still create loss or duplication outside this guarantee.

Keep this experimental and leave installed beta.6 unchanged. A narrowly useful next independent phase is a real-client fault/quarantine/reset scenario plus concurrent latest-snapshot submissions on multiple chunk keys, with explicit transaction-boundary limitations. Production integration should wait for that evidence and a durable operator/retention policy. Nothing is required from Anya for the independent work.

No portal backend was installed. Live see-through rendering and unrestricted room containers/machinery remain outside this milestone; the non-live preview is unchanged.

Primary workflow: https://github.com/WeepingAngeIs/zSortange/actions/runs/43994872898
Tested source: https://github.com/WeepingAngeIs/zSortange/commit/df620d79f9b199c6202d0c15f8cfff5d0ed4b269
Reference API contracts consulted: Java 21 FileChannel.force and Files.move (ATOMIC_MOVE).

The evidence kit retains raw logs/CSVs/screenshots, exact source, hashes, read-only analysis and the failed compile evidence. It contains no executable JARs, native libraries, player worlds or decompiled engine sources. The code remains available in the repository for inspection; the kit is not an installation package.
