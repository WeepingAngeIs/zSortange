# Beta.6: reset-publication crash and persistence-failure boundary

**The targeted workflow passed all six jobs, including five disposable Minecraft server scenarios and a small filesystem suite. The tests reproduced a real pre-fix quarantine-reopening defect. A narrow experimental reset-intent sidecar closes the tested gap. No replacement playable JAR is required.**

No installed profile, user Java arguments/settings, real save, user PC or live server was accessed or changed. All Minecraft runtime work used disposable GitHub environments. No force push, production integration or portal installation occurred.

## 1. Exact checkpoint and evidence

Starting branch/report: `1b546fe13d0dee7f5b6610c46846f221e54ceee8` on `feature/elemental-pockets`.

**Final tested executable source:** `290ccf2dc614c8ecef9209aae9763095b4bedfb3`.

**Targeted workflow:** `44022524557`, Reset publication crash and persistence boundaries.

| Lane | Job | Result |
|---|---:|---|
| Filesystem intent/cut/checksum/capacity checks | 133835065559 | Passed, 98 assertions |
| Process cut after unlocked projection, before compensation | 133835065759 | Passed |
| Compensating relock persistence failure | 133835065685 | Passed |
| Relock and fault-marker persistence both fail | 133835065651 | Passed |
| Newest journal durable before newest primary write | 133835065684 | Passed, including third process |
| Valid current reset without a newer submission | 133835065576 | Passed |

The same-source healthy-ordering regression `44022524547` also passed both jobs: repair `133835065466`, reset `133835065058`. This checks the previous successful-write/proof/reset boundaries against the modified implementation, not a substitute for the new crash/fault tests. Existing workflow triggers also launch broader regression suites when repair source changes; they are supporting regressions, not newly designed gap coverage. The completed churn scenario was not explicitly replayed.

SHA-256:
- Unchanged playable beta.6: `5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11`.
- Modified experimental repair module: `0e18934cca7ff47184cadcf4b504466902054a415f615002bb45bfaa95616c17`.
- New reset-gap fixture with experimental modules: `ea8a26b2c2cb4528736dde414f8090244c43a474f971fb96c0244657bec10f4c`.
- Exact tested source ZIP: `f9dac12c5d00799afaa0bc63706b42be01f336e9c95d8935aa1235025d1231c8`.

All final gap artifacts agree on source and binary hashes. Only two previously existing experimental Java files changed: `RepairService.java` and `SnapshotJournal.java`. All 74 production source/resource files, 11 acceptance files and 12 ticket-experiment files remain byte-identical to the reconciled beta.6 source. The journal snapshot `.wal` encoding/version and primary region/player/ledger/POI formats are unchanged. This phase adds an experimental `.reset` sidecar and test code. It is not a claim that older experimental binaries understand that new sidecar.

## 2. Reproduced defect, separated from the first fixture error

Initial fixture commit `69fb3b641f4175b02442817388b11659b13ee0e4`, run `44020892748`, compiled but all three cases stopped during setup: `Storage identity tag not found`. The fixture serialized each empty chest before invoking its lazy storage-identity allocator. No reset race ran. Commit `c5e6ef7c24ca1302a559f28b991f31bdb27e386a` calls the allocator first and verifies the exact serialized UUID. This preserves and strengthens identity assertions, rather than relaxing them.

On that corrected fixture, **run `44021437983` genuinely reproduced the safety defect** in the cut and double-failure lanes. The relock-failure-only lane already passed because its separate fault marker persisted.

The tested sequence is precise:
1. A single bootstrap primary-write failure creates a durable locked generation-one snapshot.
2. That fault is disabled. An ordinary repair successfully restores generation one, flushes, reads back and records proof.
3. An explicit reset is paused on the I/O worker after `SnapshotJournal.reset` durably publishes APPLIED/unlocked, before the service checks supersession and compensates.
4. The server thread submits detached generation two. Its in-flight guard blocks current writers, but the same I/O worker has not journaled or physically written it yet.
5. Either the process halts immediately, or compensation throws before the lock write, followed by a failed fault-marker write. The queued generation-two worker is held before journal publication so the exact no-durable-newest boundary is retained.
6. A new server reads the primary region directly before loading the destination chunk and separately opens the journal.

Pre-fix cut and double-failure observations were:

```text
primary=1 journal=1 state=APPLIED recordLocked=false
serviceBlocked=false fatal=false newSubmissionRecovered=false
```

The old durable data surviving is expected: generation two had no durable copy. **Reopening the uncertain reset without quarantine is the defect.** It was observed in a new process, not a stale in-memory read. The baseline relock-only failure instead recorded `serviceBlocked=true fatal=true` because its persistent global fault marker survived.

All original failure artifacts and the one passing pre-fix control are retained separately. No failed result is pooled into the final positive result.

## 3. Narrow correction: durable intent before an unlocked projection

Reset now has two distinct persistent boundaries:

```text
Exact repaired token + owner-thread validation
  -> durable reset-pending intent
  -> original primary flush and read-back
  -> unlocked journal projection, still effectively quarantined
  -> submission-epoch fence
  -> durable reset-completed marker
  -> success acknowledgement
```

A checksum-protected, world-bound marker is stored at the journal's own numeric chunk-key path. It is forced, atomically renamed and followed by the same directory-force operation used by the existing snapshot journal. The marker is **81 bytes** and contains no duplicate item payload. At most 64 retained keys are accepted; completed markers are retained rather than deleted. Their raw fixed-size total is at most 5,184 bytes, excluding filesystem allocation, temporary files and metadata.

A pending marker overrides an APPLIED/unlocked `.wal` record for the service's effective blocked state. This distinction is deliberate: the old metadata projection may say unlocked, but writers, room access checks and restart quarantine consult the authoritative pending marker too. The marker is published before that projection, so the tested crash does not need a successful later write to discover uncertainty.

If a newer snapshot supersedes the reset, the prior epoch fence and compensating relock still execute. The durable pending marker remains authoritative even if relocking and the attempt to persist the global fault both fail. New snapshots inherit that pending quarantine. If no supersession occurred, a separate exact-token completion publication ends the pending state. A valid current reset still reopens normally.

Failure to publish the intent prevents the unlock path. Failure to complete it remains blocked or globally failed. Both paths attempt the existing persistent fault marker without suppressing the original exception. Incomplete temporary publications are retained and refuse journal startup. Checksum/path/world/record mismatches also refuse startup; they are not silently repaired by deleting evidence.

No ordinary region save, queued snapshot, owned callback, flush or shutdown obligation is removed. The primary-save fallback in `RepairService.write` is unchanged. All world access stays on its existing owner-thread path, while detached journal/storage I/O uses the original worker. No mutating inventory transaction is replayed.

The original `.wal` format is unchanged; the new sidecar is additive experimental metadata. **Downgrading to an older experiment that ignores `.reset` is not safe or supported.** Losing/deleting/independently rolling back this marker is outside the tested persistence guarantee. This is not a new atomic commit spanning all Minecraft files.

## 4. Final independent restart outcomes

The original cut, relock-failure and double-failure assertions are unchanged after the fixture identity correction.

| Case | Durable data at first restart | Final verified behavior |
|---|---|---|
| Cut in publication-to-compensation gap | Primary 1, journal 1 | Effective quarantine survives; old proof cannot be blindly reset |
| Relock fails, global fault marker succeeds | Primary 1, journal 1 | Global fault plus target quarantine survive |
| Both relock and global fault marker fail | Primary 1, journal 1 | Target remains quarantined via its earlier durable intent; global fault itself is not persisted |
| Newest journal published, primary write not begun | Primary 1, journal 2 | Quarantined newest snapshot repairs primary to 2; explicit current reset succeeds; a third process verifies both copies at 2 and correct reopening |
| Valid current reset, no newer submission | Primary 1, journal 1 | Acknowledged completed reset remains usable after restart |

In the two fixed defect cases, the post-restart record still has APPLIED/unlocked projection, but `serviceBlocked=true`. This is not a claim that an absent generation-two payload was recovered. The pending record survives intentionally; it is not erased to make cleanup look empty.

Each case verifies **13 component-correct named diamonds, one original room/cell/owner identity and all four physical chest identities**, with no additional items in other checked slots/chests. The names distinguish versions while the count stays 13; this is not a cross-file deposit/withdrawal atomicity test.

Raw reads use the primary region store, not the journal overlay, before loading the destination. The latest-durable case first confirms primary one/journal two, then verifies repaired primary two and an explicit reset, cuts again, and checks both durable copies on another launch. The fixture oracle supplies expected values only; storage recovery does not consume it.

The five cases use **11 server processes in total**, with one server-player fixture during each seed and no connected player on restart. There are **zero real Minecraft clients/network sessions in the targeted gap test**. Latches are worker-only with a five-second ceiling. Observed holds span one or two recorded tick intervals; the game-world thread is never paused on them.

The persistence failures are injected `IOException`s at the relock/fault-marker method entries, before any bytes are written, not actual disk exhaustion. This deliberately prevents an orphan temporary file from accidentally masking the double-failure defect. Process termination is `Runtime.halt(77)`, not a power cut to physical storage.

After the final verification phase and 650 drainage ticks, exact engine-owned tickets, destination cache entries, pending unloads, callbacks, active repair work/in-flight receipts and unfinished return records are empty. Required snapshots and uncertainty markers remain stored. Normal final shutdown retains its original flush/close behavior.

## 5. Additional small filesystem and ordering regressions

The 98-assertion filesystem suite covers durable pending intent across reopening, newer-payload retention, stale completion-token rejection, valid completion, and nine selected failures around file force/rename/publication for intent, unlocked projection and final completion. It also checks checksum corruption without deletion, a marker whose required snapshot is missing, and refusal of a 65th snapshot while retaining 64 protected records.

These checks use temporary files and a 128-MiB Java heap, not Minecraft clients or simulated physical power loss. Incomplete publications refuse startup rather than claiming recovery. In particular, corruption tests do not make a corrupt newest payload readable.

The exact-source healthy-ordering suite retains all six prior successful-write/proof/reset assertions and their independent restarts. Its repair and reset jobs both passed. The existing `reset-published` diagnostic cut now denotes the final durable reset-completion marker in the service path; the intermediate `.wal` publication is explicitly named `reset-projection`. The new gap fixture separately pauses at `SnapshotJournal.reset` return, so it still exercises that intermediate unlocked projection rather than moving its cut past the risk.

Automatically triggered client/lifecycle and multi-key suites are regression evidence, not real-client injections of this new compensation gap. Their existing scenarios must not be substituted for the new fixture read-backs or described as fresh maximum-load profiling. Runtime artifacts and checksums remain separate.

## 6. Resources and exclusions

The targeted runners exposed four guest logical CPUs on Intel Xeon 6975P-C, AMD EPYC 9V45 or EPYC 9V74 hosts. Java Temurin 21.0.13+9, Minecraft 1.21.1, NeoForge 21.1.249, Xms256m/Xmx2048m, G1, ergonomic processor count two, dedicated-server view three/simulation two, generated flat disposable world and loopback server binding. No user configuration was read or edited.

Peak sampled RSS across all 11 processes was **1,018.5 MiB**. The lowest sampled host-available memory was **13,780.5 MiB**. The longest final elapsed resource sample for a process was **41.022 seconds**, against a 200-second ceiling. The launcher retains a 4-GiB RSS stop and 2-GiB minimum host availability; fixture ceilings remain 2,600 ticks overall and 900 per phase. No final guard fired.

The largest recorded event bracket was **65.36 ms**, but it is not an end-to-end tick or journal-latency benchmark. Lowest-priority fixture actions, some direct reads/submissions and output are outside that bracket. Worker I/O is not an exclusive server-thread timing measurement. RSS is sampled once per second. Forced cuts leave final buffered CSV rows unflushed; the short intermediate latest-durable repair process has no flushed tick rows, which are reported as unavailable, not zero. Its forced terminal marker and the independent third-process primary/journal check establish completion.

There is no controlled performance comparison or claimed FPS/TPS improvement. The two extra durable marker publications add I/O; long-term cost, shared-disk saturation and actual filesystem failure require separate measurement. Broad memory exhaustion, quota pressure and multikey fairness were not newly stressed by this one-key boundary test.

## Decision and remaining limits

**Keep this experimental. No installation or replacement beta.6 artifact.** The observed restart-reopening bug is fixed for the tested orderings by persisting uncertainty before unlock, not by pretending the newest edit survived without a copy.

A new edit submitted but not durably journaled or written is still lost on process failure; the previous version is preserved and now stays quarantined. If neither newest copy is durable, recovery cannot manufacture it. Losing the filesystem can lose all copies and markers. Player, chunk, ledger and POI files remain non-atomic, so a complete chunk replay is not an atomic inventory transfer across files.

The durable intent itself must have successfully persisted. The correction refuses unlocking when that publication fails, but physically corrupted/lying storage, removal or independent rollback of marker files, old experimental binaries, hung I/O and arbitrary direct writers remain outside this guarantee. The double-failure case preserves per-key uncertainty, not a global fault marker that was never written. Operator inspection, safe resolution of permanently uncertain resets and migration/downgrade policy are still unfinished.

Later submissions after the successful epoch fence, other APIs, multi-worker concurrency and broader actual-client fault orderings are not universally covered by these deterministic pauses. No live-client gameplay mutation was admitted while quarantined. Existing permission paths, viewer deadlines, strong recovery tickets, default-off gates, damage suppression, production data formats, artwork/UI and non-live preview are unchanged. Live see-through portals remain unimplemented.

There is no execution/access blocker for this completed bounded test. No human test or live-world access is needed.

Primary run: https://github.com/WeepingAngeIs/zSortange/actions/runs/44022524557
Healthy-ordering regression: https://github.com/WeepingAngeIs/zSortange/actions/runs/44022524547
Tested source: https://github.com/WeepingAngeIs/zSortange/commit/290ccf2dc614c8ecef9209aae9763095b4bedfb3

The evidence kit retains the original fixture failures, confirmed pre-fix defect artifacts, passing final raw archives, exact source, source differences, hashes and read-only analysis/verification scripts. It contains no executable JARs, native libraries, user worlds or decompiled engine source. This report-only follow-up does not change the tested executable.
