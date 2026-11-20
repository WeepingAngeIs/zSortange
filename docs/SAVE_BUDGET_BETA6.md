# Beta.6: bounded, save-preserving unload serialization

Verified November 16, 2026. **All nine final targeted jobs passed on one executable source commit. The candidate remains experimental; the playable beta.6 JAR is unchanged and no replacement is required.**

No installed profile, user Java arguments/settings, real save, user PC or live server was accessed or changed. All Minecraft execution used disposable GitHub infrastructure. This phase follows the minimal-ownership report at `d49ebcb025216990a82f5f7324d809f91dfe7770`.

## Exact source and CI

**Measured source:** `d8a3033c84492dedc2d3d7d5ab2d895fd3fcfa04`, branch `feature/elemental-pockets`.

**Final workflow:** `43963311525`, Bounded save-preserving serialization budget. Completed required client lifecycle at 2026-11-16 21:55:12 UTC.

| Required lane | Job ID | Result |
|---|---:|---|
| Baseline-first lean pair and separate diagnostic | 133636116166 | Passed |
| Candidate-first lean pair and separate diagnostic | 133636115864 | Passed |
| Four dirty unload/reactivation cycles per policy and restart | 133636116205 | Passed |
| Shutdown and explicit force-save with dirty backlog, then restart | 133636116008 | Passed |
| Preparation write/POI/flush faults, delay, cancel and revoke | 133636115996 | Passed |
| Selected preparation cuts and independent restart | 133636115914 | Passed |
| Two actual clients: room construction and restart | 133636115745 | Passed |
| Two actual clients: cold/unsafe recovery | 133636115845 | Passed |
| Two actual clients: transitions, active shutdown, interrupted return | 133636115430 | Passed |

SHA-256:
- Playable beta.6: `5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11`.
- Separate budget experiment: `838f363cf2a7e23e2eb336491f9e8dea1ec3eea9ab8f30f0d889c268839834a3`.
- Existing remote-ticket experiment in client regressions: `6aa9f312cc2b526a86b2122510d50021845a9b12aa8039298af2012748d30bcd`.
- Existing client fixture: `ce780484ceada6ebe30c52fe56106d611bfe69669d29eeba9fc1aba5566bbe13`.
- Exact measured source ZIP: `d355a66b3e7452fd2d0c54a3effb5c3f9003df95153840194a822d836c9ceaca`.

All 74 production source/resource files, 12 remote-selector files, 15 prior preparation files, 11 prior minimal-module files and 11 acceptance files match the preceding source. New code is isolated in budget source sets and launch wrappers. A budget-specific dirty-test class is substituted only in this test module; the old fixture remains available unchanged. A report-only follow-up does not change the measured executable.

## 1. Budget admission, never discard owned saves

The new module retains the minimal stale-ownership guard and the previous nonblocking durable-preparation implementation. Its new behavior affects only the pocket dimension's normal `ChunkMap.processUnloads` admission.

Before polling the engine callback queue, it checks a shared per-server-tick allowance: **256 callbacks, 32 chunk serializations and an 8-ms soft work-time limit**. Multiple calls within one tick share the same allowance. On exhaustion it returns no callback **without polling or removing an entry**. Every callback actually taken executes once, to completion. The normal incremental-save path after the callback loop shares the allowance; denying that attempt does not invoke the original save or clear its dirty flag.

There is no additional callback queue, copied snapshot store, rewritten save future, changed generation reference or off-thread world access. The engine's readiness, exact-holder ownership, save/unload order, event dispatch and asynchronous I/O behavior remain. The previous defensive queue is excluded from the built archive.

Explicit `saveAllChunks`, force-flush paths and server shutdown bypass the budget. Their original save/flush obligations remain, even when this makes a stop or explicit save slow. A thrown callback exception is not swallowed. The pure owner-thread budget test passed **8,354 assertions** covering FIFO retention, same-tick accumulation, elapsed/count limits, callback errors, bypass and off-thread rejection. These are not multiplayer tests.

**The 8-ms allowance is not a worst-case tick guarantee.** One callback or serialization can exceed it because admitted work is not interrupted. Unrelated engine work and explicit saves are outside the allowance. Count bounds were verified for this supported workload, not arbitrary third-party save events. Initial movement of holders into pending-unload state is not made asynchronous or globally bounded by this patch.

The prior preparation fence still requires each intended chunk and changed-exit-POI write receipt, both force-flush completions and final current authorization/identity checks before READY. Visitor/home/recovery ticket strengths, fresh permission decisions and independent viewer deadlines are unchanged.

## 2. New dirty-backlog shutdown and explicit-save evidence

A dedicated fixture creates eight READY rooms, inserts 13 named diamonds in the first physical chest of each (104 total), verifies dirty flags and returns its users. There is no manual full-world save after insertion. With a one-serialization-per-tick test setting, it waits until sample-bearing chunks are genuinely dirty and owned by `pendingUnloads`, with callbacks still queued and prior budget yields observed.

- **Normal stop:** the stop was requested with six dirty sample chunks and **4,916 queued callbacks** still present. The fixture did not pre-drain or call `saveEverything`. Shutdown bypassed the normal allowance, executed the engine path, and closed with empty pending/callback queues. The budget stop record counted 8,378 unmetered callback executions over shutdown; these are invocations, not a distinct-chunk count.
- **Explicit save:** the force-save case started with six dirty sample chunks and **6,675 queued callbacks**. The actual `save(true)` path bypassed the allowance. After exact queues drained and the explicit region flush completed, the process was cut with `Runtime.halt(77)` before clean shutdown could rescue data.

Independent server launches after each case recovered exactly **104 named/component-correct items, eight original room/cell/owner identities and 32 physical chest identities**, with other checked slots empty, then drained again. This is stronger than stopping an already idle world.

These cases use server-player fixtures with simulated packet sinks, not actual connected clients. The limit of one is fixture-only; normal measurements use 32. Shutdown/explicit saves intentionally remain potentially blocking.

## 3. Repeated dirty cycles and the first fixture failure

Both policies completed four dirty unload/reopen cycles, alternating a held owned save and reactivation of the same holder. Each cycle verifies the preceding 104 items, updates only their test names and checks dirty flags. The final successful flush is followed by `Runtime.halt(77)` and independent restart. No full-world save is invoked after sample insertion; ordinary engine saving is not disabled.

The initial run **43961972110**, source `090d8dd683cbbbbf6507c04ca0f6f6b589859b63`, failed its dirty lane because the inherited fixture required a second callback invocation within 20 ticks. This encoded the prior busy-retry timing. The new budget intentionally changes service latency.

The budget-specific fixture now checks exact pending ownership and the held sample **every tick**, retains a minimum 20-tick hold and requires an eventual second owned callback before release, with a hard 400-tick deadline. It logs the state at tick 20. The final run showed `pending_owns=true`, one attempt and **6,635 callbacks ahead/in the queue** at that observation. The held reference was released only after service returned at **212 ticks**; the later held cycle required 29 ticks. Reactivation cycles retained their original identity/ticket checks.

No inventory, save, identity or final cleanup assertion was removed, and no mutating transfer was retried. The longer hold is explicit synthetic test input, not a natural generation-reference lifetime claim. Both policies independently recovered the same 104 items after all four cycles. Cache-zero alone is not used as proof of save completion: actual pending and callback queues plus flush results are checked.

## 4. Controlled lean comparison to the previous minimal candidate

Four fresh-world/JVM cases reverse order on two separate runners. Both use the prior minimal ownership guard, unchanged nonblocking preparation and original strong tickets. The efficient remote-ticket experiment is absent. Both sides collect the same new counters. Only budget enablement differs.

Each prepares eight pockets for eight server-player fixtures, visits, returns, remotely views for 400 ticks and drains for 650 ticks. **Zero real Minecraft clients run in these load cases.** Separate actual-client jobs are described below. All drainage ticks are included; p95 uses linear interpolation.

| Within-runner measurement | Minimal baseline, no budget | Budget enabled |
|---|---:|---:|
| Pair A, baseline first: maximum drainage event tick | 1,197.230 ms | **31.077 ms** |
| Pair A: drainage p95 event tick | 1.848 ms | 7.772 ms |
| Pair A: first tick offset with requests/cache/pending/callbacks all zero | 32 | 197 |
| Pair A: peak serializations in one drainage tick | 3,626 | **32** |
| Pair A: sampled whole-case RSS peak | 1,357.1 MiB | 829.8 MiB |
| Pair B, candidate first: maximum drainage event tick | 986.602 ms | **26.514 ms** |
| Pair B: drainage p95 event tick | 2.416 ms | 8.791 ms |
| Pair B: first fully drained tick offset | 28 | 196 |
| Pair B: peak serializations in one drainage tick | 3,274 | **32** |
| Pair B: sampled whole-case RSS peak | 1,213.8 MiB | 1,055.9 MiB |

The large bursts are spread into smaller pieces. **The tradeoff is longer drainage and a higher drainage p95**, not uniformly faster ticks. Complete cleanup occurs 165/168 ticks later, about 8.25/8.40 seconds at 20 ticks per second. Peak pending callbacks rise from 2,000 to 7,176/7,170 while queued work is preserved. All four cases execute exactly **7,200 polled callbacks during final drainage**, with polled and executed counts equal, then reach zero actual queues and requests.

Whole-case candidate event maxima remain **58.752 and 89.896 ms** outside the worst drainage values. Normal candidate callback count never exceeds 256 and charged serialization count never exceeds 32 in the observed workload. Charged work itself can overshoot 8 ms when one admitted operation runs long; the candidate's lean drainage work peaks are about23.5/23.8ms.

Both sides peak at **8,481 raw cache entries and 140 logical requests**. This change does not reduce the maximum number of loaded/cache objects by weakening tickets. RSS is lower in these two samples, but previous pairs reversed and these are neither live-object retention measurements nor proof of a general memory reduction.

Total measured JVM CPU between first and last CSV samples is **36.08 -> 37.02 seconds** in Pair A and **37.05 -> 35.73 seconds** in Pair B. This is primarily latency redistribution, not demonstrated universal CPU savings. During drainage, cumulative server-thread allocation rises from **1.777 -> 2.114 GiB** and **1.615 -> 2.141 GiB**. Those are repeatedly allocated/reclaimed bytes, not concurrent RAM usage.

The earlier minimal report had lean candidate drainage peaks of422/388ms on different runs. Do not subtract those historical values from these new candidate values as a controlled speedup. The paired baselines above reproduce the same kind of burst under this phase's host/timing conditions and shared accounting overhead. Save counts can change between phases because scheduling changes when incremental dirty snapshots are taken; equal callback counts and persisted-data checks are the safety evidence, not equal total serialization counts.

### Separate diagnostic cases

One separately instrumented candidate case per runner also passes. The original approximately1,900-serialization burst is no longer present: the observed per-tick limit remains32. Maximum aggregate serialization spans during drainage were **31.459 and27.978ms**; a single operation is not preempted. Diagnostic drainage event peaks of110.664/122.185ms occur at rows with zero serialization calls, so they do not establish a remaining bulk-serialization spike. JFR/event setup and other work are not exclusively attributed here. These diagnostic values are not pooled into lean comparisons and do not imply every pause is solved.

## 5. Required exact-candidate regression coverage

All nine jobs compiled the same source and verified the same playable/experiment hashes.

### Fault/cut fixtures, real storage workers but not network clients

The preparation fence still rejects injected chunk-write failure (16 observed write failures), changed-exit-POI failure (one), flush failure (one), delayed publication, logout cancellation, revoked authorization and changed reanchor epoch. A delayed publication case is not a physically hung disk. Uncertain BUILDING data is not automatically regenerated.

Clean shutdown with publication pending and process cuts before flush, after flush/before READY and after READY/before entry are followed by independent server launches. Checked room identities, physical inventories and exit-POI persistence remain valid. These are selected boundaries, not arbitrary crash atomicity.

### Two actual Minecraft clients over localhost TCP

Each client job uses two genuine Minecraft client JVMs with generated launchers loading the packaged beta.6 plus the exact budget module. Private native-extraction directories and process/resource supervision remain. Server fixtures seed conditions and direct client actions; these are not human, WAN or full-pack tests. The unchanged efficient remote-ticket experiment is also present in these jobs, unlike the load lane.

- **Room:** delegated Build/storage permissions, protected boundaries, added physical chest and270-slot view, one conserved deposit, actual client-process interruption/reconnect, safe return/reanchor and separate restart pass.
- **Cold recovery:** truly absent home/fallback chunks, both-unsafe queued retries, unresolved shutdown/restart, repairing a landing and automatic return pass. The explicit fixture login-spawn staging needed to keep fallback cold remains. Original strong recovery tickets remain.
- **Transitions:** remote/visit transitions, independent/shared viewers and deadlines, unchanged same-hub policy-revision invalidation, revoked stale actions and actual client-process SIGKILL/reconnect pass. Active server shutdown and a forced return cut after teleport/before saved-player confirmation recover with original room/cell/owner identities, ten named diamonds stored plus one held and17 named emeralds. Final actual engine tickets, pending callbacks, cache and return records are empty.

OPAC0.33.0 and Carry On2.2.6.13 are present in the client fixtures, not certification of the user's current pack or arbitrary grave/mover combinations.

## 6. Bounded environment and measurement limits

Both primary runners report AMD EPYC7763, four guest logical CPUs and15.61GiB visible memory, Microsoft virtualization/Linux7.0.0-1019-azure. Separate sequential pairs stay on their own runner, with order reversed across runners. Java Temurin21.0.13+9, Minecraft1.21.1, NeoForge21.1.249, Xms256m/Xmx2048m/G1, ergonomic processors2 (not OS CPU pinning), view3/simulation2, flat seed860612 and no spawn tickets.

Primary processes lasted88-89seconds. Ordinary process cap180seconds; dirty/backlog cases300seconds. Existing limits retain4GiB sampled server RSS, at least2GiB host memory available,92%used heap and15,000cache entries. Dirty fixture ceiling3,800ticks and held-callback ceiling400ticks; backlog fixture ceiling2,600ticks. No final resource/time guard fired. Minimum available host memory in the four primary cases exceeded13.36GiB.

Per-tick elapsed/CPU/allocation/heap/callback telemetry retains hooks and existing preparation auditing. Final CSV and lowest-priority post-tick fixture actions are outside the elapsed bracket. RSS/host sampling is1Hz, not a continuous maximum. No forced GC or OS-cache clearing; JVM/world fresh per case, libraries/OS caches reused. Two pairs and correlated ticks are not a sustained maximum-load soak or universal FPS/TPS result. Separate JFR recordings are bounded96MiB/90seconds. Baseline `forced_callbacks` means unmetered because the budget is disabled, not that every baseline callback came from an explicit save.

## 7. Risks and next decision

**Retain the experiment. No replacement playable artifact.** The budget preserves queued work and materially reduces the observed peak, with dirty-backlog shutdown/forced-save evidence. It also increases dirty-data residence and pending queue size. Sustained incoming work, backpressure, long soaks, saturated-server fairness and time-to-durable-save need a further bounded test. It does not cap the engine queue by evicting saves.

Explicit saves and shutdown deliberately bypass the budget and can still block. A single large serialization or third-party event can exceed the soft time allowance. Never-released generation dependencies and physically hung I/O remain unverified. The implementation hooks pinned engine internals, not a stable cross-version API.

**General asynchronous unload-write failure semantics are unchanged.** The engine can report a failed write after marking a chunk clean and removing its holder. These preparation-failure tests do not demonstrate retries or recovery of arbitrary unsaved items after failed unload writes. The budget does not suppress those errors, but cannot promise data that never reached durable storage. Successful post-flush cuts do not make separate player/ledger/region/POI saves atomic.

No production data format, identity, inventory rule, artwork/UI, default-off gate, damage suppression or non-live preview changed. Live see-through portals and unrestricted machinery/container support remain unimplemented; no portal backend installed. Nothing is needed from Anya for further independent work.

Next useful bounded phase: sustained admission/backpressure with queued dirty saves, measuring durability delay and recovery fairness before any production integration. Do not drop a save or pretend shutdown completed to satisfy a budget.

Primary workflow: https://github.com/WeepingAngeIs/zSortange/actions/runs/43963311525
Measured source: https://github.com/WeepingAngeIs/zSortange/commit/d8a3033c84492dedc2d3d7d5ab2d895fd3fcfa04
Pinned engine audit: https://github.com/WeepingAngeIs/zSortange/actions/runs/43918683165

The evidence kit retains raw measurements/logs/JFR, screenshots, exact source, read-only analysis/verification scripts and artifact digests. It contains no executable JARs, native libraries, player worlds or decompiled engine sources. The initial failed fixture remains retained separately and is not pooled into final passing measurements.
