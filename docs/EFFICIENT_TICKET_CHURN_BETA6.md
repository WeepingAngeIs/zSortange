# Beta.6 efficient per-pocket eligibility and maximum-cap churn

Verified November 16, 2026. **All six final jobs passed on the exact same source. Keep the candidate experimental; no replacement playable JAR is required.** Warmed remote-view tick/CPU measurements improve in both final pairs and loading decreases deterministically. Memory peaks reverse direction between pairs and cold pauses remain, so no production optimization is promoted.

No user PC/profile, installed JAR, Java arguments/settings, player world or live server was accessed or changed. Compilation and disposable Minecraft worlds ran on GitHub. No portal backend was installed.

## Exact provenance

Starting report checkpoint `1d888b940ede0f942519ebe7b7282dcb8ecfc900`.

**Measured executable source `cb41aca54fb5e8cfc1d194b6b09bb03e457a8970`**, branch `feature/elemental-pockets`.

**Workflow 43847949879**, `Efficient per-pocket selection and maximum-cap churn`:

| Lane | Job | Result |
|---|---:|---|
| Churn baseline-first | 133232802833 | Passed |
| Churn candidate-first | 133232803370 | Passed |
| Actual-client room construction with candidate | 133232803427 | Passed |
| Actual-client paired transitions/shutdown/interrupted recovery | 133232803304 | Passed |
| Actual-client cold recovery baseline | 133232803245 | Passed |
| Actual-client cold recovery candidate | 133232803466 | Passed |

The final job completed 2026-11-16 10:36:01 UTC. This documentation-only follow-up is not a new executable test. Automatic ordinary regression workflows are not counted as new coverage.

SHA-256:
- Unchanged playable beta.6: `5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11`.
- Separate efficient experiment: `6aa9f312cc2b526a86b2122510d50021845a9b12aa8039298af2012748d30bcd`.
- Separate client acceptance fixture: `ce780484ceada6ebe30c52fe56106d611bfe69669d29eeba9fc1aba5566bbe13`.

All six source snapshots and experiment hashes agree. All 74 retained src/main entries match the previous beta.6 reference. Runtime loading verifies the packaged playable JAR. The room, transition, cold-recovery and client-action Java fixtures are unchanged from the preceding checkpoint. Formats, item contents/identities, art/UI, default-off gates, damage suppression and the non-live preview remain unchanged.

## Scoped efficient candidate

`EligibilitySnapshot` calculates a fresh decision at **each synchronization**, using two bounded lease passes, one record lookup per active pocket and occupant checks. Per-key policy uses set membership instead of repeatedly scanning all leases/players through reflection. This is not a cross-tick authorization cache.

Only READY, remote-only, unoccupied destination keys can use region-ticket argument 0 rather than 2. Pending players, visits, expired/stale leases, preparation, missing/unfinished records and physical occupants retain original strength. Released visitors waiting for recovery remain occupants. Home/recovery requests stay argument 2. Desired keys, permissions, range and lease deadlines are unchanged.

Rank changes add the replacement before removing the exact former rank. Actual engine-owned ticket identities/levels are audited, including ordinary release and stop. The code remains in the separate CI-only experiment, never the playable JAR. Typed mixin inputs remove per-key reflective eligibility scans; read-only auditing/telemetry still have overhead.

The pure selector oracle passed **6,000 deterministic snapshots / 53,990 assertions**. These are property tests, not integration/client counts.

## New maximum-cap load scenario

Two counterbalanced paired jobs, four fresh-world/JVM cases, approximately 5,732-5,738 measured ticks (286.6-286.9 seconds) per case. Heavy jobs use **17 ordinary server-player fixture identities with embedded packet sinks, zero real client processes/network sessions**, at most eight active pockets and 16 leases. Separate client jobs below use genuine clients.

Virgin rooms are first prepared by visits. Three mixed cycles then switch eight guest users among visits, partial departures and remote views while eight owners retain independent remote access. Lowered admission limits preserve existing sessions but deny a new distinct pocket. A ninth finite contender is admitted after capacity is freed while other views remain usable. Revocation/denied reopening, guarded named-item withdrawal/redeposit and eight simultaneous unsafe-return cases are checked. Fixture obstacle repair allows the existing production retry to return users, not a substituted fixture teleport.

All four cases passed:
- Maximum active pockets 8, leases 16, pending entrants 16; peak logical requests 146 including temporary recovery requests, versus 136 ordinary eight-pocket requests.
- Nine rooms prepared, **58,824 shell writes**, at most **256 shell writes/tick**, no warm regeneration. This excludes chest/exit finalization, generation, lighting and saves.
- First owner/guest pair admitted after 27-29 ticks; last after 209-211 ticks. Ninth contender completes 27-28 ticks after capacity is freed. This is finite-case evidence, not fairness under unlimited arrivals or an automatic full-cap queue.
- Eight unsafe returns receive first attempts over eight consecutive ticks, at most one attempt per tick and 100 ticks between a user's retries. Four cases record 128 attempts, four per guest including the final repaired return. Independent remote deadlines remain unchanged.
- Named samples and physical owner/pocket identities pass guarded withdrawal/redeposit at five verification windows per case.
- Each candidate case performs **1,696 exact existing-key rank transitions** with engine audits.
- After final 650-tick drainage: zero requests, applied engine keys, destination caches, leases and unfinished returns.

Selection stays within 32 lease visits and eight record lookups per synchronization, at most four synchronizations in one tick. Warmed owner-remotes use 16 lease visits/eight lookups, with median selection times 3.76 and 4.33 microseconds per synchronization. These medians are not worst-case bounds.

## Measurements

Preselected warmed remote summaries take the trailing 300 ticks of each of three post-transition 500-tick remote phases: 900 correlated observations per case, not 900 independent trials. Mixed windows use their final 100 ticks. Initial/cold/recovery phases and whole-case peaks are retained separately.

| Eight remote-only pockets | Baseline | Candidate |
|---|---:|---:|
| Logical requests | 136 | 136 |
| Raw destination cache | 7200 | 5408 |
| Ready full chunks | 512 | 128 |
| Block-ticking-ready | 288 | 0 |
| Entity-ticking-ready | 128 | 0 |
| Actual tickChunk calls/tick | 0 | 0 |

Remote cache reduction **24.89%**, full-ready reduction **75%**. These are not RAM/CPU percentages. Readiness is not execution; baseline remote-only chunk ticks were already zero.

Four visited/four remote pockets: cache **7857 -> 6961**, full-ready **740 -> 548**, block-ready **468 -> 324**, entity-ready **260 -> 196**, actual ticks **196 in both**. Eight visited: both policies **8481 cache, 968 full-ready, 648 block-ready, 392 entity-ready and 392 actual chunk ticks**. Whole-run cache peak **8481 in all cases**, so visitor-driven peak loading is not improved.

| Paired runner | Policy | Warm event median/p95 ms | Thread CPU mean ms/tick | JVM core equivalents | Whole-case RSS peak MiB |
|---|---|---:|---:|---:|---:|
| EPYC 9V74, first | Baseline | 1.834 / 2.014 | 1.778 | 0.0882 | 1547.0 |
| Same runner, second | Candidate | 1.150 / 1.384 | 1.187 | 0.0749 | 1910.6 |
| Xeon 8370C, first | Candidate | 1.461 / 1.821 | 1.524 | 0.0767 | 1460.8 |
| Same runner, second | Baseline | 1.904 / 2.162 | 1.956 | 0.0840 | 1606.4 |

Observed warmed event medians improve **37.3% and 23.3%**; process CPU rates improve **15.1% and 8.7%** within pairs. Pocket-service medians are 0.0970 -> 0.1095 ms and 0.1674 -> 0.1660 ms. Both policies are instrumented; fewer ready chunks can reduce observation cost. No uninstrumented universal FPS/TPS gain is established.

Candidate RSS peak rises **23.5%** in one pair and falls **9.1%** in the other. Sampled heap peaks similarly move 1033.2 -> 1241.1 MiB and 1036.0 -> 948.3 MiB. Whole-case process CPU is 77.06 -> 70.40 seconds and 102.51 -> 70.95 seconds. These are two paired observations, not a memory guarantee.

Cold-preparation event maxima remain **732.4 -> 760.8 ms** and **930.7 -> 817.5 ms**. Initial cold-to-remote medians also differ markedly: baseline 49.724/49.772 ms, candidate 1.288/1.622 ms, but the first candidate p95 remains49.826ms. These transition tails are not presented as a universal speedup or causally attributed. Further allocation/retention and lower-observer-overhead profiling is warranted.

## Required real-client regressions

All use two actual Minecraft client JVMs over localhost TCP, generated development launchers loading the packaged JAR, plus fixture-directed setup/actions. No simulated connection substitutes for these clients. Candidate binary hash matches the heavy workload.

- **Room candidate:** unchanged owner/delegated construction, storage rights, added physical chest/270-slot view, one fresh-revision deposit, conservation, socket interruption and separate restart pass. This scenario remains in the stronger visited path, with zero rank transitions; it does not alone prove weaker-ticket transitions.
- **Transition pair:** both policies complete three process legs. Remote-to-visit, visit-to-remote, overlapping/shared viewers, denied/revoked stale actions, real remote and visiting client SIGKILL/reconnect pass. Same-hub policy revision still invalidates every old view including the owner's, which explicitly reopens. Active stop audits **34 requests -> zero actual engine-owned tickets**. A separate Runtime.halt(77) after return teleport but before saved-position confirmation retains the return record and recovers on restart. Ten named diamonds stay stored plus one held; 17 named emeralds remain. Final cleanup is zero. Candidate seed records192 rank transitions and post-cut32.
- **Cold baseline/candidate:** genuine absent home/fallback, both unsafe retries, unresolved stop/restart and repaired landing pass. Thirty of32 audited recovery loads have absent targets at method entry; all retain argument2. Each lane includes three real client SIGKILLs. The existing fixture stages login elsewhere then restores the intended fallback to ensure genuine coldness; this is an explicit test condition, not ordinary-login behavior. Independent deadlines, samples and room identities persist.

Selected cuts do not solve Minecraft's non-atomic player/region saves. Eight-user recovery contention is fixture coverage, not eight actual connected clients.

## Environment and safeguards

Heavy paired runners: EPYC9V74 and Xeon Platinum8370C, each four guest logical CPUs/~15.61GiB RAM, Microsoft virtualization, Ubuntu26 image20261111.196.1, kernel7.0.0-1019-azure. Compare within-host pairs, not CPUs against each other.

Temurin21.0.13+9, MC1.21.1, NeoForge21.1.249. Heavy heap256/3072MiB, G1GC, ergonomic processors2 (not OS quota/pinning), view3/simulation2, flatseed860612, spawnchunks0. Fresh JVM/world per case, reused libraries/OS caches, no forced GC. Heavy lanes contain no full-pack dependencies.

Real-client regression heaps: server2048MiB (room1400MiB), clients1000MiB, softwareGL25FPS. OPAC0.33.0/CarryOn2.2.6.13 are present with retained hashes, not current-user-pack certification.

Per-tick event/thread/process/service/selector/write metrics; chunk readiness every20ticks; actual tickChunk calls separately counted. Event bracket includes fixture work and excludes final sampler work. Process CPU includes audits/GC/async work and excludes clients. RSS/available host RAM sampled1Hz; group RSS is not PSS. No resource guard fired. Heavy cutoffs:5GiBRSS, <2GiB host available,92%heap,15000cache,900s/case. Lowest sampled available host memory **13138MiB**. These are sampled observations, not continuous maxima or production guarantees.

## Retained failures and decision

43844314443: fixture requested remote storage before first visit; corrected only setup ordering. 43845054668: fixture compared a newly admitted Visit with a deliberately closed Remote deadline; corrected only that expectation, retaining independent live-view checks. 43846748483: transition Alpha failed before login loading liblwjgl_stb.so; final launcher gets per-case/leg/reconnect private extraction/temp directories, loader diagnostics and supervision of both clients, preserving planned SIGKILLs. The exact native extraction race is not proven. No item-transfer retry or production assertion/permission relaxation was introduced.

Only final source/workflow43847949879 supplies primary results; older passing subsets are not pooled. No production defect was identified by these cases.

**No new playable artifact.** Deterministic loading and warmed CPU/tick benefits are useful, but memory reversal, observer cost and cold pauses remain unresolved. Next independent phase: bounded lower-overhead allocation/retention and cold-to-remote profiling, before considering native production integration. No human test is needed for that work. More real clients, long soaks, arbitrary movers/graves, full-pack settings and broader crash boundaries remain unverified. Live see-through portals remain unimplemented; fallback unchanged.

## Retained artifact IDs and ZIP hashes

- Churn baseline-first14215788829: `0f625b11e0b228ecd55b69cd82ac61b7905755bd07fc20c4de2bfd6c90705308`
- Churn candidate-first14217001504: `2fabd325722e3c15c0c9cb3733132c8890c2ced3f3a8011ca3bc5cdd4510cef9`
- Room candidate14216731000: `915772acb2e070742d3db4b9fdbb49f73d5ab28581c41166027589de6335d9b6`
- Transition pair14216188824: `bf90c4fd390386ff5cc3d0565f2453f180c0d98b167d7e3daafbce77460511b6`
- Cold baseline14217360247: `ec2e742d1f8a52b78ea759f9def95b1e7f89022b5285dc8b447f28ba52ad669c`
- Cold candidate14216897102: `95d21798abd3a4a9306d9ec0a2f94d2d9a4e1f08df8ac948f915188148002469`

Workflow: https://github.com/WeepingAngeIs/zSortange/actions/runs/43847949879
Measured source: https://github.com/WeepingAngeIs/zSortange/commit/cb41aca54fb5e8cfc1d194b6b09bb03e457a8970
