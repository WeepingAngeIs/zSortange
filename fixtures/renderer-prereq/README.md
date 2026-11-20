# Bounded renderer prerequisite, not a live-pocket adapter

This additive fixture targets Minecraft 1.21.1 / NeoForge 21.1.249 / Sodium 0.8.13 / Iris 1.8.14-beta.1 with shaders disabled / Cloth Config 15.0.140. It keeps installed-profile files and all production sources unchanged. `inputs.json` records Stella's read-only 2026-11-17 target hashes as expected values, not newly observed local files.

## Execution boundaries

1. The Python-only preflight downloads metadata and archives from the publishers' Modrinth projects. It verifies immutable version/project identity, source-repository link, loader, game version, byte size, publisher SHA-1/SHA-512 and the supplied target SHA-256. A mismatch stops before Java compilation/installation/runtime. No mirror, guessed replacement, downgrade or shader removal.
2. Nested archives are read in memory with bounds. Their manifests and JarJar constraints are candidate evidence, not NeoForge resolution.
3. Only when preflight passes, GitHub builds the unchanged Anya beta.6 and current safety/operator stack plus this separate diagnostic mod. The existing exact beta.6/safety hashes must match. A fresh server and one genuine Minecraft client run on loopback. The client records the actual selected modules and archive paths/hashes, an instantiated Sodium renderer object on transformed `LevelRenderer`, and Iris's empty current shader pack.
4. A direct-world red-to-green scene checks rendered frame changes and packet-driven terrain refresh. This is NOT a CE portal frame or a safe Anya live view. Images are diagnostic captures, not new artwork.
5. CE build/execution remains blocked pending explicit source/version/action approval. No CE JAR is fetched or loaded by this workflow. The pinned lead is `UpperMoon0/ImmersivePortals` at `ad2e7ee5d73e4ff78bd0befade4f797265e65939` (6.1.4).

## Why the CE harness cannot simply be run unchanged

At that pin, `build.gradle` uses 0.8.12 in portal/client verification lanes and defaults `verificationSable` to true. `tools/verify.py` carries multi-hour visual limits. Those are not the approved exact target or the bounded test requested here. A later approved CE runner must use the verified 0.8.13 bytes, retained Iris, NeoForge 21.1.249, no unapproved Sable/Aeronautics dependencies, current Anya safety hash, and short explicit limits. Its dependency graph must be captured before execution of any additional unfamiliar artifact.

The minimal later CE control should use the same direct-world scene as a positive control, a destination colored-plane live change through a non-teleportable/non-interactable fixture portal, a hidden/occluded negative control, real framebuffer comparison and backend/mixin evidence. No pocket permission/lease adapter, user portal installation or default change is authorized by this preparation. Passing pixels would establish backend capability only, not recipient privacy or loading cleanup.

## Exact scope and exclusions

Only the three publisher renderer/config artifacts are imported. The other 364 top-level profile files, Sodium add-ons, Euphoria patcher, full top-level Forgified Fabric API, target GPU/driver and shaderpack are not included. The selected shaderpack filename is retained but shaders are off and the ZIP is not downloaded. Vanilla options use the supplied values. The exact Sodium JSON was not supplied; its fresh fixture defaults are captured and must not be claimed to reproduce every advanced target option.

No new saves/edits/transfers, repair or reset operation is exercised. Current protection binaries and flags remain active but this does not repeat their safety acceptance. The scene is a new disposable overworld only; no portal or pocket is opened. Software rendering is not RTX5090 or NVIDIA-workaround validation.

## Limits and outputs

150s server startup, 480s total game runtime, 4000 server/7000 client ticks, 2GiB server heap / 1000MiB client heap, 6GiB summed sampled RSS / 2GiB minimum host available memory; 20-minute job. New private native extraction path, bounded archive/metadata downloads. No user credentials or files; Actions checkout credentials are not persisted. Original process logs and failure markers always retained. Artifact paths exclude JARs, native binaries and worlds.

Outputs: raw publisher metadata, target/publisher hashes, nested candidate metadata, actual runtime mod list, active backend evidence, direct red/green framebuffer captures, config deltas, exact source, process resource samples and terminal result. Absence of `runtime-result.json` means no runtime pass. A static inventory is never relabeled as selected loader resolution.

## Reproduction

GitHub only: `python3 fixtures/renderer-prereq/provenance.py build/renderer-prereq/provenance`, then the targeted workflow's build/launch commands. Offline standard-library tests can run with `python3 -m unittest discover -s fixtures/renderer-prereq/tests -v`; those tests do not execute downloaded software. `execution_approved=false` is deliberate and cannot be used as an authorization grant by changing a local flag. Approval belongs to the user, separately.
