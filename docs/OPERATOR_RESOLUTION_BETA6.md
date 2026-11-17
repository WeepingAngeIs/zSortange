# Beta.6: read-only inspection and explicit pending-reset resolution

**All eight final targeted jobs passed on the same executable source. The new operator path is experimental and deliberately limited. The playable beta.6 and underlying repair module are unchanged; no replacement playable JAR is required.**

No installed profile, user Java arguments/settings, real save, user PC or live server was accessed or changed. All Minecraft tests used disposable GitHub infrastructure. No installation, downgrade, force push or production integration occurred.

## 1. Exact checkpoint

Starting branch/report: `ddc55e5d968ff53f6bb8ef2a30138f47f5a9d419`, `feature/elemental-pockets`.

**Final tested source:** `423d870f5d201b69b283b64e1d0ee8c84f7ecd2d`.

**Targeted workflow:** `44030774368`, Read-only inspection and explicit pending-reset resolution.

| Lane | Job ID | Result |
|---|---:|---|
| Standalone journal inspection checks | 133861917507 | Passed, 36 assertions |
| Recoverable exact-current resolution and stale inspection | 133861917887 | Passed |
| Matching but uncertain old version | 133861918030 | Passed: refuses reopening |
| Conflicting primary/journal versions | 133861917999 | Passed: refuses choosing/overwriting |
| Pending-intent publication failure | 133861918024 | Passed: retained partial evidence and quarantine |
| Completion publication failure | 133861917953 | Passed: retained partial evidence and quarantine |
| Process cut at intermediate unlocked projection | 133861917822 | Passed: quarantine survives |
| Process cut after completed reset publication | 133861918162 | Passed: completed reset survives |

SHA-256:
- Playable beta.6: `5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11`.
- Existing durable-intent repair module: `0e18934cca7ff47184cadcf4b504466902054a415f615002bb45bfaa95616c17`.
- New separate operator experiment: `e11ed33cfb44e53bb059dbbb0449f94a87a02fa0f7d6de3ec38477a35f840c57`.
- Exact tested source ZIP: `3af076baa929909ed5fd811acb97b44a2c32af24660432bc595ef0518ec640fe`.

Every final Minecraft artifact agrees on these hashes. Comparing with the preceding exact executable archive found all 326 existing files unchanged, including all 74 production source/resource files. Relative to the starting report commit, this phase adds nine files only. The repair service, snapshot/reset formats, save-budget implementation, client fixtures, artwork/UI, identities, gates, damage suppression and non-live preview are unchanged. A documentation-only follow-up is not another tested executable.

The earlier completed reset-gap tests were reused as background evidence, not rerun as new coverage. General build/room/isolation workflows triggered automatically; they are not substitutes for the operator matrix.

## 2. Inspection does not mutate the inspected storage

`JournalDisk` independently parses the existing world binding, `.wal` snapshots, `.reset` intent markers and fault marker with READ-only bounded file access. It does not invoke the journal constructor, create a missing directory/binding, flush, delete partial records, finish a reset or change a lock. Checks include fixed format/checksums, numeric key paths, world association, bounded record sizes/counts and a sorted fingerprint of the retained files. Missing, malformed, unknown and partial entries are refused, not fabricated or cleaned up.

`ReadOnlyRegion` opens an existing primary `.mca` file with READ only. It does not use an open-or-create region cache, journal overlay, destination chunk load or flush. It validates the location header, encoded-length/allocation/EOF bounds, compression and NBT coordinates. GZIP, zlib and uncompressed records are supported; external `.mcc` records and other compression types are conservatively refused. This is not arbitrary region-format compatibility.

The service checks operator permission level four, the owner thread, an unloaded destination, no in-flight save and idle repair work. On the storage worker it reads the journal, the primary data and the journal again. Back on the owner thread it rechecks submission epoch, live/disk agreement, room/owner/coordinates and storage identities. It reports exact world-binding ID, chunk key, snapshot token, pending-intent token/status, record state, payload digests, decoded-data equality and a conclusion.

**Tokens are identifiers, not timestamps or numeric versions.** Digests identify bytes; inspection is not a disk force and does not independently certify all prior write acknowledgements. Existing recorded repair proof plus a new flush/read-back during resolution supply the supported protocol's completion checks.

The Minecraft tests hash the checked journal and region filenames/content before and after inspection/refusal and assert that no destination chunks load. Access times are explicitly excluded. Diagnostic log writes and ephemeral inspection receipts are not changes to the inspected data. Ordinary server startup/shutdown still has its existing behavior; this is not a claim that booting a Minecraft server is globally read-only.

## 3. Explicit resolution is a small supported subset

An inspection is eligible only when all of these are true: no persistent/process-global fault; a pending reset marker exists; its token differs from the current snapshot token under the existing single-worker protocol; the current snapshot is locked and has a REPAIRED proof; its complete decoded payload equals the primary chunk; and room/identity/thread/unloaded-state checks pass.

A same-token pending intent remains `REFUSE_UNCERTAIN_OLD_VERSION` even when the primary and journal agree. Matching old copies cannot prove that an unjournaled newer edit was saved. A primary/journal disagreement remains `REFUSE_PRIMARY_JOURNAL_DISAGREEMENT`; the operator path never selects a winner or replays the journal over a possibly newer primary.

The prototype exposes two experimental, permission-level-four commands:

```text
anya_repair_operator inspect <hex-key>
anya_repair_operator resolve <world-uuid> <hex-key> <snapshot-token> <inspection-receipt>
```

**These commands are not in installed beta.6 and were not installed for Anya.** The runtime fixtures call their backing service with a console command source; they do not exercise a real player's chat command or the complete command-parser/user-interface path.

At most 16 inspection receipts are retained. Each is actor-bound, one-use and expires after 600 server ticks. An explicit request supplies exact world/key/token plus its receipt, rechecks the epoch and re-inspects current storage. The fingerprint, primary digest and decision must still match. Only then does it call the unchanged `RepairService.reset`, which retains its fresh epoch/in-flight checks, original primary flush/read-back, durable reset intent and completion publication.

Inspection alone never resets. A failed/stale request does not retry itself. A wrong request can consume its receipt but does not alter storage; another inspection is then required. The code bounds retained receipts, but this phase does not prove a high-rate operator-request flood is globally resource-bounded. Reader rejection and explicit resolution are not a generic operator recovery console or persistent administrative audit system.

## 4. Seven disposable runtime scenarios

The runtime matrix uses **19 dedicated-server processes total, zero real Minecraft clients or network sessions**. Each seed uses one server-player fixture with a simulated packet sink. Restart processes have no connected player. Initial uncertain metadata is deliberately constructed with the current journal's own methods; this phase does not present that setup as another naturally reproduced reset race.

The fixture starts with 13 diamonds and creates named snapshot versions without creating additional items. A bootstrap primary-write failure establishes quarantine, then the existing repair service restores version one. It constructs a durable pending intent with an intermediate unlocked projection. Recoverable cases then submit version two, which becomes a locked, proven successor while the older pending marker remains. Every transition uses only generated test storage.

| Case | New verified behavior |
|---|---|
| Resolve | Read-only inspection of retained version two is eligible. Wrong-world and low-permission requests are refused. Version three arrives after a receipt is issued, and that old receipt is rejected as stale. Wrong-key confirmation is also refused. A fresh exact version-three confirmation completes existing flush/read-back/reset. After forced termination, another server directly verifies version three and completed reset. |
| Uncertain | Matching primary/journal version one with the same pending token is explicitly refused. No marker or snapshot is removed, and quarantine remains. |
| Disagreement | The fixture models an unsupported external writer by changing only primary data to version three while journal version two remains. Inspection reports disagreement and refuses to overwrite either or reopen. Both copies are retained. |
| Intent failure | An injected exception occurs after temporary intent bytes are written but before their force boundary. Resolution fails, global/target protection remains and the temporary file is retained. Restart inspection refuses incomplete publication without deleting it. |
| Completion failure | The equivalent exception at completion-marker publication retains partial evidence and quarantine. Restart refuses blind cleanup/reset. |
| Projection cut | The process terminates after the intermediate unlocked projection but before durable completion. A fresh server reads primary version two directly and sees the pending intent. Reopening is refused as uncertain. |
| Completion cut | The process terminates after durable completion publication, before acknowledgement. Restart verifies version two and the completed reset. A redundant resolution is refused because no pending intent remains. |

Each runtime case verifies **13 exact component-bearing diamonds, the original room/cell/owner identity and all four physical chest identities**, with other inspected slots/chests empty. The seed player's separate 11 emeralds are checked while that player exists; no persistent player reconnect or cross-file transaction guarantee is claimed.

Raw primary reads happen before destination loading and do not use the journal overlay. The normal acknowledged resolution finishes at version three; other newer-snapshot cases finish at version two; the deliberately uncertain case retains version one. No version-number interpretation is added to production data; these names are fixture oracles only.

After final verification and 650 drainage ticks, the tests require zero engine-owned requests, destination cache entries, pending unloads, callbacks, active repair/in-flight work and unfinished returns. Required snapshots, completed/pending markers and fault evidence remain stored. An empty runtime queue is not achieved by deleting persistent obligations.

### What the injected failures mean

Intent/completion faults are `IOException`s from the existing journal cut hook immediately before file force. They leave a partial temporary publication by design. They are not physical disk exhaustion, arbitrary partial-sector corruption, a lying device cache or a power cut. `Runtime.halt(77)` tests process loss at selected boundaries, not the durability behavior of every filesystem/hardware stack.

The original primary-save fallback and all pre-existing save/flush obligations are unchanged. This matrix does not newly exercise every ordinary primary-write failure or a journal plus primary both failing; the existing no-durable-copy evidence and limitation remain, not a new recovery claim.

## 5. Read-only format tests and the initial reader defect

The standalone journal reader passes **36 assertions** against generated files: non-creating inspection, pending/uncertain/eligible decisions, corrupt/missing/misbound records, partial publications, global fault, and 64 retained snapshots with a refused 65th. File maps/hashes remain unchanged by inspection and refusal. Synthetic corruption or deletion occurs only while constructing those negative fixtures.

The first workflow, **44029766043** at **fdea9ee7ff83ecc66dc7d0a27c8e951706a784e8**, compiled and passed five jobs, but resolve, uncertain and disagreement failed with `PRIMARY_LOCATION_OUTSIDE_FILE`. The new reader incorrectly required all allocated final-sector padding to exist. The pinned engine writes the encoded chunk buffer and adds unused final padding on region close; a complete record may legitimately end before the allocation's padded end, especially after a forced cut.

This was a correctness defect in the new read-only inspection implementation, not demonstrated lost-save behavior or a quarantine bypass. In one case it refused during restarted raw read-back, in one after a newer snapshot invalidated the old receipt, and in one after the intentionally conflicting primary write. All three failed artifacts are retained separately.

The narrow fix checks that the header and **entire encoded record** lie inside the actual file and that its length fits its allocated sectors. It does not require unused trailing zeros. Actual truncated payloads still fail. No item, identity, read-only, reset or quarantine assertion changed, and no transfer was blindly retried.

A new **55-assertion synthetic region-reader check** runs during each seed on a separate disposable format-test directory. It accepts complete unpadded GZIP/zlib/raw records and padded records, while rejecting truncated data, out-of-file offsets, allocation overflow, unsupported/external compression, coordinate mismatch and oversized decompression. It verifies unchanged filenames/content after reading. Repeating those same checks across seven seeds is not 385 independent test designs.

Only final source `423d870f...` supplies the complete positive runtime evidence. The older five passes are not pooled to disguise the first overall failed workflow. No pre-existing production or repair-core source changed.

## 6. Resource bounds and measurement exclusions

Targeted runners expose four guest logical CPUs on AMD EPYC 7763, 9V45 or 9V74 hardware. Java Temurin 21.0.13+9, Minecraft 1.21.1, NeoForge 21.1.249, Xms256m/Xmx2048m, G1, ergonomic processor count two, dedicated-server view three/simulation two, flat disposable worlds and loopback binding.

Across all 19 server processes:
- Peak sampled server RSS: **995.4 MiB**.
- Lowest sampled host-available memory: **13,856.7 MiB**.
- Longest last elapsed resource sample: **45.025 seconds**, versus a 200-second process ceiling.
- Largest recorded event bracket: **63.881 ms**.

The launcher retains a 4-GiB sampled-RSS stop and 2-GiB minimum available host memory. Fixtures retain 2,600-tick overall and 900-tick phase limits. The standalone journal suite has a 192-MiB heap and 60-second ceiling. No final guard fired.

There is no controlled performance comparison. RSS is sampled once per second, not a live-object ownership measurement. Event timing excludes lowest-priority fixture submissions/checks and some output; asynchronous worker I/O is not isolated as server-thread CPU. Forced cuts can leave buffered tick rows unflushed. Empty distributions are reported unavailable, not zero. Preserved cut markers and independent primary/journal read-back establish those boundaries.

Read-only parsing is bounded by 64 snapshots/markers, 2 MiB per payload and a 64-MiB payload aggregate plus fixed framing. It may allocate copies while checking those bounded records. These runtime cases contain one room, not a near-capacity payload or sustained inspection-spam stress. The full-count core fixture uses small payloads; software quota refusal is not physical disk-full coverage.

## 7. What a human operator may conclude

- **Eligible for explicit reverify:** the currently retained, protected successor snapshot has the expected identity and proof, agrees with primary data and stayed stable during this inspection. This permits a separate exact, fresh confirmation in the experiment; it is not an automatic unlock or a universal no-data-loss certificate.
- **Uncertain same-token intent:** matching old copies cannot establish that no newer unjournaled submission was lost. Keep quarantine. The path intentionally offers no 'force accept old data' switch.
- **Conflicting, partial, missing, corrupt or global-fault evidence:** preserve it. This prototype refuses selection, deletion, automatic repair and reset. It does not tell the operator to remove `.reset`, `.wal`, temporary files or a fault marker to make the warning disappear.
- **Completed intent after restart:** the selected completed-reset boundary persisted in these tests. This is not evidence that separate player/ledger/POI files are transactionally synchronized.

The commands are experimental and are not available in installed beta.6. The tests use their backing API with console sources and a low-permission negative control, not an actual network operator or command-parser interaction. A broader safe UI, durable operator authorization/audit, offline export, hostile concurrent filesystem changes and deliberate independent rollback remain unfinished.

## Remaining safety limits and decision

**Keep the operator module experimental; no installation or replacement artifact.** The demonstrated resolution is intentionally narrower than general recovery: it does not restore a mismatched primary from journal, resolve corrupt data, clear a global failure or choose an old version merely because its copies match.

Different UUID tokens are interpreted only through the trusted existing protocol, not as an independent chronological proof. Matching current copies cannot prove that an entirely unrecorded newer submission never existed. A successful resolution acknowledges the verified retained snapshot, not every intended edit in history. If neither newest copy is durable, the edit cannot be manufactured. Evidence of such loss or other ambiguity must not be dismissed using this eligibility result.

Player inventories, chunks, ledger and POI files remain non-atomic. This fixture changes names on a conserved stack, not an atomic cross-file deposit/withdrawal under power failure. The marker itself and its prior durable publications must survive; hardware/storage loss can destroy both data copies and metadata. Old experimental binaries that ignore `.reset`, manual marker removal or rollback, uncontrolled direct writers and hung storage remain unsupported.

The new read-only parser deliberately refuses unsupported region encodings. Files can change outside the monitored service; the observed epoch/fingerprint checks are not a hostile multi-process filesystem locking protocol. Receipt expiration and the 16-receipt cap are implemented but not stress-certified. A valid reset interrupted at its projection can remain permanently uncertain under this conservative policy; safe human resolution of that broader case is not solved by deleting evidence.

Existing permission, lease, recovery, save-budget and primary-write code is unchanged. This is not fresh full-pack or actual-client fault coverage. The previous no-durable-copy and healthy-ordering reports remain separate historical evidence. All production saved formats, identities, artwork/UI, default-off gates, damage suppression and non-live preview are preserved. Live see-through portals remain unimplemented.

No execution/access blocker remains for this completed bounded milestone, and nothing is needed from Anya. The evidence kit contains final raw archives, all three first-run failures, exact source, artifact digests and read-only verification/analysis scripts. It contains no executable JARs, native libraries, player worlds or decompiled engine source.

Targeted run: https://github.com/WeepingAngeIs/zSortange/actions/runs/44030774368
Tested source: https://github.com/WeepingAngeIs/zSortange/commit/423d870f5d201b69b283b64e1d0ee8c84f7ecd2d
