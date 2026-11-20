# Beta.6: reset-fence client lifecycle regression reconciliation

**The exact previously failed client job now passes on the same source, with identical runtime binaries and assertions. No executable change was needed. The original failure remains preserved. This is a targeted regression reconciliation, not another healthy-race test or production integration. No replacement playable JAR is required.**

Only GitHub's single-job rerun action was requested. No user profile, installed JAR, Java argument, setting, real world, PC or live server was accessed or changed. No force push or portal backend installation occurred.

## Exact provenance

Repository/branch: `WeepingAngeIs/zSortange`, `feature/elemental-pockets`.

**Tested source in both attempts:** `236f8dc1c3cbf87460266d922c7af5ff371b548a`, the experimental reset-fence fix.

**Workflow:** `44013963105`, Persistent latest-snapshot repair and quarantine.

| Evidence | Exact result |
|---|---|
| Attempt 1, transitions job `133807324801` | Failed at initial `two-network-clients` startup gate |
| Attempt 1, other eight jobs | Passed previously; not rerun for this reconciliation |
| Attempt 2, targeted transitions job `133815286413` | Passed, including all three client/server process legs |
| Attempt 2 actual job time | 2026-11-17 04:19:44 to 04:29:04 UTC |

GitHub displays the eight successful prior jobs under attempt 2 with new check IDs but retains their original 03:59-04:05 execution timestamps. They are inherited results, not eight newly executed jobs. The healthy-ordering workflow `44014043194` is not used as a substitute for this client result.

At inspection, the branch was at report commit `c9c1a081b54ffb036f119c8bf2a9de81b3f9ef2b`. Comparing that head with the tested fix found only a healthy-race workflow trigger and its report, not executable changes. This documentation-only follow-up is not another measured executable.

### Preserved artifact identities

- Original failure artifact `14298660366`: SHA-256 `93f9513277c4dc3f1cbd7fa9a8d9d6f941e8fed5deffacc612050ceee0bd44f8`.
- Targeted pass artifact `14299870794`: SHA-256 `eae731a99ef2a890e5a514362ded83a645b7b9ee0e6b36ea3b596cf2b9cbc14c`.
- Exact source ZIP in both artifacts: SHA-256 `4dbfc4ecae3f1b01c4767230065def2bde62788ca6706e3f4cda95ce4e868fdb`.
- Playable beta.6: `5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11`.
- Experimental repair module: `4de389234439e4176f5ea1c737281b54796b92527cb831119b063901877bcae4`.
- Acceptance fixture: `f08bff8c7af797538ad9583c989eed8accdfdb1ee867fc428296aac94ab79344`.
- Remote-ticket experiment: `6aa9f312cc2b526a86b2122510d50021845a9b12aa8039298af2012748d30bcd`.

The archives' source.zip and runtime-inputs.json are byte-for-byte identical across attempts. All loaded playable-JAR checks in the successful server/client processes match the expected beta.6 hash. The evidence kit retains the original and new archives unchanged plus a read-only verifier and measurements; it contains no executable JARs, native libraries or player worlds.

## 1. Original cause: startup deadline, before any storage action

The final error was:

```text
two-network-clients: Timeout at two-network-clients
active pockets=0; leases=0; Anya-owned ticket requests=0
```

The actual timeline is decisive:

| UTC timestamp, November 17 | Recorded event |
|---|---|
| 04:01:46 | Server ready and packaged-JAR check complete |
| 04:01:49 | Both client launchers start |
| 04:01:51 | Both begin OpenGL 4.6 negotiation |
| 04:02:04 | Both try OpenGL 4.5 |
| 04:02:19 | Both receive a 4.5 llvmpipe software-rendering context |
| 04:02:42 | Clients begin resource reload |
| 04:02:44 | Client font loading is still logged |
| 04:02:46 | Server's unchanged 1,200-tick initial join deadline expires |

Neither client logged its connection attempt or pre-connection packaged-JAR verification marker. The server recorded no player joining and no completed lifecycle checkpoint. Its repair summary is `writes=0 failures=0 repairs=0 resets=0 journalBytes=0 globalBad=false`. The reset method was not reached, so this trace does not show a reset-fence storage regression.

Classification: **runner/client-startup timing exceeded the fixture's join allowance**. The approximately 28-second graphics-context negotiation is an observed large part of the startup delay. The reason that negotiation/resource loading was slower on that runner is not instrumented sufficiently to assign an exclusive CPU, driver or I/O cause.

This is not the older missing-lib-lwjgl-stb startup failure. The private native hashes are present, and both clients proceed into mod/resource initialization. Narrator-library and invalid client simulation-distance warnings are followed by resource-loading progress, so the logs do not establish those warnings as fatal. No memory guard or unexpected client-exit assertion fired. Original sampled server RSS peaked at 823.1 MiB, combined process-group RSS at 2,765.2 MiB, with at least 12,485.3 MiB host memory available.

## 2. Narrow validation, no code workaround

The original artifact and digest were preserved before requesting `rerun_workflow_job(133807324801)`. One new disposable runner executed the same commit, launch scripts, production/experimental modules, client fixtures, assertions and deadlines. No startup timeout was lengthened, no lifecycle assertion was relaxed, and no item transfer was blindly retried.

On this retry, the initial graphics-context negotiation took about three seconds rather than 28. The server was ready at 04:21:05; both clients joined at 04:21:51, and the first checkpoint completed at tick 896, inside the original 1,200-tick allowance. The remaining scenario ran to completion.

No gameplay, experimental or fixture patch is justified by this evidence. The unchanged-source pass reconciles the reported regression. It does not permanently cure the harness's sensitivity to slow client startup, and no claim of universally reliable startup timing is made. A separate readiness/launch-coordination change could address that infrastructure fragility if needed, but was not added to expand this narrow task.

## 3. Exact successful lifecycle coverage

The job used **two genuine Minecraft client JVMs over localhost TCP**, plus replacement client processes after intentional interruptions. Three dedicated-server processes operated on the same disposable fixture world. It did not use fake network connections in place of these clients.

- **Seed:** 37 completed sequential checkpoints. Independent and shared remote views, three steady windows, repeat close/reopen, one conserved withdrawal, visitor joining/leaving a remote view, same-player remote/visit transitions and independent lease deadlines pass. Revoked stale actions and unauthorized reopening are rejected. Existing same-hub policy-revision invalidation is retained, including the owner's explicit refresh.
- **Socket interruptions:** the runner SIGKILLs the real Beta client process once while remotely viewing and once while visiting, then launches fresh clients with the same identity. Relevant leases release, visiting recovery completes and the unrelated view remains usable.
- **Active-session stop:** the server stops with Alpha remotely viewing and Beta visiting. The exact engine audit records `before=34 after=0`. Pending unloads and callbacks close empty.
- **After-stop:** six checkpoints complete, then the intended `Runtime.halt(77)` occurs after return teleport but before saved-player confirmation. The cut marker asserts `outside=true`, `returnRecord=true` and `engineTicketsAudited=true`. This intentional hard stop does not execute a clean-shutdown audit; the next process verifies recovery.
- **After-cut:** eight checkpoints verify reconnect, original room/cell/owner identities and contents, then independent and final cleanup. Alpha's destination unloads while Beta remains usable. Final requests, actual engine-owned tickets, pending/callback queues, destination cache and unfinished returns are empty.

All three terminal markers and the final CASE_PASSED marker are present. These 51 sequential checkpoints are one scenario, not 51 independent test suites. The fixture seeds worlds, owners, positions and samples; it is automated actual-client coverage, not human/WAN/full-pack certification.

Eleven named Alpha diamonds remain conserved as ten stored plus one held, and Beta's 17 named emeralds remain intact. The actual clients inspect those contents after both restarts. No production or player-world data participates.

### Important scope boundary

The successful normal lifecycle records zero explicit journal repairs and zero explicit journal resets. The fixed service is loaded and ordinary journal writes occur, but this lane is **not a new actual-client reset-race or injected failed-write test**. The deterministic reset-ordering evidence remains a separate result. This pass supplies exactly the previously missing client lifecycle regression, not every possible repair behavior.

## 4. Bounded resources

Successful retry runner: AMD EPYC 7763, four guest logical CPUs; original failed runner: AMD EPYC 9V74. Java Temurin 21.0.13+9, Minecraft 1.21.1, NeoForge 21.1.249. Server Xms256m/Xmx2048m, G1 and ergonomic processor count two; clients have 1,000-MiB heaps and software rendering. Dedicated-server view distance three, simulation distance two. The client simulation-distance request of two still produces its original range warning; it was not modified here.

| Successful process leg | Last elapsed resource sample | Peak server RSS | Peak combined process groups | Minimum available host memory |
|---|---:|---:|---:|---:|
| Seed | 273.1 s | 1,160.7 MiB | 3,622.4 MiB | 11,622.6 MiB |
| After-stop/cut | 52.7 s | 809.1 MiB | 3,084.6 MiB | 12,172.3 MiB |
| After-cut/final | 117.2 s | 900.0 MiB | 3,245.8 MiB | 12,027.6 MiB |

Resource sampling is once per second. Limits remain 600 seconds per network leg, 150 seconds for server start, 3 GiB server RSS, 6 GiB combined process-group RSS and at least 2 GiB host memory. The 1,200-tick per-checkpoint limit and actual client process supervision remain. No guard fired in the successful retry. These are acceptance costs, not a speedup, retention analysis or comparison against the early-aborted run.

Carry On 2.2.6.13 and OPAC 0.33.0 are loaded in the isolated environment and their exact hashes match the original run. This does not establish the user's installed versions or all integration settings. Private native/temp directories, no permanent pocket loading and shutdown/flush obligations are unchanged.

## Decision and remaining safety limits

**The specific red client lane is reconciled by a full unchanged-source pass. No code fix, installation or replacement artifact is needed.** The original failure is retained, and the eight other original passes are not presented as newly rerun coverage.

The intermittent pre-connection startup timing remains a fixture/environment fragility, not a proved reset defect. There is no remaining access/execution blocker for this task. GitHub single-job execution and artifact retrieval were verified directly. This documentation is the only repository addition from the reconciliation.

All existing production files, saved formats, identities, inventories, artwork/UI, default-off gates, damage suppression and the non-live preview remain unchanged. The experiment remains unshipped. No live see-through portal backend was installed.

No durable latest journal copy and no durable latest primary copy still means the newest edit cannot be recovered. Player, chunk, ledger and POI files remain non-atomic. The publication-to-compensating-relock crash/failure window, arbitrary direct writers, wider healthy multi-key races, full-pack behavior and physical disk hangs are not newly validated here. A passing normal-client lifecycle does not remove those limits.

Primary failed job: https://github.com/WeepingAngeIs/zSortange/actions/runs/44013963105/job/133807324801
Exact successful targeted job: https://github.com/WeepingAngeIs/zSortange/actions/runs/44013963105/job/133815286413
Tested source: https://github.com/WeepingAngeIs/zSortange/commit/236f8dc1c3cbf87460266d922c7af5ff371b548a
