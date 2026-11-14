# Anya's Storage Plus

Experimental Minecraft **1.21.1 / NeoForge 21.1.249+ / Java 21** storage mod. Mod ID: `anyastorage`. Current source milestone: `0.2.0-beta.1`.

This repository is the source and GitHub-hosted build location. The workflow compiles the mod, runs configuration-core assertions and a headless NeoForge GameTest suite, then uploads the JAR, source archive and SHA-256 checksums. Only install an artifact from a successful run for the exact source commit.

**This is an isolated-world beta, not a certified drop-in replacement for the existing storage stack.** Do not remove Tom's Storage, Sophisticated Storage/Backpacks, their dependencies, or QuickStack from an occupied world just because this JAR exists. There is no migration importer. Back up and copy the whole world first, including its `data` directory.

## Install

Install the same built `anya-storage-plus-0.2.0-beta.1.jar` on both the dedicated server and every client. Do not install the `-sources.jar` as a mod. No Ice and Fire, Cataclysm, or other material mod is a mandatory dependency. Restart after adding the JAR.

The server owns `config/anya-storage-plus.json`. The previous version is backed up as `.json.bak` when an operator applies changes. Legacy schema-1 foundation `.properties` settings are imported only when the new JSON file does not already exist. Invalid settings stop initialization rather than silently replacing them.

## In-game access

- `/anya storage` opens the personal overview and, for permission-level-2 operators, the configuration workbench. `/as` is registered as an alias only if the name is free.
- Right-click a backpack to open it. Right-click a Storage Chest or UwU Cube for direct physical access.
- Place a Storage Hub. Sneak-right-click it while holding a Storage Button to bind the button. Right-click the button within the hub's upgraded radius to open network storage.
- Set **QuickStack matching items** and **Category dump** keys under Controls > Anya's Storage Plus. They are deliberately unbound by default to avoid modpack conflicts.

The contents screen uses a search field, paged results and real-time server-owned counts. Search ordinary names/IDs, `@mod_namespace`, `#category`, or `*` for favorites. Left-click a listed item to withdraw a stack, shift-left-click for one, and right-click to favorite its item type. Click an item in your inventory to deposit it. Display icons never become cursor items.

## Implemented systems

### Backpacks

Starter (27 slots / 1 upgrade), iron (45 / 2), gold (63 / 3), diamond (81 / 4), and netherite (108 / 4). Upgrade recipes preserve the backpack's world-owned inventory identity and item components. Existing inventories do not get truncated when settings change. Backpacks with the same identity, including creative copies, share one inventory rather than cloning contents.

The four installable upgrade types are pickup/auto-stow, safe-food feeding, refill of existing hotbar stacks, and explicit vanilla-material 9-to-1 compacting. Each has a server toggle, personal toggle and per-backpack switch. A per-backpack exact-item filter is editable in the menu. Auto-stow is not a ground-item magnet. Feeding is limited to plain whitelisted foods; compacting is not arbitrary mod crafting. Automation pauses while a container screen is open.

Backpack contents are stored in this world's SavedData. Copying just the item into another world does not transport its contents. Armor and offhand are not QuickStack sources. The nine hotbar slots are protected by default. Nested backpacks and container-payload items are rejected.

### Hubs and ordinary containers

Loaded-chunk-only discovery, a globally bounded chunk scanning budget, a container limit and a cached index. Supported network containers are this mod's physical containers and explicitly enabled exact vanilla chest, trapped-chest, barrel and shulker-box classes. Arbitrary machines, player-specific inventories, Ender Chests and unresolved loot containers are deliberately not scanned.

Overlapping hubs assign each container to the nearest loaded hub deterministically. Ownership, trust, configured radius, locks and claim checks are revalidated when a transfer commits. The same hub radius governs remote access, network container discovery and base QuickStack.

The ore feeder shows the selected material count and progress before consuming anything. A separate confirmation spends progress on the next radius increase. Both operations reject stale previews. The default base radius is 16, increments are 8, and the default maximum is 64; these are editable in-game within hard safety bounds.

### Organization and profiles

Each hub has a name, owner, up to 64 trusted UUIDs, category-designated destinations and an overflow policy. Trust never overrides a claims mod's permission. Configure an online player by name or an offline player by exact UUID.

Organize first builds a component-preserving preview. A second click commits it only if all inventories, permissions and the settings revision are unchanged. A preview expires after ten seconds. Large sorts are limited to 4096 slots per operation. Categories have automatic defaults plus per-item overrides. QuickStack deposits into existing matching items; category dump additionally uses compatible category destinations. A separate panel explains skipped inventory slots.

Storage Chests have seven selectable front symbols: heart, star, square, circle, diamond, leaf and gear. The symbol is split between the animated lid and base. The UwU Cube has its own face and heart artwork.

### Operator workbench

Private revisioned drafts; independent feature and recipe gates; live material detection; custom tier editing; material, predecessor, capacity, upgrade count and recipe cost fields; ore-value editing; category overrides; presets; change-impact review; stale-draft rejection; file backup and audit log; recipe availability explanations and matching-layout conflict diagnostics; index age, scan time, limits and permissions status.

A compatibility gate enables storage recipes **using** a material. It does not add shortcut recipes manufacturing Dragonsteel, Witherite or another mod's progression item. Known ingot-name candidates are discovered from the running registry. To configure a new material precisely: click its inventory stack in the compatibility editor, add the material gate, then create/edit its tier in the tier editor. New optional tiers and gates start disabled. Set the predecessor and costs, enable the tier and both compatibility switches, review, then Apply. Missing optional materials are explained and do not make vanilla tiers depend on those mods.

Custom crafting rules are live server-authoritative shapeless recipes. Use the built-in recipe guide rather than assuming the vanilla recipe book or JEI/EMI lists every dynamic tier. Conflict diagnostics warn about other recipes matching the shown layout; they do not rewrite another mod's recipes.

### Endgame choice remains optional

The endgame recipe defaults to **undecided / disabled**. The menu offers two alternatives, each also requiring one Storage Core:

1. Four Nether Stars plus four Dragon Breath. This is a Wither-heavy route but does not force repeated dragon kills.
2. Four Nether Stars plus four Dragon Victory Tokens. When this route and its recipe group are enabled, managed End dragon kills award one token per dragon identity.

Choose the route and enable the endgame recipe group explicitly. Existing cubes remain accessible when the recipe is disabled.

## Permissions and safety

Open Parties and Claims is optional. The adapter calls its public block-interaction protection API. A detected OPAC API failure blocks access rather than silently granting it. Several recognized unsupported claims systems also block network access. Unknown protection mods cannot be assumed safe: validate the exact installed pack before using shared-world storage. The installed OPAC version has not been certified merely by compilation.

Transactions preserve components, check source/destination snapshots and capacity, reject replays and changed permission guards, roll back setter failures where possible, and lock further transfers after a rollback failure. These are **in-memory server-thread guarantees**, not proof of power-loss atomicity across Minecraft's separately saved chunks, player files and world data. See [SAFETY.md](docs/SAFETY.md).

## Build and verification

GitHub Actions uses Temurin Java 21 and Gradle 8.14.3. It runs:

```sh
gradle --no-daemon clean build
gradle --no-daemon runGameTestServer
```

The resource generator uses Python's standard library and creates original textures, JSON resources and the test structure. No copyrighted texture pack or third-party mod is copied into this JAR. The workflow uploads build diagnostics even when a step fails. A successful headless test run does not replace client visual testing or multiplayer tests with the full modpack. See [TESTING.md](docs/TESTING.md) for the remaining acceptance checklist.
