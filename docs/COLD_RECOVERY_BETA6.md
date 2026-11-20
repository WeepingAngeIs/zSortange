# Beta.6 room-regression repair and cold recovery evidence

Verified November 16, 2026. **The specific room regression is repaired and independently green. New cold-home and unsafe-fallback recovery passes in both policy lanes. No replacement playable JAR is required.**

No user profile, installed JAR, Java arguments/settings, player world or live server was accessed or changed. No portal backend was installed. All setup mutations belong to disposable GitHub fixtures.

## Requested red run: exact diagnosis and repair

Run **43830237503**, source `450ab991f8c490cf1ea16a08915da0a242b30594`, failed at `Exercise room editing with two real client JVMs and restart`. Retained artifact **14208252930** contains the actual Beta failure:

```text
java.lang.UnsatisfiedLinkError: Failed to locate library: liblwjgl_stb.so
  org.lwjgl.system.Library.loadSystem
  org.lwjgl.stb.LibSTB.<clinit>
  org.lwjgl.stb.STBImage.<clinit>
  net.neoforged.fml.earlydisplay.STBHelper.loadImageFromClasspath
  net.neoforged.fml.earlydisplay.DisplayWindow.initWindow
```

Beta failed during native early-display initialization, before Anya client initialization/login. Alpha joined. The later `phase=0 ... Room phase timeout 0` occurred before any room edit. The orchestrator monitored Alpha's exit but did not promptly detect Beta's exit.

**Classification: native-loader test infrastructure plus fixture supervision, not evidence of an inventory/room production defect.** The generic architecture warning lists Linux amd64 and linux/x64; it does not establish a wrong architecture binary. The original log also does not prove an extraction race. Native path/cache interference is a plausible explanation, not a traced root instruction.

Commit **88eafb0157f0c4d86cf376d728e2775518149834** changes only the launcher script: each client/reconnect gets a fresh private `java.io.tmpdir` and `org.lwjgl.system.SharedLibraryExtractPath`, loader diagnostics, and supervision of all unexpected process exits with log tails. Planned SIGKILL and normal server stops remain explicit. No room assertion or item-transfer retry changed.

The exact scenario passed as **43834928927**, job **133189082882**, including actual clients and a separate installed-server restart. The playable and acceptance JARs match the originals. The three isolated `liblwjgl_stb.so` copies have the same SHA-256 `67bfb70131582e153aadb73f05463fe14d9da7d7d0590a9cdb6ecf7988a3aeba`.

Adding the new cold subscriber initially introduced a separate test-only packaging error: ordinary room runs lacked `ColdRecoveryAudit$Observation`. Commit **46cfc85d521afcc01cf2616070e0a25b7a562907** excludes the cold subscriber from non-opt-in fixture JARs and avoids resolving it in ordinary packet routing. It does not install an experiment dependency in ordinary runs or production.

The room workflow passed again on that source: **43837852585**, job **133198990372**. `RoomProbe.java` remains byte-identical to the originally failed source, including the single fresh-revision deposit. Automatic multi-pocket regression **43837852615** also passed; its unchanged cases are not new coverage.

## New paired cold-recovery workflow

Exact measured cold source **da9904adda3be023617768ce3438154f5d154614**.
Workflow **43837596506**, `Packaged unloaded-home and unsafe-fallback recovery`:

| Lane | Job | Result | Completed UTC |
|---|---|---|---|
| Baseline | 133198002580 | Passed | 2026-11-16 09:21:19 |
| Remote-only test candidate | 133198002119 | Passed | 2026-11-16 09:19:46 |

Each lane runs two actual Minecraft client JVMs over localhost TCP against the packaged beta.6 JAR. Generated development launchers load the JAR, not production class directories. No FakePlayer/embedded connection substitutes for these clients. There are 21 seed and nine restart checkpoints per lane, not 60 independent suites.

Alpha's home is in chunk (128,128), Beta's independent home in (0,0), and fallback spawn in (-64,-64). After each actual client SIGKILL, the fixture waits 650 ticks before reconnect. At the actual production `loadForRecovery` entry, read-only instrumentation asserts `getChunkNow == null`; afterward it checks a loaded chunk and the original recovery ticket strength in the engine's actual ticket set. Inspection does not force-load the target. This measures full-chunk residency, not OS cache coldness or absence of every unrelated holder.

The first attempt, **43836173670**, correctly rejected the cold-spawn claim: spawn was absent before reconnect but login warmed it before fallback. The final fixture stages login elsewhere, then restores the intended shared spawn after login without loading it. Restoration and recovery-entry cold assertions remain. This is an explicit changed-spawn fixture condition, not a claim that ordinary login always leaves spawn cold. Recovery code is never substituted or bypassed.

### Passed behavior

- A genuinely absent safe home chunk loads at original strength; real reconnect returns the player and confirms the saved position before clearing the return record.
- With every bounded home landing obstructed, recovery rejects home and reaches the still-cold safe fallback.
- With both home and spawn unsafe, the player remains in the protected pocket, without a Visit lease, with a durable unfinished return. Retries are 100 ticks apart, and damage suppression remains. The direct physical-access check stays denied at the fixture position; this is not a new nearby-client chest attack test.
- Beta's independent remote menu, 17 named emeralds, ticket rank and original lease deadline survive Alpha's seed recovery cycles.
- A normal server stop while recovery remains unresolved removes 17 live requests to zero. A new server retains the return record, original room IDs/cells/owners and checked chest identities. Real clients reconnect after another empty-server cold interval.
- Repairing one spawn-floor block in the fixture lets the existing retry find a safe landing and complete recovery. No fixture teleport or replacement recovery callback performs that return.
- One actual client withdrawal conserves 11 named diamonds as 10 stored plus one held. Components, samples and checked identities survive reconnect/restart. After final views close and 650 cleanup ticks, destination cache, both original home chunks, Anya/experiment requests and unfinished return records are empty. The clients' final overworld area is not expected to unload.

Fixture APIs establish owners/positions/samples/obstacles and change the shared spawn for the cold test. Three interruptions per lane are real client-process SIGKILLs. The new server interruption is a clean unresolved shutdown, not a new arbitrary-power-cut test.

## Bounds and costs

Each lane records 16 production recovery-load calls: one warm Beta exit and **15 absent-target calls**. All retain region-ticket argument **2**, including under the candidate. Seed unsafe retries occur at baseline ticks 5581/5681/5781 and candidate ticks 4649/4749/4849; restart retries at 1740/1840/1940 and 1391/1491/1591 respectively.

During repeated unsafe returns, total logical requests stay at or below **19**: Beta's 17 plus at most two temporary recovery requests. Whole-workload peak is 34. Each lane prepares two rooms, 13,072 shell writes, and zero shell regeneration on restart. Shell counts exclude final chests/exit, generation, lighting, saves and other work.

| Lane | Destination cache peak | Sampled server RSS peak | Sampled process-group RSS peak | Longest load-plus-audit call |
|---|---:|---:|---:|---:|
| Baseline | 2575 | 1333.5 MiB | 3662.0 MiB | 143.8 ms |
| Candidate | 2579 | 1376.6 MiB | 3680.0 MiB | 93.6 ms |

No resource guard fired. This is one safety case per policy on different hardware, not a paired performance benchmark. Timings include audit overhead. Cold recovery is synchronous and not free; these numbers are not a candidate speedup claim.

Java 21.0.13+9, MC1.21.1, NeoForge21.1.249; server 256/2048 MiB initial/max heap, G1GC, ergonomic processors2 (not CPU pinning), view3/simulation2. Actual clients use 1000 MiB heaps, software GL and 25 FPS. Baseline host: EPYC7763, four guest CPUs, ~15.61 GiB RAM, Microsoft virtualization; candidate: Xeon6975P-C, four guest CPUs, ~15.33 GiB, KVM. No forced GC or OS cache clearing. RSS is sampled once/second; group RSS is not PSS.

OPAC0.33.0 and CarryOn2.2.6.13 are loaded with retained hashes, not full-pack certification. Cutoffs include 3 GiB server RSS, 6 GiB group RSS, <2 GiB host-available memory, heap/cache limits and deadlines. Lowest sampled available host memory was ~11307.5 MiB.

## Preservation and remaining work

All 74 retained src/main entries match previous beta.6. Playable JAR:
`5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11`.

Latest room-verified source is **46cfc85d521afcc01cf2616070e0a25b7a562907**; cold-measured source is **da9904adda3be023617768ce3438154f5d154614**. The later change isolates fixture packaging/routing; do not label its cold run as having executed on that later commit. This report-only follow-up changes neither executable.

No production repair/optimization is promoted, and no replacement JAR is warranted. Saved formats, inventories/identities, artwork/UI, experimental gates, damage suppression and non-live preview remain unchanged. The reflective remote-only policy is unshipped. An efficient native per-pocket implementation under maximum-cap churn was not implemented or measured in this pass. It can use these cold-recovery tests as required regression evidence. No human test is required for that independent experiment.

Both-unsafe recovery still needs a safe landing to become available; the fixture repair does not prove automatic repair of arbitrary worlds. More clients, maximum-cap recovery contention, long soaks, WAN latency, grave mods, other movers and arbitrary crash boundaries remain unverified. Minecraft's non-atomic player/region saves remain a limitation. Live see-through rendering remains unimplemented.

## Retained artifact provenance

| Artifact | ID | ZIP SHA-256 |
|---|---:|---|
| Original room failure | 14208252930 | 4d7e6080b3e89904ed8c50b43f30592ef2d7f9460f0463832d6c6075424d6073 |
| First room repair | 14210741665 | 26678fecf4ecf4cee1d91dedefa69f7b20e4ef53cfc36716aa08d2a50a3d59b3 |
| Latest room verification | 14211622372 | a2ec95e3651b3ccd27d346aedcf8e3708078fc5ebdeeb1b55934ec8c944ce3f5 |
| Cold baseline | 14212126808 | e074803511f16716f5c5d039aa0d275463e3637b5920160cbaac5338b5affd0b |
| Cold candidate | 14211388791 | 93570e618f38dcc711258e9d0637febf22f81f5b928c2eca03bb520678601c61 |

Primary workflows: https://github.com/WeepingAngeIs/zSortange/actions/runs/43837852585 and https://github.com/WeepingAngeIs/zSortange/actions/runs/43837596506
Pinned LWJGL extraction properties: https://github.com/LWJGL/lwjgl3/blob/3.3.3/modules/lwjgl/core/src/main/java/org/lwjgl/system/Configuration.java
