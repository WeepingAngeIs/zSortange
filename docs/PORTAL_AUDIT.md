# Optional live portal lead: source audit, not an integration

## Status and inspected revision

No portal dependency has been installed, bundled, copied into Anya Storage, or tested in a user's profile. This milestone does not implement a pocket dimension, a portal block, a preview renderer or live portal rendering. `pocketStorage=true` is rejected. The recommended sequence remains foundations, bounded physical pocket storage/permissions, then optional live viewing.

Inspected repository: `UpperMoon0/ImmersivePortals`, commit **ad2e7ee5d73e4ff78bd0befade4f797265e65939**. Its `gradle.properties` identifies source version 6.1.4, Minecraft 1.21.1 and NeoForge 21.1.228. This identifies the inspected source, not a claim that a particular release JAR has been runtime-tested here.

## License

The repository LICENSE is Apache License 2.0, and NOTICE identifies the independent Community Edition and upstream contributors. Those files were read; no CE code or assets were reused. A later adoption must preserve applicable licensing/notice requirements and audit dependencies separately. The presence of Apache-2.0 is not a blanket compatibility or asset-license certification.

## Renderer contracts and missing target evidence

The inspected renderer-backends document lists these targets:

| Terrain renderer | Shader lane |
| --- | --- |
| Vanilla Minecraft 1.21.1 | None |
| Sodium 0.8.12+mc1.21.1 | None or official Iris 1.8.14-beta.1+1.21.1-neoforge |
| Embeddium 1.0.15+mc1.21.1 | None or NeOculus 1.8.7 |

Only one terrain renderer should be present. `RendererCompatibility.java` rejects Sodium+Embeddium, NeOculus without Embeddium and official Iris without Sodium. Its version checker pins Embeddium/NeOculus; it does not itself prove every adjacent Sodium version compatible or incompatible. A different version number is a testing gap, not sufficient evidence of a crash.

The exact intended target profile's renderer/mod inventory and hashes were not verified. A report from another installation is not a substitute. No renderer switch, shader removal, dependency installation or compatibility approval was performed. Full-pack startup, mixin application and actual framebuffer/two-player evidence remain required before enabling an adapter.

## Destination loading and cleanup

The documented optimized renderers require a one-chunk block/light-data halo outside the visible radius for mesh construction. A visible pocket occupying four chunks therefore does not imply only four chunks of loaded/rendered data. Ordinary visitor view/simulation distances, other mods' tickets and renderer prerequisites must be counted too.

`ImmPtlChunkTracking` stores per-player, per-dimension watch records, updates them on a 13-tick interval, and purges stale generations. Removed players and dimension removal have cleanup routes. Purging sends forget-chunk packets and asks `ImmPtlChunkTickets` to release chunks with neither watchers nor additional loaders. Global additional chunk loaders are refreshed separately; retaining one can keep destination data requested even without viewers.

These source paths show a cleanup design, not a measured unload guarantee. Watch removal is not proof that all chunk tickets, entities, renderer meshes and lighting work cease immediately. No disconnect/two-player/restart portal test was executed for this audit.

## Required Anya integration contract (future work)

A bounded pocket must have a separate 32x32-block build boundary, storage identity, home anchor and explicit visit/view/deposit/withdraw permissions. Registered physical chests, inventory operations and logging must be authoritative on the server; visiting must not accidentally grant withdrawal. No natural mob spawning or new overworld ore generation is planned. Scenery should be decorative and replaceable without overwriting player containers.

Live views must use short, server-approved leases for a nearby authorized viewer. Budgets need separate caps for active pockets, viewers, visible chunks, renderer halo, tickets and refresh work. Reference counts must retain a destination while another permitted visitor/viewer needs it, then release Anya-owned requests on distance loss, revocation, anchor removal, disconnect and shutdown. No permanent force-loading or reusable global loader should be introduced just to keep a portal pretty.

A lightweight animated preview must remain the default fallback. It must be labeled a preview, not depict stale inventory as a live world, and must not load the destination merely to animate. A real live view has nonzero rendering, networking and server cost even for four build chunks.

Before enabling live rendering, test two simultaneous authorized viewers, an unauthorized observer, independent portals, mixed visitation/viewing, permission revocation, crossing both directions, death/disconnect, resource reload, shader changes, dimension teardown and restart persistence. Measure final tickets/chunks after the last user leaves. No passing claim may be based solely on an API signature or documentation matrix.

## Primary source locations

All references are pinned to the inspected commit:

- https://github.com/UpperMoon0/ImmersivePortals/blob/ad2e7ee5d73e4ff78bd0befade4f797265e65939/LICENSE
- https://github.com/UpperMoon0/ImmersivePortals/blob/ad2e7ee5d73e4ff78bd0befade4f797265e65939/NOTICE
- https://github.com/UpperMoon0/ImmersivePortals/blob/ad2e7ee5d73e4ff78bd0befade4f797265e65939/gradle.properties
- https://github.com/UpperMoon0/ImmersivePortals/blob/ad2e7ee5d73e4ff78bd0befade4f797265e65939/docs/renderer-backends.md
- https://github.com/UpperMoon0/ImmersivePortals/blob/ad2e7ee5d73e4ff78bd0befade4f797265e65939/src/main/java/qouteall/imm_ptl/core/compat/RendererCompatibility.java
- https://github.com/UpperMoon0/ImmersivePortals/blob/ad2e7ee5d73e4ff78bd0befade4f797265e65939/src/main/java/qouteall/imm_ptl/core/chunk_loading/ImmPtlChunkTracking.java
