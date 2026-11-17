# Approved pinned CE renderer control: beta.6 prerequisite

**The targeted control passed on source `55a803fdf9c20ebbb312bd4bc99472a66f2b266c`, workflow `44092751116`, job `134071852841`. This establishes one minimal through-portal renderer control, not a safe Anya live-view adapter or production readiness.**

The user specifically approved building/executing UpperMoon0/ImmersivePortals CE6.1.4 at `ad2e7ee5d73e4ff78bd0befade4f797265e65939` only on bounded disposable GitHub runners. No profile, user Java arguments/settings, real save, live server, artwork or model setting was accessed or changed. No user installation, production integration or new permission/lease adapter occurred. The cheap non-live preview and all existing Anya code/defaults remain unchanged.

## Exact inputs and executable provenance

The passing target-only baseline remains `ff63be191c69746e5ca40f11b61ff87f96a55f2a`, run `44083635001`. It was not rerun as new coverage. Its publisher preflight was reused to verify the same Minecraft1.21.1 / NeoForge21.1.249 / Sodium0.8.13 / Iris1.8.14-beta.1 / Cloth15.0.140 inputs before execution. Iris remained installed and shaders disabled.

| Artifact | SHA-256 |
|---|---|
| Unchanged Anya beta.6 | `5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11` |
| Unchanged current safety/operator module | `e11ed33cfb44e53bb059dbbb0449f94a87a02fa0f7d6de3ec38477a35f840c57` |
| Built CE6.1.4, 4,917,026 bytes | `6c58d232328146c9b76ceee1eb53969a149ce97ace5af4b50424a30581bf6187` |
| Separate owned diagnostic | `423ce3b16c7f27cf15d9d753a993bca87badef482747cdb4db2861f5c2d7cd84` |
| Sodium wrapper | `575d187b15328d7bddd8a9b1bee64333c22c14735f5780ec49eac0cd5503c08d` |
| Sodium nested implementation | `d71acbffafd3d177add7624710114d8570cdd70d5553a2b5c8ab371852c59ce3` |
| Iris | `60d5f8bf52f25e9986440f4a4270a6bd986d9cff994510e1108ac1547063b314` |
| Cloth Config | `65e722e0d98431a07c45f8bdd8d529a217cc8c175fde1740248bd5c1b4f3c0d4` |
| Official NeoForge installer21.1.249 | `d88b448eab73cd65bdf1720844a4828262de30a15fc71bd04dd81acc61c5399a` |
| Exact tested Anya-source ZIP | `e2da6372819149eb6bc26c6079d0cb11b1286e3528a9f9ba7ad851dac7ba7476` |

CE's source tree is `173f7d1fa6e468afa6d771df040b9f3cb725061b`. The artifact retains a SHA-256 manifest of all 968 tracked files, the exact LICENSE/NOTICE and build metadata. The pinned CE build.gradle has SHA-256 `25af8427f63fb152a5adccf26f9d99d13f2a15b7b176231fd9eb782061b246c8`. Source/build files were not patched. Build-only settings use NeoForge21.1.249 and exact supplied renderer/config compile inputs; this locally built, recorded binary is not represented as a publisher release binary.

Only CE's jar task and its build prerequisites ran. No upstream visual/test/game harness or Sable/Aeronautics runtime ran. The build uses the pinned CE Gradle8.10.2 wrapper; Anya still uses8.14.3. Compilation reads optional integration JAR classfiles, including Sable/Veil/Create compatibility APIs, as compile data only. Annotation processing is disabled with `-proc:none`; those integration JARs are not staged in either game. The resolved compile manifest retains112 coordinate/file/hash entries. Their presence on the compiler classpath is not runtime-mod acceptance or a separate blanket supply-chain certification.

The built CE JAR was checked for its three declared mod IDs/version, absence of nested executable JARs and dev GameTests, notices and required dependencies limited to Minecraft/NeoForge/Cloth. The game used only the explicit runtime allowlist, not CE's broader development runtime. No additional unrecognized runtime source was required or executed. Publisher project/version identities, sizes, hashes and repository links for Sodium/Iris/Cloth were reverified; the installer hash came from official NeoForge Maven.

## Actual runtime and nested selection

One genuine Minecraft client connected by localhost TCP to one dedicated server. Runtime inspection recorded all15 expected mod IDs, exact versions and readable selected-archive hashes, including CE's `imm_ptl`, `immersive_portals_core` and `q_misc_util` at6.1.4. Minecraft and NeoForge were1.21.1/21.1.249. Sodium's nested implementation matched the supplied SHA-256, rather than being replaced by the upstream default0.8.12.

The selected nested renderer API is `fabric_renderer_api_v1 3.4.1+9125b6dc19`, SHA-256 `aba3e2dc8d670eae242a75c5ae961acbb542792ac587ca3a3b4b177bcbb9cda7`. Other selected modules are API base0.4.42+d1308ded19, block-view1.0.10+9afaaf8c19 and rendering-data-attachment0.3.48+73761d2e19. Full top-level Forgified Fabric API and other profile mods were absent, so this is selection in the minimal fixture only.

The transformed LevelRenderer held an actual SodiumWorldRenderer and implemented both Sodium's LevelRendererExtension and CE's IEWorldRenderer, alongside Iris interfaces. `Iris.getCurrentPack()` was `Optional.empty`. The ordinary and CE rendering paths executed; this is stronger than a static mod-list or ABI-signature assertion. It does not certify every compatibility mixin or the full pack.

The renderer was Mesa llvmpipe, OpenGL4.5/Mesa26.0.8-1ubuntu0.3, not the target NVIDIA GPU. The fixture used fresh Sodium advanced defaults and captured them, not the complete target options JSON. It retained supplied vanilla/Iris options but did not fetch the selected shader ZIP. Renderer extensions, Euphoria and the rest of the 367-mod inventory were not added.

## Five framebuffer controls, not a direct-world substitution

A fresh Overworld source contains a permanently blue wall. The player/camera stays in that dimension. A non-global fixture CE portal shows a separate disposable void dimension `anyacecontrol:target`, not an Anya pocket. Its destination wall changes from red to lime through ordinary server block-update delivery. The portal is non-teleportable, non-interactable and cross-portal collision is disabled. Its recipient is the single fixture player. No inventory or Anya travel operation occurs.

Positive frames require both the expected remote chunk state and CE's actual rendered-destination/frame counters. Every capture also checks that the source wall remains blue. Screenshots are taken from the real main render target after frame rendering, followed by independent PNG center-color verification.

| Capture | Center mean RGB | CE portals rendered in captured frame | Meaning |
|---|---|---:|---|
| portal-red |105.045,25.094,25.094|1|Initial destination through portal |
| portal-green |71.843,117.755,19.420|1|Destination update, unchanged blue source |
| occluded-blue |35.032,34.177,104.377|0|Opaque source barrier blocks view; portal still exists |
| restored-green |70.088,125.965,18.387|1|Barrier removed, destination visible again |
| removed-blue |33.481,34.539,107.423|0|Portal entity actually discarded; source wall visible |

All images are960x640. Raw screenshot hashes and frame observations are retained. Positive frames name `anyacecontrol:target` in the rendered dimension set while the client remains in `minecraft:overworld`; negative sets are empty. Diagnostic geometry/captures are not new artwork. UI chat/tutorial notices are present in the raw screenshots and are not edited out.

This is not traversal, a user-deployable live room, per-player packet-privacy proof, scoped revocation or a multiplayer overlap test. The passing direct-world baseline is separate retained evidence, not confused with these destination controls.

## Loading and cleanup result

The fixture limits CE depth to1, indirect visible radius to1 and rendered portal count to2. The required halo and CE's normal ticket propagation still operate; no permanent/global additional loader was introduced. Cross-portal interaction, optional online notices and vanilla portal replacement generation are disabled only in this disposable fixture.

Observed destination raw chunk-cache peak: **961**. This is not a measured number of simultaneously ticking chunks or a four-chunk cost claim. No exclusive attribution of every loaded/cache neighbor was performed. The theoretical visible25-column radius-plus-halo request is not interchangeable with that engine cache peak.

After actual portal removal, client disconnect and650ticks, CE's destination ticket registry and the destination reported loaded-cache count were zero. Anya's owned requests were zero. Its stop audit reported zero engine-owned Anya tickets and the final minimal-module pending/callback audit was empty.

The CE check reads its tracking registry; it is not a separate full engine-ticket-set census for every ticket type. Cleanup follows removal AND final client departure. It therefore does not establish that removal alone releases precisely the right scope while another viewer/visitor remains. Occlusion/invisibility was never used as a substitute for removal or unload.

## Save-safety and preservation boundary

Current nonblocking preparation, minimal ownership guard, 32-serialization/256-callback/8ms soft budget, backpressure and journal/reset modules remain enabled and hash-identical. The Anya physical pocket prototype/defaults were not enabled or changed. The diagnostic uses a separate dimension and no storage transactions. Its repair summary reports zero protected writes/failures/repairs/resets and zero journal bytes. Those zero counters show the code was loaded, not that CE has passed the full Anya save-safety matrix.

The server performs its normal save/clean stop. There is no new independent restart, player/item conservation or injected persistence-failure acceptance in this rendering step. Prior results are retained for unchanged binaries and are not relabeled as CE+Anya integration coverage.

Archive comparison verified all383 preexisting files, including all74 production source/resource files, byte-for-byte unchanged from the preceding renderer baseline. Exactly11 fixture/workflow files were added. No production source, existing tests, artwork, defaults, data format, recovery policy or fallback preview changed.

## Resource limits and observed costs

Linux7.0.0-1019-azure, four guest logical CPUs, Temurin21.0.13+9. This is not the historical target Java21.0.12.1 patch or target Windows/RTX environment. One dedicated server, one actual client; no simulated-client performance counts.

| Runtime measurement | Value |
|---|---:|
| Samples |191|
| Last elapsed game sample |96.356s|
| Peak server process-group RSS |1,004.3MiB|
| Peak client process-group RSS |1,387.6MiB|
| Peak combined process-group RSS |2,364.4MiB|
| Minimum available host memory |12,714.6MiB|

Limits:25-minute job,600-second CE build with3GiB daemon/two workers,120-second owned-fixture build,480-second game phase,150-second server startup,90-second final drain/stop;2GiB server heap and1000MiB client heap;3GiB server RSS/6GiB combined RSS/2GiB minimum host headroom;4096destination-cache ceiling. The CE build completed in5m40s. No final guard fired.

Memory sampling covers full game process groups at roughly0.5second intervals; shared pages are not deduplicated, and these are not continuous maxima. Build/decompiler/compiler process memory is not included in runtime RSS. No CPU/FPS benchmark, comparison speedup, long soak or VRAM claim is made. Existing tick telemetry includes startup and instrumentation and is not a controlled performance comparison.

The logs retain warnings, including optional absent mixin targets, compatibility-level messages, software-graphics limitations, narration/audio initialization and the default flat-generator settings diagnostic. They did not fail this control; the run is not described as warning-free. No warning was suppressed or dependency installed merely to make the log clean.

## First failed attempt retained

Initial source `e6138d30b329a957fe79483bc3fb30d4ac5e12f6`, workflow `44092211503`, job `134069973013`, failed before CE compilation with the missing Gradle Problems.forNamespace method. The fixture had invoked Anya's Gradle8.14.3 against the pinned CE NeoGradle plugin. Reopening CE's own wrapper established8.10.2.

The only subsequent correction uses that pinned wrapper for CE while leaving Anya's toolchain and all target mod versions unchanged. No CE source patch, render assertion reduction, extra runtime mod, modified shader setting or item retry was involved. The original failed archive is retained unchanged, SHA-256 `ad1b791eb1503998dfce22035fb7fe0d24c480fb6cd1e6f02590b15b69a27324`. The final passing archive is `0e575b1308c488b30998a2382448784eafb193a95bca261459d7bb0b2137f209`.

Automatically triggered broad repository workflows are not substitutes for the targeted result and were not manually repeated as new portal coverage.

## Decision and remaining blockers

**The specifically approved minimal CE renderer capability test passes with exact Sodium0.8.13, retained Iris/shaders off and unchanged Anya safety binaries. No new runtime-dependency approval blocker arose.** The next stage is not automatically authorized production installation or a completed adapter.

A safe Anya live-view adapter still needs separate View/Visit decisions, scoped outgoing packet authorization/revocation, control of CE automatic/secondary loaders, independent concurrent viewer/visitor handles, halo/ticket budget accounting, claim/anchor checks and cleanup without disturbing unrelated users or save/recovery tickets. Multiple users, resource reload, mixed portals, target GPU, full modpack, active shaders and fault/shutdown interactions with actual Anya pocket writes remain untested here. Do not interpret this diagnostic's specificPlayerId as proof of the missing adapter.

The no-durable-copy and cross-file player/chunk/ledger/POI non-atomicity limits remain. CE rendering does not remove those constraints, and current safeguards were not weakened to obtain pixels. No unsafe downgrade is supported. No installation or user-side test is requested for this completed prerequisite. No art phase was necessary.

The evidence kit includes both original CI archives, exact owned fixture source, complete source/hash/runtime manifests, raw captures/logs, resource records and a read-only verifier. It excludes executable JARs, native libraries, world saves and copied CE source/assets beyond the relevant text license/build metadata. CE source hashes identify the approved upstream without claiming a reproducible-build comparison that was not run.

Target run: https://github.com/WeepingAngeIs/zSortange/actions/runs/44092751116
Tested source: https://github.com/WeepingAngeIs/zSortange/commit/55a803fdf9c20ebbb312bd4bc99472a66f2b266c
Approved upstream: https://github.com/UpperMoon0/ImmersivePortals/tree/ad2e7ee5d73e4ff78bd0befade4f797265e65939
