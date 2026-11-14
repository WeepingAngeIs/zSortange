# anya's strorage plus

**0.2.0-beta.1** for Minecraft **1.21.1**, NeoForge **21.1.249+ within 21.1.x**, Java **21**. Install the same playable JAR on server and clients. This is an original storage beta, not a source-only configuration mock-up and not a claim of complete feature parity with other storage mods.

## Included

- Backpacks with saved-capacity progression, seven installable upgrade types and content-preserving tier recipes.
- A physical-storage hub with search, favorites, a bound handheld remote, ore-fed radius upgrades and confirmation previews.
- A 54-slot labelled chest with a hinged lid and split sticker; a 216-slot UwU cube with an optional endgame recipe.
- Built-in QuickStack and broader matching Dump, protected inventory slots, per-item explanations and organization previews.
- Named base profiles, trusted players, category/item routes, priorities and overflow rules.
- Operator settings, private drafts, revision-safe Apply, recipe gates, tier editing, exact installed-material selection, compatibility diagnostics, item/tag category rules, presets, impact previews and performance limits.

Open settings with `/anya storage` or the collision-safe `/as` alias. Normal players get their protected-slot preferences rather than server settings. The handheld remote is the primary storage interface. QuickStack, Dump and Open Backpack keys start unbound so they do not overwrite pack controls.

Optional Dragonsteel/Witherite tiers consume actual installed material items; they never manufacture those ingots or require the material mods as hard dependencies. Newly added optional tiers start disabled. The endgame cube recipe also defaults to OFF until an operator chooses a route.

## Documentation

- [Installation, controls and progression](docs/INSTALL.md)
- [Inventory/permission safety and limits](docs/SAFETY.md)
- [Automated tests and remaining manual acceptance](docs/TESTING.md)
- [Verified pre-release build](docs/VERIFICATION.md)

## Build and verification

The read-only GitHub Actions workflow builds the exact checked-out commit using Java 21 and Gradle 8.14.3, runs configuration assertions, launches a real NeoForge GameTest server, and uploads playable JARs only after required steps succeed. The artifact includes the exact source commit, source archive, logs, documentation and SHA-256 checksums. Install `anya-storage-plus-0.2.0-beta.1.jar`, not the `-sources.jar`.

The core implementation passed 28 required GameTests, including 500 deterministic randomized transfers, on source `fb7d74494c7d9e3e30abdd5fda41b400b23aee81` in workflow run `43654812369`. Subsequent commits must be judged by their own workflow result.

## Before replacing existing mods

Use a backed-up test world and keep existing storage mods until the complete pack passes acceptance. No migration is provided. Only exact vanilla chests/barrels/optional shulkers and this mod's containers are supported; arbitrary external item-handler storage is intentionally excluded. The OPAC adapter fails closed on API errors but still needs testing with the actual installed claim configuration.

Transfers are server-thread snapshot transactions with component conservation and rollback attempts. They are NOT a distributed durable transaction across Minecraft's separate player/chunk save files. Hard crashes, disk failures or partial backup restores are not guaranteed safe. Client appearance, real multiplayer/disconnect behavior and the full installed modpack still require testing. See the documentation for narrower upgrade behavior and other beta limits.
