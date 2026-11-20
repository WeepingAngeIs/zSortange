# Beta.6: actual-client operator inspection and explicit confirmation

**The targeted actual-client workflow passed both process legs on the final source. Thirty operator commands traveled through genuine Minecraft client connections, with received chat replies and independent server/data assertions. The operator and repair implementations are unchanged. Keep the candidate experimental; no replacement playable JAR is required.**

All Minecraft work used disposable GitHub infrastructure. No user profile, installed mod, Java argument, setting, real world, PC or live server was accessed or changed. No installation, downgrade, force push or production integration occurred. Existing completed suites were not manually replayed; automatically triggered general builds/room/isolation jobs are supporting checks, not the new command-path result.

## 1. Exact checkpoint and preservation

Starting report checkpoint: `67745119e930b099f9006b4c2d7968f66463faa6`, branch `feature/elemental-pockets`.

**Final tested executable source:** `fe824e05a479a5bee9d41c04fa0eefacb72bc67f`.

**Targeted workflow:** `44034903454`, Actual-client operator inspection and confirmation. Job `133875315517` passed; workflow completed 2026-11-17 07:01:04 UTC.

- Successful artifact: `14306991877`, SHA-256 `0616a2325954c7c653165938af3cd69869e6b2ac79a3c0b28914b6add817f296`.
- Original failed artifact: `14306752006`, SHA-256 `0e5983910fea552233a8f08a9dbc94fb3d61b1863c776645fe04faa8419deae5`.
- Exact tested source ZIP: `c80de5e2d72197769775b7086a9683c9164f7487931a950be2d05108e36caed4`.
- Playable beta.6: `5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11`.
- Existing operator experiment: `e11ed33cfb44e53bb059dbbb0449f94a87a02fa0f7d6de3ec38477a35f840c57`.
- New network acceptance fixture: `87418d7edcefe85e11e8cf3759a54860b49de86cf1054155d1065e0fa354e00e`.
- Existing remote-ticket experiment: `6aa9f312cc2b526a86b2122510d50021845a9b12aa8039298af2012748d30bcd`.

Archive comparison verifies all 336 files in the preceding operator executable source remain byte-identical, including all 74 production files. Relative to the starting report head this phase adds six fixture/build/workflow files only. The existing operator module hash matches the preceding validation. Production saved formats, snapshot/reset encoding, original client Java fixtures, artwork/UI, identities, save obligations, gates, damage suppression and non-live preview are unchanged. This report-only follow-up is not another tested executable.

## 2. The commands really traveled over the network

The scenario starts two genuine Minecraft client JVMs over localhost TCP for each of two dedicated-server processes. Client logs and server login records identify both players. It does not use fake network connections instead of clients.

The added fixture payload carries a test directive and later the observed response, not an operator service call. The client sends each requested action once through `ClientPacketListener.sendCommand`. Minecraft's server command parser, permission predicate and existing `anya_repair_operator inspect/resolve` implementation handle it. A client event observer captures the actual system-chat response, including the issued inspection receipt, and returns that text to the fixture. The next confirmation uses that received receipt. The fixture never directly calls `OperatorResolution.inspect` or `OperatorResolution.resolve`.

There are **25 operator commands in the seed process and five after restart**, each logged once per sequence. Original item/menu actions are separate and also retain their single-mutating-intent behavior. Diagnostic screenshot timing can precede rendering the response being observed; chat logs and paired server assertions are the primary response evidence. The retained `operator-Alpha-32.png` visibly contains the successful confirmation returned by the preceding command.

This is automated command-parser coverage, not a human typing/GUI usability test, authenticated public server or WAN-latency certification. Loopback offline mode is confined to the disposable test server. Existing Carry On 2.2.6.13 and OPAC 0.33.0 are loaded, but their presence is not a new exhaustive pack/integration certification.

## 3. Authorization and receipt validation

| Actual-client action | Checked outcome |
|---|---|
| Non-operator inspection and confirmation | Native command parser returns Unknown or incomplete command; storage remains protected |
| Different level-four operator uses Alpha's receipt | UNKNOWN_OR_REPLAYED_RECEIPT; no reset or data mutation |
| Owner reuses the receipt consumed by that rejected attempt | Refused; current implementation consumes before actor validation |
| Unknown receipt | Refused |
| Wrong world, wrong key or wrong token | EXACT_WORLD_KEY_TOKEN_REQUIRED; protected files/data unchanged |
| Natural expiry | The fixture waits 620 actual server ticks after inspection without changing the clock or TTL; confirmation returns STALE_INSPECTION |
| New snapshot after inspection | The prior receipt returns STALE_INSPECTION; current snapshot remains quarantined |
| Primary bytes change after inspection | Reinspection returns RESOLUTION_EVIDENCE_CHANGED rather than trusting the old receipt |
| Same-token pending old evidence | REFUSE_UNCERTAIN_OLD_VERSION, even though the retained old copies agree |
| Conflicting primary/journal data | REFUSE_PRIMARY_JOURNAL_DISAGREEMENT; neither copy is selected, overwritten or discarded |
| Fresh eligible exact confirmation | Existing flush/read-back/durable-reset path completes and reports success |
| Reuse after successful confirmation | Refused |
| Old receipt after process restart | Refused; no persistent confirmation authority was inferred |
| Redundant reset of completed key | REFUSE_NO_PENDING_INTENT |

Each inspection/refusal checks that the target remains unloaded and the checked `.wal`, `.reset` and decoded primary payload digest stay unchanged. These checks concern the selected target, not all files the independently running server may save. Access times and diagnostic logs are outside the nonmutation assertion.

Actor binding in the unchanged implementation uses the command source text name. This test establishes separation of the two current real client names, not a new persistent account-identity scheme. A different operator who has a receipt can consume it without gaining reset authority under the existing policy; that is recorded, not silently redefined as a reusable owner receipt. Receipt-cap flooding, rename/reconnect identity edge cases and arbitrary hostile filesystem changes were not exercised.

The final successful confirmation is per chunk key. A second conflicting key in the same room deliberately remains quarantined. The result is not a claim that the entire affected room became enterable.

## 4. Fixture-created evidence versus client actions

Two independent homes/owners and two rooms are allocated through the actual clients' initial entrance interactions. Each room has four physical chests. The server fixture supplies their sample items and records the existing room/cell/owner and chest identities.

Target A has 13 diamonds; target C in a different chunk column has seven gold ingots. Their names carry fixture generations, not production version numbers. The unrelated owner has 17 named emeralds. Beta performs a real single-item withdrawal through the production menu, leaving **16 stored plus one held**.

Two bootstrap ordinary primary-write faults are injected to create protected samples. The faults are cleared and the existing repair service restores the recorded snapshots. The fixture deliberately constructs pending reset evidence through the current journal methods, then submits healthy detached successor snapshots where required. These are setup operations in disposable storage, not operator gameplay writes admitted through quarantine and not a new recreation of every preceding crash race.

For changed/conflicting evidence, the fixture models an unsupported external writer by changing C's primary copy to generation three while retaining its journal generation two. The tested command path refuses to choose a winner. A target-entry attempt from the actual client is denied while the room remains quarantined.

No injected failure occurs inside the tested network inspect/resolve publication itself. The prior publication-failure and no-durable-copy reports remain historical coverage for the unchanged implementation, not additional actual-client fault coverage in this run.

## 5. Unrelated viewer, process recovery and exact cleanup

After Beta's single withdrawal, its original independent remote lease deadline, menu and 16-stored/one-held balance are asserted every active test tick. Sending Beta the other actor's receipt does not close that view. It remains open during receipt expiry, all refusals and Alpha's successful exact confirmation, until the fixture explicitly directs closure.

Alpha's resolved A snapshot ends at generation three. Its C column retains conflicting primary generation three and journal generation two, with the original pending intent. No force-clear or marker deletion occurs. An explicit full save at the terminal fixture boundary persists player state; direct physical region inspection then verifies the chest contents and identities. The seed process terminates with `Runtime.halt(77)` before normal shutdown, after the successful confirmation and explicit save/read-back. This is not a cut during an unfinished operator reset or a physical power loss.

The independent server first reads the physical region before loading destination chunks, verifying the resolved A state and retained C conflict. Fresh actual clients connect. Beta reopens its remote, sees 16 named emeralds through the network menu and still holds its one withdrawn item. Alpha's old-process receipt is rejected; fresh command inspection confirms no pending intent for A and continued conflict refusal for C.

The final raw checks establish **13 A-generation-three diamonds, seven C-primary-generation-three gold ingots with C-journal-generation-two still retained, 16 stored emeralds plus one in Beta's saved inventory, two original room/cell/owner identities and eight physical chest identities**. Other checked chest slots are empty. Name/component changes preserve counts; this does not test atomic transfer across player and chunk files.

After final view closure and 650 drainage ticks, destination cache, actual engine-owned requests, pending unloads, callbacks, repair/in-flight work and unfinished returns are empty. The original final shutdown audit records `exact_engine_owned_tickets=0 jobs=0`, with empty pending/callback queues. Required snapshots, completed/pending reset markers and the intentionally conflicting evidence remain on disk.

The first leg records 56 completed stage lines before its terminal cut check; the second records 11, including final cleanup. These are sequential checkpoints within one scenario, not 67 independent suites. The terminal cut occurs before the normal stage-counter increment, and buffered tick rows stop shortly before the terminal raw verification.

Scope boundary: initial safe exit commands and independent process/item recovery are exercised. This new scenario does not inject unsafe landing geometry, kill/reconnect a client mid-command, run a new post-teleport recovery cut, or add command-path publication-failure races. Those different prior tests are not claimed as new coverage.

## 6. Original fixture failure and narrow correction

Initial source `162db270dcbca7b4da54725a825761234ef7a057`, run `44034500757`, compiled and reached real client visits, exits, the unrelated view and its conserved withdrawal. It then failed before the first operator command:

```text
eligible-A-failed-primary: NullPointerException
RepairClientProbe.record(long) is null
```

The new fixture built its later steps during server startup, before room allocation. Helpers captured the then-zero primitive chunk keys. Lambdas reading the eventual fields did not repair those already captured values. This was a fixture setup defect, not an operator/storage algorithm failure.

Commit `fe824e05a479a5bee9d41c04fa0eefacb72bc67f` builds those steps only after the real clients are ready and room keys have been allocated, and checks that the two target columns are distinct. Restart steps likewise bind the restored context. Item, identity, refusal, expiry, quarantine and cleanup assertions are unchanged. No command or inventory mutation was blindly retried. Only the new fixture file changed; the original failed archive and source remain separately retained.

## 7. Resource bounds and exclusions

Successful runner: AMD EPYC 9V74, four guest logical CPUs, about 15.61 GiB visible RAM, Microsoft virtualization, Ubuntu26 image 20261111.196.1/Linux7.0.0-1019-azure. Temurin21.0.13+9, Minecraft1.21.1/NeoForge21.1.249. Server Xms256m/Xmx2048m/G1 and ergonomic processors two (not OS CPU affinity), view three/simulation two; clients use software GL and 1,000-MiB heaps.

| Process leg | Last elapsed resource sample | Peak server RSS | Peak combined process groups | Minimum host available |
|---|---:|---:|---:|---:|
| Seed | 102.806 s | 961.7 MiB | 3,406.1 MiB | 11,857.9 MiB |
| Independent restart | 78.358 s | 874.7 MiB | 3,333.2 MiB | 11,900.1 MiB |

The new fixture separates readiness from gameplay: server startup has a 150-second launcher ceiling; the initial two-client ready gate has a 3,600-tick ceiling, while subsequent gameplay stages have 900 ticks and command response observation has 240 client ticks. The ready stages actually completed at server ticks759 and742. The natural receipt-expiry wait does not change the operator's 600-tick lifetime. Each process leg has a600-second wall ceiling, with3GiB server RSS/6GiB process-group RSS stops and2GiB minimum host availability. Private native directories and unexpected client-exit supervision remain. No final guard fired.

RSS is sampled at about1Hz beginning after server readiness, not a continuous peak or live-retention census. Recorded event medians/p95/maxima were1.321/11.547/424.487ms in seed and0.961/6.218/393.905ms after restart. These whole-leg distributions include startup and loading and exclude lowest-priority fixture actions and final CSV writes; they are not command-latency, end-to-end tick, FPS or TPS claims. No causal diagnosis of those maximum ticks is attempted here. The seed hard cut leaves periodic rows after tick2060 buffered; terminal raw verification at2072 is preserved separately. The 650-tick final cleanup and engine audits are not inferred solely from a final resource sample.

This is one bounded acceptance run after correcting one fixture defect, not a paired performance experiment, long soak or inspection-flood test. Existing operator parsing/receipt quotas remain but were not newly stressed to their capacity.

## Decision and remaining limits

**Keep this experimental. No installation, production integration or replacement beta.6 artifact.** The previously missing client command parser/permission/receipt/confirmation path now has direct runtime evidence, including a real-client-visible successful response and independent persisted-data verification. No production or existing operator/repair code fix was needed.

The commands are not available in installed beta.6. A polished operator interface, durable audit policy, stronger long-lived actor/session semantics and broader concurrent inspection traffic remain unfinished. A matching retained snapshot is not proof that an entirely unrecorded newer edit never existed. The conservative ambiguous/conflicting refusals remain intentional, and old experimental binaries that ignore `.reset` metadata remain unsupported.

No durable newest primary copy and no durable newest journal copy still means the newest edit cannot be recovered. Player, chunk, ledger and POI files remain non-atomic; selected successful-save cuts do not create atomic cross-file inventory transactions. Physical disk failure, marker rollback/removal, arbitrary direct-writing mods, hostile concurrent filesystem modifications and permanently hung I/O are not newly covered. No markers or snapshots were deleted to satisfy cleanup. Live see-through portals remain unimplemented.

No execution/access blocker remains for this completed bounded task, and nothing is needed from Anya.

Targeted successful run: https://github.com/WeepingAngeIs/zSortange/actions/runs/44034903454
Original failed run: https://github.com/WeepingAngeIs/zSortange/actions/runs/44034500757
Tested source: https://github.com/WeepingAngeIs/zSortange/commit/fe824e05a479a5bee9d41c04fa0eefacb72bc67f

The evidence kit retains both CI archives unchanged, exact source, observed command traces/screenshots, resource data, the narrow fixture correction and read-only verification/analysis scripts. It contains no executable JARs, native libraries, player worlds or decompiled engine sources.
