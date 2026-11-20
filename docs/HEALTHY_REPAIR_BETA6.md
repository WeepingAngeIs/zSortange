# Beta.6: healthy executing-repair and explicit-reset ordering

**Both focused final jobs passed on the same executable source. Four healthy repair-write boundaries preserve the newest version; two reset boundaries exposed and now verify a narrow experimental reset fence. This is not production integration. The playable beta.6 JAR is unchanged; no replacement is required.**

All runtime work used disposable GitHub servers. No user profile, installed JAR, Java arguments, settings, real save, PC or live server was accessed or changed. No force push or portal installation occurred.

## Exact checkpoint

Starting report: `97cb6072057d851e2163e12badc7495f2c1d38ff`.

**Tested executable source:** `18760a332b24c589d539cb1c486e4f21b232d654`, branch `feature/elemental-pockets`.

**Targeted workflow:** `44014043194`, Healthy repair write proof and reset ordering.

| Lane | Job ID | Result |
|---|---:|---|
| Four physical write/flush/proof boundaries plus two restarts | 133807594582 | Passed |
| Two executing-reset boundaries plus two restarts | 133807594945 | Passed |

The experimental reset fix itself is commit `236f8dc1c3cbf87460266d922c7af5ff371b548a`. The final commit extends this narrow workflow's trigger to the experimental repair source. Automatically triggered build/room/isolation jobs are not substituted for this result or claimed as new race coverage. The completed churn suite was not explicitly replayed.

SHA-256:
- Unchanged playable beta.6: `5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11`.
- Fixed experimental repair module: `4de389234439e4176f5ea1c737281b54796b92527cb831119b063901877bcae4`.
- Ordering fixture plus experimental modules: `0e39ddb14b98ed7ed44cb2b87ccd98b93c96e7e35aeb2e4e0b91f112dc12be96`.
- Exact tested source ZIP: `3f52350cbf44cc6b984a844eeb14517fb5ccd896f9dba12d825aa06fd9a6dac6`.

Both final archives agree on these hashes. Of all existing executable files, only `src/repairProbe/java/dev/anya/storage/repair/RepairService.java` changes, confined to reset fencing. All 74 production files, the journal format/implementation, 11 acceptance files and 12 ticket-experiment files are unchanged. New test sources, launch wrappers and this report are separate additions. A report-only follow-up is not another tested executable.

## 1. What the original runtime failure actually meant

The original experimental reset validates an exact repaired journal token on the server thread, then queues its flush/read-back/reset on the I/O worker. A new snapshot can be captured on the server thread while that worker operation is executing. Its in-flight counter changes immediately, but its new journal token does not exist until the same worker processes the new save.

In the failed reset-before-publication sequence:
1. The old generation-one physical repair successfully writes, flushes and publishes its proof.
2. Its explicit reset pauses immediately before journal reset publication.
3. The server thread submits generation two. The in-flight guard correctly reports blocked.
4. The old reset resumes, publishes APPLIED/unlocked for its still-current token and reports success.
5. Generation two is then written successfully. Because it inherits the old unlocked state, it also finishes APPLIED/unlocked without a reset authorized for generation two.

The assertion runs only after the newer write receipt, repair-idle condition and reset future complete. It observes `state=APPLIED locked=false`. This is **not a stale intermediate read or a retry-count audit defect**. It is a real ordering defect in the unshipped reset service. The trace shows the old physical write and the newer physical write both succeeded; it does not show an older chunk overwriting the newest data.

The four repair-write/flush/proof cases already passed on the original algorithm. The reset defect reproduced again after the unrelated prior validation-observer declaration was removed from the new fixture's metadata. That isolation did not fix the ordering error.

## 2. Narrow experimental repair

Reset now captures the chunk's existing submission epoch after the original owner-thread validation. Its worker action checks the token, epoch and in-flight state before performing the original flush and read-back. After durable reset publication, an atomic epoch check defines whether that reset is still current.

If a new submission superseded it during publication, the worker retains the transient block, durably relocks the exact old record before processing the queued newer save and returns an exception. The newer snapshot inherits the retained quarantine and requires its own verified explicit reset. Failed relocking sets global failure and attempts the existing persistent fault marker; that I/O-failure branch is not injected in this healthy phase.

A reset that completes without supersession still succeeds. A genuinely later submission after the successful fence is a new operation, not retroactive cancellation of a completed reset. Original coordinate/room/owner/unloaded-target validation, exact-token checks, flush and read-back remain. No save is cancelled, evicted or replayed as a deposit/withdrawal. No world access moves off the owner thread, and the journal's on-disk format is unchanged.

The fence is process-local ordering plus durable compensation, not a new atomic cross-file commit protocol. The newer snapshot must still reach a durable copy to survive process loss. A cut during old-reset publication/compensating relock before the newer snapshot itself is durable is not tested here; the in-flight epoch is not a persistent submission journal.

## 3. Deterministic coverage and its exact scope

**Zero real Minecraft clients or network sessions run in this phase.** Each lane creates one room using one ordinary ServerPlayer fixture with an embedded packet sink, then operates on an unloaded chunk through the real engine I/O worker. The two restarts in each lane use no connected player. This is not another network-client churn or full-pack test.

Each case first injects exactly one bootstrap write failure to establish a durable locked snapshot that needs repair. The fault is then cleared. The overlap under test requires the old physical repair to return successfully and verifies that no injected failure remains armed. The healthy cases are therefore materially different from the previous failing-execution overlap.

A bounded worker-only latch pauses at one selected point. The server thread submits the newer detached complete snapshot and releases the latch. The world thread is never blocked on the latch; it has a five-second safety ceiling. Gates release within zero or one observed tick interval, not a simulated physical disk hang.

| Lane | Deterministic boundary | Verified result |
|---|---|---|
| Repair | Before old physical write | Old succeeds, then newest succeeds; latest quarantine retained |
| Repair | After old write, before flush | Same latest-version/quarantine result |
| Repair | After old flush/read-back, before proof | Same result; no stale proof can change the later token |
| Repair | After old proof, before completion | Same result |
| Reset | Before reset publication | Superseded old reset fails, exact record relocks, newest remains quarantined |
| Reset | After reset publication, before return | Same result, including the compensating persistent relock |

The original failing assertions are retained. The reset lane records two `REPAIR_RESET_SUPERSEDED` events with `relocked=true`. Normal current resets between cases continue to succeed. At each completed case, old-token service reset/repair requests and direct stale journal proof/reset operations are refused, with the latest token/state/lock unchanged. Explicit primary-region flush/read-back confirms the newest generation without using the journal overlay.

All successful physical-return generations are nondecreasing in these traces. The final repair lane reaches generation eight; the reset lane reaches generation four. Each has the same 13 diamonds with changed name/version components, not newly created stacks or inventory transfers. The fixture player's separate 11 named emeralds remain unchanged during its seed process; no player reconnect is claimed for the restart processes.

These are six exact same-worker orderings, not proof of every arbitrary scheduler interleaving, multiple independent physical writers or unrestricted live edits while quarantined. New saves are detached snapshots submitted while the target is unloaded, not gameplay writes admitted through quarantine.

## 4. Independent restart and explicit reset boundaries

Each lane uses three server processes:
1. After all its cases, the newest primary write, flush, read-back and locked journal proof complete. `Runtime.halt(77)` cuts the process with the current explicit reset intentionally pending.
2. A new server verifies the same room/cell/owner, reads the physical region before loading the destination chunk, and confirms the newest snapshot is still quarantined. Stale operations are again refused. A current-token reset then terminates the process immediately after durable reset publication but before acknowledgement.
3. Another server finds the intended reset durably applied. It checks the physical region before loading, then verifies **13 latest component-correct diamonds, the original room identity and all four physical chest identities**, with other slots/chests empty.

After inspection and 650 drainage ticks, the final assertions require no pocket cache, pending unloads, engine callbacks, Anya-owned tickets, repair work/in-flight receipts or unfinished return records. The original normal shutdown/flush path closes cleanly. Current journal records are retained, not deleted to make cleanup look empty.

The cuts do not represent physical power failure or persistent disk failure. This phase does not repeat the no-durable-copy, full/corrupt-journal, multi-key fairness or actual-client scenarios as new coverage. Their prior evidence is historical and does not make the modified reset exact-final-commit client-tested.

## 5. Resources and measurement exclusions

Repair lane: AMD EPYC 9V74; reset lane: Intel Xeon 6975P-C. Each runner exposes four guest logical CPUs and approximately 15.61/15.33 GiB RAM. Java Temurin 21.0.13+9, Minecraft 1.21.1 / NeoForge 21.1.249, Xms256m/Xmx2048m, G1, ergonomic processors two, view distance three/simulation distance two, flat seed860612 and disposable loopback-only server configuration.

| Observed resource | Repair lane | Reset lane |
|---|---:|---:|
| Peak sampled server RSS across three processes | 776.3 MiB | 803.8 MiB |
| Lowest sampled host available memory | 14,150.6 MiB | 13,907.2 MiB |
| Longest per-process final elapsed sample | 41.0 s | 40.0 s |
| Largest recorded event tick | 48.948 ms | 24.757 ms |

All processes have a 240-second ceiling and a 4-GiB sampled RSS stop, with at least2GiB host memory required. Fixture deadlines are 2,600 ticks overall and900 per phase; existing preparation heap/cache checks remain. No final guard fired.

These are acceptance-test costs, not a benchmark or before/after performance comparison. Event timing excludes lowest-priority test work, including snapshot construction/submission, some direct checks and fixture-triggered saves; worker activity is not an exclusive server-tick breakdown. RSS is sampled once per second. Forced termination leaves some periodic CSV rows buffered, and the short verify-proof process has no flushed tick rows at all. Its missing values are reported as unavailable, not zero. Separately flushed gate/cut markers and independent restart/read-back establish those boundaries. No forced GC, long soak or maximum-client stress was performed.

## 6. Retained original failures

- **44012821526 /18bbca0307378a127823b99abbbcb8f37e88f394:** both lanes failed compilation because the new fixture's drained() helper omitted an IOException declaration for a ledger read. No game scenario ran. The declaration was corrected without changing assertions.
- **44013340296 /35bc2e228d2f45fe19225deeae22d2948d0fd902:** the four repair boundaries and their restarts passed; reset-before-publication failed with the newest record APPLIED/unlocked. This supplies the primary pre-fix defect trace.
- **44013584207 /4f76d1555b39ba252e38f46e20478d9f67b227c4:** removing the prior optional validation observer and adding package verification still reproduced the same reset failure. The earlier runtime had already started successfully; this metadata cleanup is not described as a demonstrated missing-mixin failure.
- **236f8dc1c3cbf87460266d922c7af5ff371b548a:** only the experimental reset method was fenced. No runtime assertion was relaxed, no mutating transfer blindly retried, and the snapshot journal implementation/format remains unchanged.
- **44014043194 /18760a332b24c589d539cb1c486e4f21b232d654:** both final lanes pass. Only this source supplies final positive evidence for the corrected reset.

Original compile and failed-reset artifacts are retained separately with archive digests. Intermediate successes are not pooled into a claim that the original complete suite passed. This report-only follow-up does not rerun completed scenarios.

## Decision and remaining limits

**Keep the repair module experimental; no installable beta.6 replacement is issued.** The specific observed old-reset/new-submission bug is repaired and the selected healthy orderings now preserve the newest physical and journal version through the tested restarts.

The next durability boundary is the publication-to-compensation crash window and failure of the compensating lock write; neither has been fault-injected here. Broader actual-client use of the modified reset, multiple independent writers/workers, healthy races at other storage API boundaries, persistent I/O hangs and operating systems/filesystems beyond this environment remain unverified. These gaps are not reasons to weaken the current assertions or lose snapshots.

No durable latest journal copy and no durable latest primary copy still means the latest edit cannot be recovered. Submission is not durability. Player, chunk, ledger and POI files remain non-atomic, and replaying a complete chunk is not an atomic inventory transaction across those files. Loss of the filesystem can destroy both copies.

All production formats, identities, existing inventories, artwork/UI, default-off gates, damage suppression and non-live preview remain unchanged. The source-level permission, viewer-lease and recovery paths were not modified; this narrow fixture is not a fresh exhaustive validation of them. Live see-through portals remain unimplemented. No human test is needed for this bounded checkpoint.

Final run: https://github.com/WeepingAngeIs/zSortange/actions/runs/44014043194
Tested source: https://github.com/WeepingAngeIs/zSortange/commit/18760a332b24c589d539cb1c486e4f21b232d654

The evidence kit retains exact source, raw artifact archives, original failures, gate traces, read-back/reset markers, resource measurements, the narrow reset diff and read-only verification scripts. It contains no executable JARs, native libraries, player worlds or decompiled engine source.
