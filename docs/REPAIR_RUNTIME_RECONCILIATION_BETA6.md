# Beta.6 repair validation: runtime failures resolved

**Both previously failed lanes now pass in a focused GitHub run. The passing multi-key, corrupt-journal and no-durable-copy lanes are retained from their original run, not presented as newly rerun results. The candidate stays experimental. No replacement playable JAR is required.**

All Minecraft execution in this continuation used disposable GitHub infrastructure. No user profile, Java arguments, settings, player save, live server or user PC was opened or changed. No installed JAR was replaced. No model/settings change or force push was performed.

## 1. Verified source, capabilities and CI

The branch was first verified at the supplied `d135a7e80cd9f761d9d11b30e78604ebc620f577`. Original workflow **44001462983** had three successful lanes and two runtime failures, not a compile failure. The newer remote evidence superseded the earlier unexecuted-draft status.

GitHub tree/commit/ref writes are available in this session and were verified by actual successful calls. The repair commit was published as a non-forced fast-forward with an expected-head check; its push triggered the bounded workflow. No CLI credential or manual user installation was needed. This does not imply a separately untested workflow-dispatch action.

**Repaired executable source:** `4d1fd1273498f9ace5286b6fb575a10ee84d5d8c`.

**Focused workflow:** **44003271391**, both jobs successful:

| Lane | Job | Result |
|---|---:|---|
| Actual-client failure, quarantine, repair and reset | 133772316962 | Passed all four server-process legs |
| Full journal, primary fallback and independent restart | 133772317455 | Passed |

The commit message's explicit runtime-only selector runs these two lanes. Other normal/manual uses of this validation workflow retain the full matrix. Supporting build/room/isolation/older repair workflows triggered automatically, but are not substituted for the targeted results or labelled new coverage.

Previously passing evidence retained from **44001462983 at d135a7e...**:

| Lane | Job | Result reused |
|---|---:|---|
| Nine-key failures, fairness, supersession and reset cut | 133766308294 | Passed |
| Corrupt journal and conservative restart | 133766308354 | Passed |
| No durable newest copy / journal-only failure fallback | 133766308251 | Passed |

This is **not a claim that all five scenarios ran again on 4d1fd...**. Independent source comparison found only the two validation-fixture edits and their workflow selector; the journal, repair service, writer guard, storage and client scenario code are unchanged. The original failed logs and source are retained separately.

Hashes:
- Playable beta.6: `5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11`.
- Repaired test module, including observation/fixture changes: `49370865822761b72241aad6679ebce8f10a523f47397e93b961e3f25c9eb1b4`.
- Actual-client fixture: `5252f607843a4b5a3afa129ed5bf00481f263cba02fce5c0fafd1b3298ef0191`.
- Exact repaired source ZIP: `6183dcd494a6bb37235ffe59c2bcae7e83f1aeb4f6e87283857b513b5796aa96`.

The downloaded full/client artifacts agree on source and module hashes. The client/server startup probes confirm the playable binary was loaded from the packaged JAR, not main source output. Archive digests and a read-only verifier are included in the evidence kit.

## 2. Exact diagnoses and narrow repairs

### Full-journal lane: padding exceeded an existing NBT bound

The original runtime failure was:

```text
multi mode=full phase=70
java.util.concurrent.CompletionException: java.io.IOException: NBT bound exceeded
RepairService.encode -> MultiRepairFixture.lambda$begin$0
```

It occurred while the test worker was constructing quota-padding records, before the desired quota-exhaustion/primary-fallback assertion. The encoder bounds both serialized bytes and the estimated in-memory NBT size. The test accounted for encoded overhead but not the latter bound.

The fix computes a padding payload that satisfies both existing limits, including a safe exact remainder. It retains the 2-MiB per-snapshot bound, 64-record bound and 64-MiB payload quota. Production limits, fallback behavior and journal implementation are unchanged. A new assertion checks both encoded length and estimated size before the fixture publishes its no-item padding record.

Classification: **test-data construction defect**, not evidence of a lost primary save.

### Actual-client lane: the audit missed an early-cancelled plan

The original assertion failed at:

```text
fresh-withdrawal-denied:
Fresh network action never reached quarantine guard
```

However, the retained server action log showed a fresh request with matching revision 17, and the real client displayed the exact guard response:

```text
Persistence quarantine changed; no item mutation.
Verify repair/reset before retrying.
```

The old observer was another HEAD injection on `Transactions.Plan.commit`. The existing cancellable writer guard returned before that observer ran, leaving its counter unchanged. The inventory was unchanged, but the observation assertion incorrectly reported missing coverage.

The replacement observes the actual return value at the two production menu call sites. It invokes the original plan commit **exactly once**, records whether the original quarantine response was returned, and returns it unchanged. It does not bypass the guard, replace the result, retry a transfer or weaken item-count assertions.

The corrected final seed records:

```text
1078 NETWORK_REPAIR_ACTION fresh=true revision=17 serverRevision=17 value=256
1078 NETWORK_PLAN_RESULT quarantine=true blocked=1
1118 NETWORK_REPAIR_ACTION fresh=true revision=22 serverRevision=22 value=54
1118 NETWORK_PLAN_RESULT quarantine=true blocked=2
```

The captured older request was separately rejected with `fresh=false` before a plan commit. It is not falsely counted as a quarantine-plan result.

Classification: **fixture observation-order defect**. No production or experimental storage algorithm repair was needed for either reported failure. This does not certify all possible storage paths.

## 3. What the actual clients now demonstrate

The focused job runs **two genuine Minecraft client JVMs over localhost TCP**, with the packaged beta.6 and exact experiment, across four newly launched server-process legs. Generated development launch scripts control real clients; fixture directives lead to ordinary menu and test-operator command traffic. These are not FakePlayers, embedded connections, human testing or WAN conditions.

The successful seed established two owner pockets and a physical-chest interface. The server fixture changed named snapshots on two distinct chunk columns and injected failures in their real region-write worker. The sample-name edits and faults were server fixture inputs, not a newly claimed client deposit.

Passed checks include:
- A captured stale withdrawal moved nothing. A separately synchronized fresh withdrawal and fresh deposit each reached the real quarantine outcome and moved nothing. The five coal held for the refused deposit remained with Alpha.
- Beta's independent remote view remained usable, with its original lease deadline unchanged and 17 emeralds conserved as 16 stored plus one already withdrawn.
- Alpha's unsafe return retried at actual ticks **1151, 1251, 1351 and 1451**, while two write-failure keys and two pending snapshots remained. After one fixture landing repair at tick 1411, the existing recovery returned Alpha 40 ticks later without clearing storage quarantine.
- The affected columns unloaded before repair. A new attempt to reopen quarantined storage was refused without acquiring a view/lease.
- Alpha issued the test-only operator repair command through the real client connection. The still-failing key attempted exactly three repairs at ticks **2156, 2177 and 2218**, then stopped with its snapshot retained. Premature reset was refused.
- Normal shutdown retained two newest pending journal snapshots and Beta's live view. Its ticket audit removed **17 requests to zero**.
- A fresh server/client leg cut the server with `Runtime.halt(77)` after writing/flushing a repaired region but before the journal repair proof. Another fresh leg successfully repeated the idempotent snapshot repair, not a deposit or withdrawal.
- A second forced cut occurred after durable reset proof publication but before acknowledgement. Final startup observed four retained records with exactly one still quarantined. Resetting the other key required a new explicit client command and read-back/flush proof.
- After both resets, actual client rendering and server checks saw newest version-one data: 13 diamonds and seven gold ingots, with unchanged physical chest IDs, room IDs/cells and owners. One fresh post-reset withdrawal then conserved the diamonds as **12 stored plus one held**. Beta remained 16 stored plus one held.
- Final closure and drainage left zero pocket cache, pending-unload entries, engine callbacks, Anya ticket requests, repair work and unfinished return records.

The client scenario code, its item assertions and its single-send behavior are byte-for-byte unchanged from d135a7e. The command is a fixture-only operator interface restricted to the declared owner/keys, not a finished production repair UI.

Normal entry/settings authorization remains as before. This scenario does not newly certify every unprivileged command packet, arbitrary direct inventory writer or grave integration. No action was retried blindly to obtain passing inventory counts.

## 4. Full journal: latest primary fallback survives, but health stays failed

The repaired full lane actually reached **67,108,864 retained payload bytes (64 MiB) in 48 records**, using valid no-item padding plus the room snapshots. It then enlarged the target sample's name to version 1000. The journal refused the new record without evicting its prior token or decreasing retained payload bytes.

The original primary region-write/flush fallback still executed. The process was deliberately terminated after drainage rather than relying on clean shutdown. A new server recovered the version-1000 primary sample and checked **117 named items, eight original room/cell/owner identities and 32 physical chest identities**.

The restarted journal remained `fatal=true`, retained all 64 MiB, and refused blind reset. Its protected write attempt still reports failure because journal protection was unavailable, even when the primary fallback succeeded. Successful primary fallback is not represented as repaired journal health.

This exercises the software payload quota, **not a physically full disk**. The filesystem still had room for the primary save and persistent fault marker. Continued disk failure can prevent both from succeeding. The test's 48 records also do not exercise the separate 64-record runtime ceiling; record-count exhaustion was established in the prior standalone filesystem suite, not newly here.

## 5. Earlier passing multi-key/corrupt/no-copy evidence reused explicitly

The original multi-key lane submitted **nine distinct chunk keys in one server tick** to the real engine storage worker, then submitted a newer generation for one key. These are concurrent outstanding requests, not nine parallel disk threads. The earlier draft's broader 18-request plan is not a description of the actual executed test.

The runtime evidence shows one new repair dispatch per tick. Seven persistent-error keys each received three attempts with bounded backoff; an eighth healthy key succeeded while those failures continued. A ninth intent was refused at the eight-intent limit, but its snapshot remained and was subsequently repaired after capacity became available. A queued old token was refused after a newer version-two snapshot replaced it; stale schedule/reset requests could not overwrite that newer payload.

After a forced cut during reset publication, a separate process verified latest versions, **117 items, eight original rooms and 32 physical chests**, then emptied engine tickets and callbacks. This is finite multi-key fairness, not a long saturated workload guarantee.

The corrupt-journal lane deliberately flipped a checksum byte after a process cut. Startup refused the journal, did not erase or alter the corrupt evidence, and refused a blind reset. It conservatively used the prior physical version-zero data. Its generic PASS marker says identities are preserved; it must **not** be read as recovery of the unreadable newest journal version.

The no-durable-copy lane contains two deliberate contrasts:
- When both newest journal publication and newest primary write failed, restart recovered **version zero**, not the nonexistent durable version one.
- When journal encoding failed but the primary fallback succeeded, restart recovered **version one**, while the persistent journal fault remained and blind reset stayed forbidden.

These results are retained from workflow 44001462983 at d135a7e, not rerun or pooled as new final-commit results. The journal/service/guard and their algorithms are identical in the repaired source.

## 6. Resource costs and bounds

This is an acceptance repair, not a controlled performance optimization comparison. Failed runs stopped early and are not latency baselines. Existing observer overhead and fixture setup are included where the timing bracket records them.

| New successful process | Last RSS sample after readiness/start | Peak server RSS | Peak summed process-group RSS |
|---|---:|---:|---:|
| Real clients: initial failure/quarantine leg | 121.5 s | 1,064.6 MiB | 3,476.7 MiB |
| Real clients: repair cut | 33.8 s | 869.3 MiB | 3,192.3 MiB |
| Real clients: reset cut | 33.8 s | 844.7 MiB | 3,246.6 MiB |
| Real clients: final reset/reopen/cleanup | 76.0 s | 999.2 MiB | 3,376.4 MiB |
| Full-quota seed fixture | 66.0 s | 1,296.0 MiB | Not measured as a client group |
| Full-quota restart fixture | 43.0 s | 890.3 MiB | Not measured as a client group |

Client elapsed samples start after server readiness; fixture samples start with its process. They are not total CI build duration or perfectly continuous peaks. Client group RSS sums processes rather than measuring proportional set size.

The client job ran on an AMD EPYC 9V45 host with four guest logical CPUs and about 15.61 GiB visible RAM. The full fixture ran on an Intel Xeon Platinum 8573C host with four guest logical CPUs. Both used Temurin 21.0.13+9, Minecraft 1.21.1 / NeoForge 21.1.249, a 2 GiB server heap, initial heap 256 MiB, G1, ergonomic processor count 2, server view 3 / simulation 2. Client heaps were 1000 MiB with software GL; this is not a real hardware-renderer benchmark. Private native extraction directories remained in use. OPAC 0.33.0 and Carry On 2.2.6.13 were present in the isolated client runtime, not a statement about Anya's exact installed pack.

Per-leg limits remain 600 seconds for the actual-client scenario after readiness, 150 seconds for startup, 3 GiB server RSS, 6 GiB summed test process groups and at least 2 GiB available host memory. Fixture processes retain 360-second wall limits, a 5,200-tick scenario ceiling and 4 GiB sampled server RSS limit. No final resource/time guard fired. Minimum available host memory observed was about 11,771 MiB in the client run and 13,650 MiB in full-quota testing.

The largest recorded client-server event tick was 473.4 ms; no claim that this durability experiment removes all pauses is made. The new runs do not include a live-object retention census, long soak, network latency injection, physical disk saturation or permanently hung I/O. Raw CSVs and a read-only resource/identity verification script are supplied.

## 7. Preservation, boundaries and decision

Compared against exact source d135a7e, only three entries changed:
1. The validation observer around the returned menu-plan result.
2. The full-quota fixture's padding calculation/assertion.
3. The targeted workflow's lane selector.

All 74 production files, the client scenario and 11 acceptance files, all 12 remote-ticket files, and existing preparation/minimal/budget/backpressure modules are unchanged. All repair core files outside the validation package are unchanged. No production save format, identity, inventory rule, artwork/UI, experimental gate, damage suppression or non-live preview changed. The playable hash remains beta.6. A report-only follow-up is documentation, not a newly tested executable.

**Both reported runtime failures are resolved. No GitHub access blocker remains for the actions needed in this continuation. No new playable artifact or user test is warranted.**

The achieved guarantees are limited to the checked states and paths: token-guarded complete snapshot repair, explicit reset, refusal of fresh/stale menu writes during quarantine, unrelated viewer continuity, return recovery while failures remain, bounded finite retries, conservative corrupt/full-journal behavior, and the selected restart/cut cases.

Remaining production blockers include a safe operator-facing inspection/retention/repair workflow; larger-scale contention and concurrent live writers; cross-file atomicity; full-pack direct writer/mover/grave behavior; physical filesystem failure and hanging I/O; and filesystem/OS durability semantics beyond the tested Linux environment. Repairs replay a chunk snapshot, not a multi-file inventory transaction. A player file and chest file can still roll back independently. Nothing can recover an edit that acquired neither a durable journal snapshot nor a durable primary copy, or data lost when both copies share a destroyed filesystem.

The candidate remains experimental. Live see-through portals are still unimplemented, and no portal backend was installed. Nothing is needed from Anya for this completed validation.

Primary targeted run: https://github.com/WeepingAngeIs/zSortange/actions/runs/44003271391
Original mixed-result evidence: https://github.com/WeepingAngeIs/zSortange/actions/runs/44001462983
Repaired source: https://github.com/WeepingAngeIs/zSortange/commit/4d1fd1273498f9ace5286b6fb575a10ee84d5d8c

The evidence kit retains exact source snapshots, original failure logs, new successes, earlier passing lanes, artifact digests, raw measurements, screenshots and read-only verification. It contains no executable JARs, native libraries, player worlds or decompiled engine source.
