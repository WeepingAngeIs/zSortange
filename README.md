# anya's strorage plus

Minecraft 1.21.1 / NeoForge 21.1.249 / Java 21. Experimental beta implementation in progress. Do not replace existing storage mods until the compiled artifact and pack-level tests have passed.

This branch implements original backpacks, physical storage chests, a loaded-chunk hub index, a handheld remote, QuickStack, routing, server-authoritative configuration and optional material tiers. Both clients and server need the same JAR.

## Controls

- `/anya storage` or the collision-safe `/as` alias opens settings. Operators see server configuration; other players see private protected-slot preferences.
- Right-click a backpack to open it. Sneak-use to install/remove upgrades.
- Sneak-use a remote on a hub to bind it; use it in that hub's radius to search storage. Sneak-use the bound remote on a supported chest to edit routing as its hub owner.
- QuickStack, Dump and Open Backpack keys are initially unbound; assign them in Minecraft Controls. `/anya quickstack`, `/anya dump` and `/anya slots` are secondary conveniences.
- Storage screens use server-checked buttons, not freely movable display items. Click an inventory slot to deposit; click a listed stack to withdraw; right-click transfers one. Middle-click a listed stack toggles a favorite.

## Beta safety limits

All remote access is server-authoritative, component-aware, restricted to loaded chunks, and checked against inventory snapshots. Operations have a conservation check and attempt rollback on failed writes. This is NOT a distributed durable transaction across Minecraft's separate player/chunk save files. A process kill, disk failure or forced rollback of only part of a world can still cause inconsistency. Keep backups and use a disposable test world first.

Only exact vanilla chest/barrel/shulker classes and this mod's physical storage are supported. Player-specific inventories, arbitrary item handlers and unopened loot containers are excluded. Open Parties and Claims is an optional reflection adapter that fails closed if its API cannot be used; installed-pack integration still requires testing. Known unsupported claim mods block remote access rather than silently bypassing protection.

The endgame cube recipe defaults to OFF. Operators may explicitly select a four-Nether-Star/four-Dragon's-Breath route or a four-Nether-Star/four-dragon-victory-token route; both also require an expensive storage core. Dragon's Breath does not prove repeat kills. The tracked fight token route only rewards a survival player's final blow on the current tracked End fight dragon, once per dragon UUID.

## Build

The GitHub Actions workflow uses Java 21 and Gradle 8.14.3. It builds, runs configuration checks, starts a NeoForge GameTest server, and uploads JARs, sources and checksums only if all required steps succeed. Current compilation and test status must be read from the actual workflow run; source being present does not establish a successful build.
