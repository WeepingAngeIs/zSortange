# Beta.6: actual-client confirmation interruption and persistence failure

**All eight final targeted jobs passed on one executable source. Five disconnect/lost-reply cases committed the already accepted reset once; three injected publication failures preserved quarantine and incomplete-publication evidence. The operator, repair and playable beta.6 implementations are unchanged. Keep this experimental. No replacement playable JAR is required.**

All Minecraft execution used disposable GitHub environments. No installed profile, user Java arguments/settings, real world, user PC or live server was accessed or changed. No installation, unsafe downgrade, force push or production integration occurred. Earlier command-path, reset-gap and publication tests are background evidence, not substitutes for these cases.

## Exact checkpoint

Starting report checkpoint: `af97e49bbf874f8d6aa3e36553fdabe2e24d9885`, branch `feature/elemental-pockets`.

**Final tested executable source:** `89ad343ea80798620b8164fd7d9e83b199089c93`.

**Targeted workflow:** `44041410657`, Actual-client confirmation interruption and persistence failure. All eight jobs passed; completed 2026-11-17 07:52:37 UTC.

| Boundary | Final job ID | Result |
|---|---:|---|
| Receipt consumed, reinspection paused, client disconnects | 133896627458 | Passed |
| Current reset intent durable, client disconnects | 133896627387 | Passed |
| Intermediate unlocked projection, client disconnects | 133896627441 | Passed |
| Completion publication durable, client disconnects | 133896627444 | Passed |
| Committed reset, success acknowledgement withheld, client disconnects | 133896627276 | Passed |
| Intent publication throws before force | 133896626946 | Passed |
| Intermediate projection publication throws before force | 133896627471 | Passed |
| Completion publication throws before force | 133896627312 | Passed |

SHA-256:
- Playable beta.6: `5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11`.
- Existing operator module: `e11ed33cfb44e53bb059dbbb0449f94a87a02fa0f7d6de3ec38477a35f840c57`.
- Separate interruption wrapper: `3ef07367a4d20b748a0ec72cc3f95a982e21286f0184988625cc06a1be150402`.
- Final network acceptance fixture: `768f3cda02fa70c8a2c79bec1010944120b4da486602bfcafef8af640013e625`.
- Existing remote-ticket module: `6aa9f312cc2b526a86b2122510d50021845a9b12aa8039298af2012748d30bcd`.
- Exact tested source ZIP: `2d874200d83e91fd9a5fcd827ac77aec4cc731a92cb4cc9a4990e3dea63c118d`.

All final artifacts agree on these inputs. Archive comparison found all343 files from the preceding executable source byte-identical, including all74 production files. Relative to the starting report head, this phase adds12 fixture/build/workflow files only. It changes no journal/reset encoding, production format, authorization rule, inventory operation, artwork/UI, gate, damage suppression or non-live preview. This documentation-only follow-up is not another tested executable.

## Genuine command packets and bounded interventions

Each lane has two actual Minecraft clients over localhost TCP, a killed Alpha client and its fresh replacement, and two fresh clients after an independent server restart. Across eight lanes this is **16 dedicated-server processes and40 client-process launches**, with at most two live clients per test server. It is not40 simultaneous users or a fake-network replacement.

The clients send ordinary `ClientPacketListener.sendCommand` traffic. The existing command parser, permission checks, actor/receipt binding, reinspection and durable-reset implementation process it. Automation obtains the receipt from the actual received system-chat response. It never calls `OperatorResolution.inspect` or `resolve` directly.

Independent log verification counted **four operator sends per lane**: initial inspection, one confirmation, inspection after reconnect and inspection after server restart. Across the final cases this is32 sends and exactly eight confirmations. Reconnect/restart sends no confirmation and does not replay an item transfer. A missing response never instructs a mutating retry.

Four worker-boundary disconnect cases use an eight-second latch on the storage worker, never on the world thread. The server verifies the on-disk boundary, requests an actual Alpha JVM-group SIGKILL, observes logout, then releases the worker. Release/logged disconnect occurred within three to five ticks of the gate observation; the latch ceiling did not fire.

The acknowledgement case deliberately suppresses only the success reply after durable completion, then kills the client. This models lost acknowledgement explicitly, not a spontaneously dropped packet. The confirmation still runs once.

The three publication-error cases inject an `IOException` after temporary bytes are written and immediately before force. The original client receives the real error first, then is killed and replaced. These are network-observed persistence errors followed by reconnect, not simultaneous silent loss of the error response.

## Lost response versus committed reset

| Healthy disconnect point | State at interruption | After settling and independent restart |
|---|---|---|
| Receipt consumption / reinspection | Current snapshot REPAIRED/locked; old pending intent | Accepted reset completes once |
| Durable current intent | REPAIRED/locked; current pending intent durable | Accepted reset completes once |
| Intermediate projection | APPLIED/unlocked WAL; pending intent still authoritative | Accepted reset completes once |
| Durable completion | APPLIED/unlocked; completed intent, before reply | Completion remains durable |
| Acknowledgement loss | Reset committed; only success reply withheld | Completion remains durable |

All five original clients lost the success response. Reconnect and restart inspection returned `REFUSE_NO_PENDING_INTENT`, while separate raw checks verified exact token, complete reset and primary/journal equality. Losing the caller does not roll back an already accepted authorized operation. Permission revocation mid-command, changed actor identity and adversarial concurrent writing were not introduced or established by this test.

## Publication errors retain evidence and protection

All three errors leave the target blocked, its current payload intact, a pending intent and exactly one temporary publication file. The new client sees `INSPECTION_REFUSED`; retained files are hashed and verified unchanged after restart. Nothing deletes or promotes the partial publication automatically.

| Error point | Seed state | Restart |
|---|---|---|
| Intent before force | REPAIRED/locked; prior pending intent; global fault | Partial publication refused; global protection remains |
| Projection before force | REPAIRED/locked; current pending intent; per-key protection, no immediate global marker | Partial WAL publication refused; startup global failure |
| Completion before force | APPLIED/unlocked projection; pending intent still blocks; global fault | Partial completion remains protected |

**Projection failure is initially per-key protection, not an immediate global fault.** This is existing behavior, now checked explicitly. All three startup refusals block new remote admissions. A `journalBytes=-1` diagnostic means the service did not initialize; it does not mean files were erased.

The unrelated existing viewer stays open with unchanged contents and original lease deadline during the seed faults. This does not claim successful new writes under global failure. After restart a fresh unrelated remote-open attempt is conservatively refused in all three fault lanes, while its stored and held items remain preserved. The availability cost is not bypassed.

These cases deliberately do not repair partial records. The operator path refuses blind cleanup, force-reset, marker deletion and downgrade. General safe resolution of incomplete publications remains unfinished.

## Item preservation, restart and cleanup

Real clients initially enter the two homes/rooms. Server fixtures supply samples and create an eligible protected state using current journal methods and a bootstrap ordinary primary-write failure. That fault is cleared before confirmation; the newest named generation-two sample is already durable in both copies.

Beta performs one real-client withdrawal before interruption:17 emeralds become16 stored plus one held. Its menu, original deadline and balance are checked every active tick. After Alpha reconnects, Beta receives its unchanged16-item network view. No post-fault mutating write test is claimed.

Each lane's independent raw primary inspection and checksum-checked journal/intent parsing verify **13 generation-two diamonds, seven generation-zero gold ingots,16 stored emeralds plus one held, two original room/cell/owner identities and eight physical chest identities**. Other checked slots remain empty.

After the accepted operation settles and runtime work drains, the seed explicitly saves player/world state, verifies physical region and retained files, then uses `Runtime.halt(77)`. A fresh server reads actual primary/journal data before loading destination chunks or reconnecting clients. This is not a server crash inside the tested reset: the mid-command interruption is the client kill. Earlier server-publication cut evidence remains separate.

Healthy lanes reopen Beta's view after restart. Error lanes preserve player counts but refuse new views under failed initialization. All final processes require650 drainage ticks and zero destination cache, pending unloads, callbacks, actual engine-owned Anya requests, active repair/in-flight work and unfinished returns. Required snapshots and complete/pending/partial evidence remain stored. No persistent obligation is deleted to obtain empty runtime queues.

## Initial failures and correction history

Initial run **44039699159** at `09d85fc35715f4f58c0e2d323f2d2987d081a181` compiled eight lanes. Intent-fail passed; seven stopped on new fixture expectations.

Six failures used APPLIED ordinal zero instead of the actual enum PENDING=0, APPLIED=1, REPAIRED=2. Logs already recorded successful completion or the expected pending projection. The correction uses named enum ordinals and retains exact lock, pending-intent, token, payload and reset-count assertions.

Projection-fail incorrectly required an immediate global fault. The service retained its target lock and pending intent; startup later refused the partial file globally. The corrected fixture requires that scoped transition and exact partial-file preservation. The service policy is unchanged.

During the full-file fixture correction, intermediate `ab17c3adc8b66e481b810076b7995d423b1866c7` accidentally omitted per-home floor offsets. Diff review caught it and restored the original geometry in final `89ad343...`. Intermediate run **44041048483** timed out initial visits in all eight lanes, before confirmation. This was a fixture setup error, not a storage defect. All its artifacts remain retained.

The kit preserves24 unchanged archives: eight initial, eight intermediate and eight final. Only final44041410657 supplies passing coverage/measurements. No storage algorithm, item/identity obligation or mutating retry was changed to make tests green. Automatically triggered general workflows are not substitutes for this matrix.

## Resource bounds and exclusions

Final runners: four guest logical CPUs on EPYC7763/9V45/9V74 or Xeon Platinum8573C hosts, roughly15.61GiB visible RAM. Temurin21.0.13+9, Minecraft1.21.1, NeoForge21.1.249. Server Xms256m/Xmx2048m/G1, ergonomic processors two (not OS pinning), server view three/simulation two; clients1,000-MiB heaps/software rendering. Carry On2.2.6.13 and OPAC0.33.0 are present, not newly fully certified.

Across16 final server legs: maximum sampled server RSS **1,270.6MiB**; maximum summed server/client groups **3,669.8MiB**; minimum host availability **11,548.4MiB**; longest final elapsed resource sample **103.093seconds**. Largest recorded event bracket **1,278.999ms**, in the acknowledgement seed. No performance comparison or causal attribution for that maximum is claimed.

Whole-leg timing includes startup/loading/instrumentation and excludes lowest-priority fixture actions and final CSV output. It is not command latency or end-to-end FPS/TPS. Resource sampling is approximately0.25seconds after readiness, not continuous; summed RSS is not proportional/shared accounting. Forced termination may leave buffered tail rows missing. Terminal markers and independent restart read-back establish those boundaries.

Limits:600seconds per leg,150seconds server startup,3,600ticks client readiness,900ticks gameplay step,240client ticks response observation,8seconds worker gate;3GiB server RSS,6GiB combined RSS,2GiB minimum host availability;20-minute GitHub job. No final limit fired. The original client options handler still rejects a client simulation-distance request of two; the explicit dedicated-server value is two. No client setting of two is inferred.

## Decision and remaining limits

**Experimental only; no replacement artifact or installation.** The new evidence distinguishes an authorized committed reset with a lost response from failed publication with preserved quarantine. Reconnect/restart inspects instead of replaying a mutation.

Injected exceptions are not physical disk failures, random torn writes, lying caches or power cuts. The newest data was durable before confirmation. No durable newest primary and no durable newest journal still means no recovery of that edit. Player, chunk, ledger and POI files remain non-atomic; explicit successful saves do not make cross-file inventory transfers atomic.

Concurrent newer submissions during these command interruptions, mid-command revocation, actor/session changes, unsafe landing recovery under these same faults, hung storage and arbitrary direct writers remain outside this matrix. Primary fallback/save/flush obligations are unchanged; a hanging device may still prevent completion. Inspection refusal is not a general recovery UI for partial publications. Older binaries ignoring `.reset`, deleting evidence or independently rolling back metadata remain unsupported.

No execution blocker remains for this completed task and nothing is needed from Anya. Artwork/UI, permissions, gates, damage suppression and non-live preview remain unchanged; live see-through portals remain unimplemented. The evidence kit contains24 raw archives, exact source, measurements, command traces, failure provenance and a read-only verifier, not executable JARs, native libraries or player worlds.

Final workflow: https://github.com/WeepingAngeIs/zSortange/actions/runs/44041410657
Tested source: https://github.com/WeepingAngeIs/zSortange/commit/89ad343ea80798620b8164fd7d9e83b199089c93
Initial failed matrix: https://github.com/WeepingAngeIs/zSortange/actions/runs/44039699159
Intermediate fixture failure: https://github.com/WeepingAngeIs/zSortange/actions/runs/44041048483
