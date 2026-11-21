# Sophisticated Storage reference for Anya Storage Plus

Reference date 2026-11-20. This inventory describes the installed upstream **Sophisticated Storage** build and the parts of Sophisticated Core that Storage uses. It is design research for an independent implementation. The checks read installed files and versioned source. They establish neither Anya feature parity nor a passed gameplay test.

## Release identity

The live-named CurseForge test profile on disk was `Cobblemon Unyielding TEST now`. Its `minecraftinstance.json` identifies Minecraft **1.21.1**, NeoForge **21.1.249**, and the installed CurseForge file IDs below. The two binaries in the sibling main profile matched the test profile hashes. Server-side values below came from the test profile's `config/` directory. They apply to this file snapshot. A dedicated host's effective server configuration and any individual world's overrides require separate evidence.

| Component | Installed file | SHA-256 | Pin |
|---|---|---|---|
| Sophisticated Storage | `sophisticatedstorage-1.21.1-1.6.18.2229.jar` | `a263cfe0abafea3dd495cd6c7e6fd3c14b5070c35b63960d00c7e58081f4f987` | [CurseForge 9578283](https://www.curseforge.com/minecraft/mc-mods/sophisticated-storage/files/9578283), [upstream build 2229](https://github.com/P3pp3rF1y/SophisticatedStorage/actions/runs/43273366011), [source 55257b5](https://github.com/P3pp3rF1y/SophisticatedStorage/tree/55257b5900c3b9a1bfdb30267a438c2665d0d7c6) |
| Sophisticated Core | `sophisticatedcore-1.21.1-1.5.35.2473.jar` | `c255cfaca1a939932f622ad3c934a0c379b371fa193b0d06ee3f81a723148e64` | [CurseForge 9578162](https://www.curseforge.com/minecraft/mc-mods/sophisticated-core/files/9578162), [upstream build 2473](https://github.com/P3pp3rF1y/SophisticatedCore/actions/runs/43271734406), [source b3cd0c4](https://github.com/P3pp3rF1y/SophisticatedCore/tree/b3cd0c429dd0daf290869241e1bd6e105d3feffb) |

The respective SHA-1 values are `7b6b98482d8f1f7d1f586c601eb4eae652b63bed` and `a881afeeadfa9f0078dad6bc77dce3f0b422b954`. Both embedded manifests contain the build-number versions. Both mod descriptors require Minecraft in `[1.21.1,1.21.2)` and NeoForge in `[21.1.229,)`. Storage requires Core `[1.5.35,)`. Their optional JEI bounds are `[19.9.0,)` (Storage) and `[19.32.0.359,)` (Core). Evidence inside each JAR is `META-INF/MANIFEST.MF` and `META-INF/neoforge.mods.toml`. The upstream licenses reserve rights to original code and art. This matrix uses original descriptions of observable mechanics. [Storage license](https://github.com/P3pp3rF1y/SophisticatedStorage/blob/55257b5900c3b9a1bfdb30267a438c2665d0d7c6/LICENSE.md) · [Core license](https://github.com/P3pp3rF1y/SophisticatedCore/blob/b3cd0c429dd0daf290869241e1bd6e105d3feffb/LICENSE.md)

## Physical storage and network

All six columns are in the order of the actual data file. A limited barrel's multiplier is per slot, in units of the item's ordinary stack capacity. Item slots, upgrade slots, fluid volume, and per-slot multipliers are independent quantities. [Storage block registry](https://github.com/P3pp3rF1y/SophisticatedStorage/blob/55257b5900c3b9a1bfdb30267a438c2665d0d7c6/src/main/java/net/p3pp3rf1y/sophisticatedstorage/init/ModBlocks.java) · [Configuration definitions](https://github.com/P3pp3rF1y/SophisticatedStorage/blob/55257b5900c3b9a1bfdb30267a438c2665d0d7c6/src/main/java/net/p3pp3rf1y/sophisticatedstorage/Config.java)

| Property in installed configuration | Basic | Copper | Iron | Gold | Diamond | Netherite |
|---|---:|---:|---:|---:|---:|---:|
| Single chest, barrel, or shulker item slots | 27 | 45 | 54 | 81 | 108 | 132 |
| Upgrade slots on those containers, tanks and limited barrels | 1 | 1 | 2 | 3 | 4 | 5 |
| Limited barrel I (one item slot) | 32 | 53 | 64 | 96 | 128 | 160 |
| Limited barrel II (two item slots) | 16 | 27 | 32 | 48 | 64 | 80 |
| Limited barrel III (three item slots) | 10 | 17 | 20 | 30 | 40 | 50 |
| Limited barrel IV (four item slots) | 8 | 13 | 16 | 24 | 32 | 40 |
| Standalone tank, mB per segment | 8,000 | 12,000 | 16,000 | 32,000 | 64,000 | 128,000 |

- The basic tier tool converts supported vanilla containers, including barrel, chest and shulkers. The six Storage tiers have **15 forward source/destination pairs**, including direct jumps. The block upgrade handles the actual container, with special handling for linked double chests, existing contents, facing, tiers, in-use/packed state and permitted transformations. Tank upgrades have a separate branch. An upgrade base is a crafting intermediate. [Tier application](https://github.com/P3pp3rF1y/SophisticatedStorage/blob/55257b5900c3b9a1bfdb30267a438c2665d0d7c6/src/main/java/net/p3pp3rf1y/sophisticatedstorage/item/StorageTierUpgradeItem.java)
- A tiered tank is a placed fluid host. Compatible segments can form a vertical tank up to five tall. Volume scales with segment count and eligible positive stack multiplier, with a signed-integer cap. The tank checks contents and upgrades when merging. [Tank implementation](https://github.com/P3pp3rF1y/SophisticatedStorage/tree/55257b5900c3b9a1bfdb30267a438c2665d0d7c6/src/main/java/net/p3pp3rf1y/sophisticatedstorage/block)
- A controller joins attached storage and accepts deposits. A quick second deposit within 10 ticks attempts matching inventory items, including memorized and filter matches. The profile controller range is **15**. A **Connector** bridges gaps in a group. A **Link** attaches a separate group to its controller with the Storage Tool. **I/O, Input, Output** expose bidirectional, insertion-only, and extraction-only item automation respectively. [Controller](https://github.com/P3pp3rF1y/SophisticatedStorage/blob/55257b5900c3b9a1bfdb30267a438c2665d0d7c6/src/main/java/net/p3pp3rf1y/sophisticatedstorage/block/ControllerBlockEntity.java) · [Registrations](https://github.com/P3pp3rF1y/SophisticatedStorage/blob/55257b5900c3b9a1bfdb30267a438c2665d0d7c6/src/main/java/net/p3pp3rf1y/sophisticatedstorage/init/ModItems.java)
- Physical handling includes packing tape with 8 uses and super tape with 32. The Storage Tool controls linking, locking, and count, lock, tier, upgrade and fill overlays. The decoration table defines finish/material choices, and the paintbrush applies prepared decoration to placed storage. Chests support double forms, and limited barrels support count display and dyeing. The register also contains a debug tool and an inaccessible-slot placeholder. [Item implementations](https://github.com/P3pp3rF1y/SophisticatedStorage/tree/55257b5900c3b9a1bfdb30267a438c2665d0d7c6/src/main/java/net/p3pp3rf1y/sophisticatedstorage/item)

Storage also has built-in settings independent of any installed upgrade. **Memory** reserves slot filters and controls component matching. **No Sort** marks specific slots to preserve during sorting. **Item Display** chooses representative slots and exposes display side, rotation, offset and color. These behaviors belong alongside container settings in a counterpart inventory. [Pinned settings categories](https://github.com/P3pp3rF1y/SophisticatedCore/tree/b3cd0c429dd0daf290869241e1bd6e105d3feffb/src/main/java/net/p3pp3rf1y/sophisticatedcore/settings)

## Installable item families

The item IDs below use the namespace `sophisticatedstorage`. The count of filter slots describes ghost criteria, independent of actual stored item slots. For item filters that provide advanced matching, the advanced UI can match by item, supplying mod, or tag, with durability and stored component/NBT matching controls. The shared contents-filter version also offers matching against the storage contents. Direction, range, cooldowns, station input and safety modes remain family-specific. Sources for these distinctions include the pinned [Storage item registry](https://github.com/P3pp3rF1y/SophisticatedStorage/blob/55257b5900c3b9a1bfdb30267a438c2665d0d7c6/src/main/java/net/p3pp3rf1y/sophisticatedstorage/init/ModItems.java), [Core upgrade wrappers and tabs](https://github.com/P3pp3rF1y/SophisticatedCore/tree/b3cd0c429dd0daf290869241e1bd6e105d3feffb/src/main/java/net/p3pp3rf1y/sophisticatedcore/upgrades), and [Storage-specific wrappers](https://github.com/P3pp3rF1y/SophisticatedStorage/tree/55257b5900c3b9a1bfdb30267a438c2665d0d7c6/src/main/java/net/p3pp3rf1y/sophisticatedstorage/upgrades).

| Family | Basic or single version | Advanced difference / important boundary |
|---|---|---|
| Pickup | `pickup_upgrade` accepts filtered loose item entities that touch the placed storage. Only the quantity actually inserted leaves the world item. 9 filter slots. | `advanced_pickup_upgrade` has 16 slots and advanced matching. Attraction at a distance belongs to the Magnet. |
| Filter | `filter_upgrade` controls automation input, output or both. 9 criteria with storage/allow/block filtering. | `advanced_filter_upgrade` adds finer matching and 16 slots. |
| Magnet | `magnet_upgrade` finds items at radius 3, with 9 filter slots. XP-orb collection is available with a compatible fluid destination. The wrapper checks available space and handles any remaining XP. | `advanced_magnet_upgrade` reaches radius 5, uses 16 slots and advanced matching. XP orb collection differs from transferring player levels. |
| Feeding | `feeding_upgrade` consumes eligible food from storage for a nearby player, with 9 criteria. Placed search range 3. Baseline timing checks missed hunger against half the food's nutrition and enables immediate feeding when hurt. | `advanced_feeding_upgrade` exposes hunger behavior (any, half or full food value) and immediate-when-hurt control plus 16 advanced criteria. |
| Compacting | `compacting_upgrade` turns qualifying sets into crafted items, normally 2×2. 9 criteria. GUI processing and permission to apply irreversible recipes are separate switches. | `advanced_compacting_upgrade` supports 2×2 and 3×3 plus configured extra patterns, with 16 advanced criteria. The profile adds a hollow 3×3 ring. No configured overrides. Compression is a separate slot arrangement below. |
| Void | `void_upgrade` discards matches. The UI distinguishes always, per-slot overflow and storage overflow and has a GUI processing switch. 9 criteria. Applicable to item or fluid storage with corresponding filter logic. | `advanced_void_upgrade` adds 16 criteria and finer matching. Both versions' server settings permit the always-void choice. Those settings do not prove which choice a player selected. |
| Hopper | `hopper_upgrade` can pull above and push below. 2 input and 2 output criteria, up to 1 item per transfer, 8 ticks between transfers. | `advanced_hopper_upgrade` configures sides separately for push/pull, with 4 input and 4 output criteria, up to 4 items per transfer and a 2-tick interval. |
| Smelting | `smelting_upgrade` supplies a furnace tab with input, fuel and result. | `auto_smelting_upgrade` handles input/fuel from inventory and returns the result to storage. 8 input criteria and 4 fuel criteria. |
| Smoking | `smoking_upgrade` uses smoker recipes in its own tab. | `auto_smoking_upgrade` applies the same automatic transfer and filter arrangement to smoker recipes. |
| Blasting | `blasting_upgrade` uses blast-furnace recipes in its own tab. | `auto_blasting_upgrade` automates corresponding input, fuel and output. All six installed cooking config sections specify 1.0 speed and 1.0 fuel efficiency multipliers. |
| Crafting | `crafting_upgrade` supplies a crafting-table interface within storage. | No advanced Storage registration. |
| Stonecutting | `stonecutter_upgrade` supplies a stonecutting recipe selection/input/output interface. | No advanced Storage registration. Optional sawmill and Chipped recipes are separate registrations. |
| Jukebox | `jukebox_upgrade` takes one disc with play/stop. | `advanced_jukebox_upgrade` has 12 disc slots in rows of 4 and previous/next, shuffle and repeat modes including one, all and no repeat. |
| Fluid pump | `pump_upgrade` transfers between the tank and adjacent fluid handlers in the configured direction. | `advanced_pump_upgrade` adds four fluid filter slots and controls for hands, world fluids and handlers. Advanced hand interaction defaults on, world interaction defaults off, and adjacent handlers default on. Installed base rate is 20 mB per operation, scaled by storage and stack settings, with multiplier ratio 1.0. Both require a fluid host. |
| Experience pump | `xp_pump_upgrade` handles player XP against a tank of compatible experience fluid, at 20 mB per point. Placed search range 3. Separate input, output, keep-level, and off modes, and manual give/take controls. Default target level 10. | No advanced registration. Mending is supported when enabled, with 5 XP points per repair attempt in the profile. This consumes real stored fluid. |
| Alchemy | `alchemy_upgrade` consumes eligible potions, effect-bearing foods or milk against per-filter conditions. 4 criteria. Conditions include fire, water, falling, sprinting, negative effects, mining, hurt, always and never. The placed wrapper searches a 3-block radius. | `advanced_alchemy_upgrade` has 8 criteria, any/all effect checks, duration and amplifier matching and player/entity targeting controls. Chipped's similarly named alchemy bench performs decorative recipe processing instead. |

## Capacity, conversion, compression and Infinity

| Registry family | Exact behavior in this release |
|---|---|
| `stack_upgrade_tier_1`, `stack_upgrade_tier_1_plus`, `stack_upgrade_tier_2`, `stack_upgrade_tier_3`, `stack_upgrade_tier_4`, `stack_upgrade_tier_5` | Multipliers **2, 3, 4, 8, 16, 32**, in that order. Multipliers combine multiplicatively where permitted. An ordinary stack of 64 fits 128, 192, 256, 512, 1,024, or 2,048 per slot with one upgrade and no other modifiers. Positive multipliers support compatible item and fluid hosts. |
| `stack_upgrade_omega_tier` | Multiplier `Integer.MAX_VALUE`, with capacity arithmetic capped at 2,147,483,647. The included recipe is nine tier-5 stack upgrades. Removing items still consumes actual inventory. |
| `stack_downgrade_tier_1`, `_2`, `_3` | Capacity divisors **8, 16, 32**, for item storage. For an ordinary baseline stack of 64, that gives 8, 4 or 2. Capacity-reducing insertion, removal or swap checks existing counts and dependent upgrades before allowing a reduction. |
| `stack_upgrade_tier_<from>_to_tier_<to>_conversion` | All 15 increasing pairs among 1, 1_plus, 2, 3, 4 and 5. Hold the correct conversion on the cursor and right-click its matching installed source upgrade in the menu. The result must be allowed in that slot. One converter is consumed outside creative. No Omega/downgrade conversion in this family. Separate shaped crafting recipes generate the converters and the actual upgrades. |
| `compression_upgrade` | Reserves an eligible contiguous range up to **5** slots. Compatible recipe-linked material forms share a quantity and can be inserted/extracted at different compression levels. Incompatible resident items or missing space prevent installation. Conflicts with Compacting. Empty-space requirements depend on existing compatible contents. Server config explicitly adds 10 decompressions below. |
| `infinity_upgrade` | Admin variant, permission level **2** for setting infinite entries/removal, with break restrictions. The inventory partition exposes unlimited copies of the actual seeded item type and components. It covers the entire inventory and conflicts with other upgrades. |
| `survival_infinity_upgrade` | Same non-depleting seeded slots with permission level **0** for the corresponding operations. The word “Survival” identifies the use policy. The shipped Storage recipe data contains **no recipe yielding either Infinity item**. Both are enabled by name in this profile. Availability to actual players depends on the pack and server. |

Compression config adds 4-unit chains for glowstone/dust, quartz block/quartz, clay/clay ball, snow block/snowball, bricks/brick, and nether bricks/nether brick, plus 9-unit chains for nether wart block/nether wart, melon/melon slice, packed ice/ice, and blue ice/packed ice. The stack blacklist lists vanilla bundles and all uncolored/colored vanilla shulker boxes. [Stack implementation and converters](https://github.com/P3pp3rF1y/SophisticatedCore/tree/b3cd0c429dd0daf290869241e1bd6e105d3feffb/src/main/java/net/p3pp3rf1y/sophisticatedcore/upgrades/stack) · [Compression](https://github.com/P3pp3rF1y/SophisticatedStorage/tree/55257b5900c3b9a1bfdb30267a438c2665d0d7c6/src/main/java/net/p3pp3rf1y/sophisticatedstorage/upgrades/compression) · [Infinity](https://github.com/P3pp3rF1y/SophisticatedCore/tree/b3cd0c429dd0daf290869241e1bd6e105d3feffb/src/main/java/net/p3pp3rf1y/sophisticatedcore/upgrades/infinity)

## Configuration and optional integrations

- In `config/sophisticatedstorage-server.toml`, ordinary chest/barrel/shulker each limits the stack group to 2 upgrades, the jukebox group to 1, and the furnace group to 1. The limited-barrel rules explicitly limit jukebox and furnace to 1 each. Installed upgrade-slot count and any explicit item conflicts still apply. Other observed values include 200 for the excessive item-entity drop threshold, empty extra banned shulker items, `containerItemsDisallowed=false`, limited-barrel count dyeing enabled and an empty generic wood mod list with deny-list semantics.
- In `sophisticatedstorage-common.toml`, `dropPacked=false`. Client options `showSingleWoodVariantOnly` and `showHigherTierGenericWoodVariants` are both false. Core common config explicitly records 150 Storage namespace entries, all enabled. It includes all seven installed Chipped station entries and both Infinity items. Core client uses title-line-right sort buttons, button sounds on, and Mouse Tweaks scroll enabled. The test profile's `defaultconfigs` directory was empty.
- The Storage JAR includes 357 files under `data/sophisticatedstorage/recipe/`. Its explicit item-enabled crafting conditions, optional mod conditions and recipe types are separate from global registration. The list below is grounded in top-level local JAR mod descriptors and conditional recipe JSON. It describes installed candidates for integration. A running server or client would be required to confirm activated hooks.

| Upstream integration | Test-profile evidence and scope |
|---|---|
| Chipped | Present, **4.0.2**. Seven `sophisticatedstorage:chipped/<station>_upgrade` items: `botanist_workbench`, `glassblower`, `carpenters_table`, `loom_table`, `mason_table`, `alchemy_bench`, `tinkering_table`. Each recipe requires its corresponding Chipped workstation, the Storage upgrade base, iron and redstone, with both mod-loaded and item-enabled conditions. Chipped stations convert supported decorative blocks in tabs. |
| Sawmill | `sophisticatedstorage:sawmill/sawmill_upgrade` is implemented, with analogous gated recipe requiring `sawmill:sawmill`. The `sawmill` provider was absent from the profile's top-level mod descriptors. |
| Jade and recipe viewers | Jade **15.10.6**, JEI **19.56.0.441** and EMI **1.1.24** are installed. Storage ships Jade display integration and JEI/EMI/REI plugins. No top-level Roughly Enough Items mod found. |
| Tom's Simple Storage | `toms_storage` **2.4.2** present, exceeding the adapter's 2.2.0 minimum. The Storage adapter identifies the partner half of double chests for Tom's multiblock inventory API. |
| Quark | Version-pinned Storage adapter supports additional vanilla-style chest tier conversion. No top-level Quark mod found. |
| Core adapters | Top-level Curios **9.5.1**, Accessories **1.1.0-beta.53**, Mouse Tweaks **2.26.1**, and Open Parties and Claims **0.33.0** present. Core also ships adapter classes for FTB Chunks, Create, Crafting Tweaks, Inventory Sorter, Item Borders, TrashSlot, and Reliquary. No top-level provider for these was found. The presence of Open Parties and Claims describes available integration, without establishing any specific claim's runtime allow/deny decision. |

Low-level capabilities and optional providers are separate from Storage's own item registry. For example, Core ships battery support, but this Storage build registers no Storage battery upgrade. Storage's physical tank is a block. Upstream includes compatibility exchange recipes for a separate product; these are excluded from this project's feature baseline. [Storage integration registration](https://github.com/P3pp3rF1y/SophisticatedStorage/blob/55257b5900c3b9a1bfdb30267a438c2665d0d7c6/src/main/java/net/p3pp3rf1y/sophisticatedstorage/init/ModCompat.java) · [Storage integrations](https://github.com/P3pp3rF1y/SophisticatedStorage/tree/55257b5900c3b9a1bfdb30267a438c2665d0d7c6/src/main/java/net/p3pp3rf1y/sophisticatedstorage/compat) · [Core integrations](https://github.com/P3pp3rF1y/SophisticatedCore/tree/b3cd0c429dd0daf290869241e1bd6e105d3feffb/src/main/java/net/p3pp3rf1y/sophisticatedcore/compat)



## Recipe index from the installed binary

The following 87 upgrade, conversion and base recipes yield 80 distinct Storage item IDs. Both Infinity items are additional registered upgrades without a bundled crafting output. Each row lists the packaged recipe path relative to `data/sophisticatedstorage/recipe/` (append `.json`). Item and tag ingredients retain namespaces. Quantities describe one recipe and include the input upgrade where applicable. The versioned source link preserves exact shapes, conditions and recipe serializers. The tier-upgrade serializer copies the consumed upgrade’s components into the crafted next tier. [Exact recipe data](https://github.com/P3pp3rF1y/SophisticatedStorage/tree/55257b5900c3b9a1bfdb30267a438c2665d0d7c6/src/generated/resources/data/sophisticatedstorage/recipe) · [Tier recipe implementation](https://github.com/P3pp3rF1y/SophisticatedCore/blob/b3cd0c429dd0daf290869241e1bd6e105d3feffb/src/main/java/net/p3pp3rf1y/sophisticatedcore/crafting/UpgradeNextTierRecipe.java)

| Recipe ID | Output when different | Ingredients for one craft |
|---|---|---|
| `advanced_alchemy_upgrade` | same | `#c:gems/diamond`, 2 × `#c:ingots/gold`, `sophisticatedstorage:alchemy_upgrade`, 3 × `#c:dusts/redstone` |
| `advanced_compacting_upgrade` | same | `#c:gems/diamond`, 2 × `#c:ingots/gold`, `sophisticatedstorage:compacting_upgrade`, 3 × `#c:dusts/redstone` |
| `advanced_feeding_upgrade` | same | `#c:gems/diamond`, 2 × `#c:ingots/gold`, `sophisticatedstorage:feeding_upgrade`, 3 × `#c:dusts/redstone` |
| `advanced_filter_upgrade` | same | 2 × `#c:ingots/gold`, `sophisticatedstorage:filter_upgrade`, 3 × `#c:dusts/redstone` |
| `advanced_hopper_upgrade` | same | `#c:gems/diamond`, 2 × `#c:ingots/gold`, `sophisticatedstorage:hopper_upgrade`, 2 × `#c:dusts/redstone`, `minecraft:dropper` |
| `advanced_jukebox_upgrade` | same | `#c:gems/diamond`, 2 × `#c:ingots/gold`, `sophisticatedstorage:jukebox_upgrade`, 3 × `#c:dusts/redstone` |
| `advanced_magnet_upgrade` | same | 2 × `#c:ender_pearls`, 3 × `#c:ingots/iron`, `sophisticatedstorage:advanced_pickup_upgrade`, `#c:dusts/redstone`, `#c:gems/lapis` |
| `advanced_magnet_upgrade_from_basic` | `advanced_magnet_upgrade` | `#c:gems/diamond`, 2 × `#c:ingots/gold`, `sophisticatedstorage:magnet_upgrade`, 3 × `#c:dusts/redstone` |
| `advanced_pickup_upgrade` | same | `#c:gems/diamond`, 2 × `#c:ingots/gold`, `sophisticatedstorage:pickup_upgrade`, 3 × `#c:dusts/redstone` |
| `advanced_pump_upgrade` | same | 2 × `#c:gems/diamond`, `minecraft:dispenser`, 2 × `#c:ingots/gold`, `sophisticatedstorage:pump_upgrade`, 3 × `#c:dusts/redstone` |
| `advanced_void_upgrade` | same | `#c:gems/diamond`, 2 × `#c:ingots/gold`, `sophisticatedstorage:void_upgrade`, 3 × `#c:dusts/redstone` |
| `alchemy_upgrade` | same | `minecraft:ghast_tear`, `minecraft:glass_bottle`, `minecraft:fermented_spider_eye`, 2 × `#c:ingots/iron`, `sophisticatedstorage:upgrade_base`, 2 × `minecraft:blaze_rod`, `minecraft:ender_pearl` |
| `auto_blasting_upgrade` | same | 2 × `#c:gems/diamond`, 3 × `minecraft:hopper`, `#c:dusts/redstone`, `sophisticatedstorage:blasting_upgrade`, 2 × `#c:ingots/gold` |
| `auto_blasting_upgrade_from_auto_smelting_upgrade` | `auto_blasting_upgrade` | 5 × `#c:ingots/iron`, `sophisticatedstorage:auto_smelting_upgrade`, 3 × `minecraft:smooth_stone` |
| `auto_smelting_upgrade` | same | 2 × `#c:gems/diamond`, 3 × `minecraft:hopper`, `#c:dusts/redstone`, `sophisticatedstorage:smelting_upgrade`, 2 × `#c:ingots/gold` |
| `auto_smoking_upgrade` | same | 2 × `#c:gems/diamond`, 3 × `minecraft:hopper`, `#c:dusts/redstone`, `sophisticatedstorage:smoking_upgrade`, 2 × `#c:ingots/gold` |
| `auto_smoking_upgrade_from_auto_smelting_upgrade` | `auto_smoking_upgrade` | 4 × `#minecraft:logs`, `sophisticatedstorage:auto_smelting_upgrade` |
| `basic_tier_upgrade` | same | 4 × `#c:rods/wooden`, `minecraft:lever` |
| `basic_to_copper_tier_upgrade` | same | 8 × `#c:ingots/copper`, `minecraft:lever` |
| `basic_to_diamond_tier_upgrade` | same | 8 × `#c:gems/diamond`, `sophisticatedstorage:basic_to_gold_tier_upgrade` |
| `basic_to_gold_tier_upgrade` | same | 8 × `#c:ingots/gold`, `sophisticatedstorage:basic_to_iron_tier_upgrade` |
| `basic_to_iron_tier_from_basic_to_copper_tier` | `basic_to_iron_tier_upgrade` | 4 × `#c:ingots/iron`, `sophisticatedstorage:basic_to_copper_tier_upgrade` |
| `basic_to_iron_tier_upgrade` | same | 8 × `#c:ingots/iron`, `minecraft:lever` |
| `basic_to_netherite_tier_upgrade` | same | `sophisticatedstorage:basic_to_diamond_tier_upgrade`, `#c:ingots/netherite` |
| `blasting_upgrade` | same | 4 × `#c:dusts/redstone`, 3 × `#c:ingots/iron`, `sophisticatedstorage:upgrade_base`, `minecraft:blast_furnace` |
| `blasting_upgrade_from_smelting_upgrade` | `blasting_upgrade` | 5 × `#c:ingots/iron`, `sophisticatedstorage:smelting_upgrade`, 3 × `minecraft:smooth_stone` |
| `chipped/alchemy_bench_upgrade` | same | `chipped:alchemy_bench`, 2 × `#c:ingots/iron`, `sophisticatedstorage:upgrade_base`, `#c:dusts/redstone` |
| `chipped/botanist_workbench_upgrade` | same | `chipped:botanist_workbench`, 2 × `#c:ingots/iron`, `sophisticatedstorage:upgrade_base`, `#c:dusts/redstone` |
| `chipped/carpenters_table_upgrade` | same | `chipped:carpenters_table`, 2 × `#c:ingots/iron`, `sophisticatedstorage:upgrade_base`, `#c:dusts/redstone` |
| `chipped/glassblower_upgrade` | same | `chipped:glassblower`, 2 × `#c:ingots/iron`, `sophisticatedstorage:upgrade_base`, `#c:dusts/redstone` |
| `chipped/loom_table_upgrade` | same | `chipped:loom_table`, 2 × `#c:ingots/iron`, `sophisticatedstorage:upgrade_base`, `#c:dusts/redstone` |
| `chipped/mason_table_upgrade` | same | `chipped:mason_table`, 2 × `#c:ingots/iron`, `sophisticatedstorage:upgrade_base`, `#c:dusts/redstone` |
| `chipped/tinkering_table_upgrade` | same | `chipped:tinkering_table`, 2 × `#c:ingots/iron`, `sophisticatedstorage:upgrade_base`, `#c:dusts/redstone` |
| `compacting_upgrade` | same | 2 × `#c:ingots/iron`, 4 × `minecraft:piston`, `sophisticatedstorage:upgrade_base`, 2 × `#c:dusts/redstone` |
| `compression_upgrade` | same | 2 × `#c:ingots/iron`, 2 × `minecraft:piston`, `sophisticatedstorage:upgrade_base`, 2 × `#c:dusts/redstone` |
| `copper_to_diamond_tier_upgrade` | same | 8 × `#c:gems/diamond`, `sophisticatedstorage:copper_to_gold_tier_upgrade` |
| `copper_to_gold_tier_upgrade` | same | 8 × `#c:ingots/gold`, `sophisticatedstorage:copper_to_iron_tier_upgrade` |
| `copper_to_iron_tier_upgrade` | same | 4 × `#c:ingots/iron`, `minecraft:lever` |
| `copper_to_netherite_tier_upgrade` | same | `sophisticatedstorage:copper_to_diamond_tier_upgrade`, `#c:ingots/netherite` |
| `crafting_upgrade` | same | `minecraft:crafting_table`, 2 × `#c:ingots/iron`, `sophisticatedstorage:upgrade_base`, `#c:chests` |
| `diamond_to_netherite_tier_upgrade` | same | `minecraft:lever`, `#c:ingots/netherite` |
| `feeding_upgrade` | same | `minecraft:golden_carrot`, `minecraft:golden_apple`, `sophisticatedstorage:upgrade_base`, `minecraft:glistering_melon_slice`, `#c:ender_pearls` |
| `filter_upgrade` | same | 4 × `#c:dusts/redstone`, 4 × `#c:strings`, `sophisticatedstorage:upgrade_base` |
| `gold_to_diamond_tier_upgrade` | same | 8 × `#c:gems/diamond`, `minecraft:lever` |
| `gold_to_netherite_tier_upgrade` | same | `sophisticatedstorage:gold_to_diamond_tier_upgrade`, `#c:ingots/netherite` |
| `hopper_upgrade` | same | `minecraft:hopper`, 2 × `#c:ingots/iron`, `sophisticatedstorage:upgrade_base`, 3 × `#c:dusts/redstone` |
| `iron_to_diamond_tier_upgrade` | same | 8 × `#c:gems/diamond`, `sophisticatedstorage:iron_to_gold_tier_upgrade` |
| `iron_to_gold_tier_upgrade` | same | 8 × `#c:ingots/gold`, `minecraft:lever` |
| `iron_to_netherite_tier_upgrade` | same | `sophisticatedstorage:iron_to_diamond_tier_upgrade`, `#c:ingots/netherite` |
| `jukebox_upgrade` | same | `minecraft:jukebox`, 2 × `#c:ingots/iron`, `sophisticatedstorage:upgrade_base`, `#c:dusts/redstone` |
| `magnet_upgrade` | same | 2 × `#c:ender_pearls`, 3 × `#c:ingots/iron`, `sophisticatedstorage:pickup_upgrade`, `#c:dusts/redstone`, `#c:gems/lapis` |
| `pickup_upgrade` | same | `minecraft:sticky_piston`, 2 × `#minecraft:planks`, `sophisticatedstorage:upgrade_base`, 3 × `#c:dusts/redstone` |
| `pump_upgrade` | same | 4 × `#c:glass_blocks`, 2 × `minecraft:bucket`, `minecraft:piston`, `sophisticatedstorage:upgrade_base`, `minecraft:sticky_piston` |
| `sawmill/sawmill_upgrade` | same | `sawmill:sawmill`, 2 × `#c:ingots/iron`, `sophisticatedstorage:upgrade_base`, `#c:dusts/redstone` |
| `smelting_upgrade` | same | 4 × `#c:dusts/redstone`, 3 × `#c:ingots/iron`, `sophisticatedstorage:upgrade_base`, `minecraft:furnace` |
| `smoking_upgrade` | same | 4 × `#c:dusts/redstone`, 3 × `#c:ingots/iron`, `sophisticatedstorage:upgrade_base`, `minecraft:smoker` |
| `smoking_upgrade_from_smelting_upgrade` | `smoking_upgrade` | 4 × `#minecraft:logs`, `sophisticatedstorage:smelting_upgrade` |
| `stack_downgrade_tier_1` | same | 5 × `#c:rods/wooden`, 3 × `minecraft:flint`, `sophisticatedstorage:upgrade_base` |
| `stack_downgrade_tier_2` | same | 4 × `minecraft:flint`, 4 × `#c:rods/wooden`, `sophisticatedstorage:upgrade_base` |
| `stack_downgrade_tier_3` | same | 3 × `#c:rods/wooden`, 5 × `minecraft:flint`, `sophisticatedstorage:upgrade_base` |
| `stack_upgrade_omega_tier` | same | 9 × `sophisticatedstorage:stack_upgrade_tier_5` |
| `stack_upgrade_tier_1` | same | 8 × `#minecraft:logs`, `sophisticatedstorage:upgrade_base` |
| `stack_upgrade_tier_1_plus` | same | 6 × `#c:ingots/copper`, `sophisticatedstorage:stack_upgrade_tier_1`, 2 × `#c:storage_blocks/copper` |
| `stack_upgrade_tier_1_plus_to_tier_2_conversion` | same | 3 × `#c:ingots/iron`, `#minecraft:planks`, `#c:storage_blocks/iron` |
| `stack_upgrade_tier_1_plus_to_tier_3_conversion` | same | 6 × `#c:ingots/gold`, `sophisticatedstorage:stack_upgrade_tier_1_plus_to_tier_2_conversion`, 2 × `#c:storage_blocks/gold` |
| `stack_upgrade_tier_1_plus_to_tier_4_conversion` | same | 6 × `#c:gems/diamond`, `sophisticatedstorage:stack_upgrade_tier_1_plus_to_tier_3_conversion`, 2 × `#c:storage_blocks/diamond` |
| `stack_upgrade_tier_1_plus_to_tier_5_conversion` | same | 6 × `#c:ingots/netherite`, `sophisticatedstorage:stack_upgrade_tier_1_plus_to_tier_4_conversion`, 2 × `#c:storage_blocks/netherite` |
| `stack_upgrade_tier_1_to_tier_1_plus_conversion` | same | 6 × `#c:ingots/copper`, `#minecraft:planks`, 2 × `#c:storage_blocks/copper` |
| `stack_upgrade_tier_1_to_tier_2_conversion` | same | 6 × `#c:ingots/iron`, `sophisticatedstorage:stack_upgrade_tier_1_to_tier_1_plus_conversion`, 2 × `#c:storage_blocks/iron` |
| `stack_upgrade_tier_1_to_tier_3_conversion` | same | 6 × `#c:ingots/gold`, `sophisticatedstorage:stack_upgrade_tier_1_to_tier_2_conversion`, 2 × `#c:storage_blocks/gold` |
| `stack_upgrade_tier_1_to_tier_4_conversion` | same | 6 × `#c:gems/diamond`, `sophisticatedstorage:stack_upgrade_tier_1_to_tier_3_conversion`, 2 × `#c:storage_blocks/diamond` |
| `stack_upgrade_tier_1_to_tier_5_conversion` | same | 6 × `#c:ingots/netherite`, `sophisticatedstorage:stack_upgrade_tier_1_to_tier_4_conversion`, 2 × `#c:storage_blocks/netherite` |
| `stack_upgrade_tier_2` | same | 6 × `#c:ingots/iron`, `sophisticatedstorage:stack_upgrade_tier_1`, 2 × `#c:storage_blocks/iron` |
| `stack_upgrade_tier_2_from_tier_1_plus` | `stack_upgrade_tier_2` | 3 × `#c:ingots/iron`, `sophisticatedstorage:stack_upgrade_tier_1_plus`, `#c:storage_blocks/iron` |
| `stack_upgrade_tier_2_to_tier_3_conversion` | same | 6 × `#c:ingots/gold`, `#minecraft:planks`, 2 × `#c:storage_blocks/gold` |
| `stack_upgrade_tier_2_to_tier_4_conversion` | same | 6 × `#c:gems/diamond`, `sophisticatedstorage:stack_upgrade_tier_2_to_tier_3_conversion`, 2 × `#c:storage_blocks/diamond` |
| `stack_upgrade_tier_2_to_tier_5_conversion` | same | 6 × `#c:ingots/netherite`, `sophisticatedstorage:stack_upgrade_tier_2_to_tier_4_conversion`, 2 × `#c:storage_blocks/netherite` |
| `stack_upgrade_tier_3` | same | 6 × `#c:ingots/gold`, `sophisticatedstorage:stack_upgrade_tier_2`, 2 × `#c:storage_blocks/gold` |
| `stack_upgrade_tier_3_to_tier_4_conversion` | same | 6 × `#c:gems/diamond`, `#minecraft:planks`, 2 × `#c:storage_blocks/diamond` |
| `stack_upgrade_tier_3_to_tier_5_conversion` | same | 6 × `#c:ingots/netherite`, `sophisticatedstorage:stack_upgrade_tier_3_to_tier_4_conversion`, 2 × `#c:storage_blocks/netherite` |
| `stack_upgrade_tier_4` | same | 6 × `#c:gems/diamond`, `sophisticatedstorage:stack_upgrade_tier_3`, 2 × `#c:storage_blocks/diamond` |
| `stack_upgrade_tier_4_to_tier_5_conversion` | same | 6 × `#c:ingots/netherite`, `#minecraft:planks`, 2 × `#c:storage_blocks/netherite` |
| `stack_upgrade_tier_5` | same | 6 × `#c:ingots/netherite`, `sophisticatedstorage:stack_upgrade_tier_4`, 2 × `#c:storage_blocks/netherite` |
| `stonecutter_upgrade` | same | `minecraft:stonecutter`, 2 × `#c:ingots/iron`, `sophisticatedstorage:upgrade_base`, `#c:dusts/redstone` |
| `upgrade_base` | same | 5 × `#minecraft:planks`, 4 × `#c:ingots/iron` |
| `void_upgrade` | same | `#c:ender_pearls`, 3 × `#c:obsidians`, `sophisticatedstorage:upgrade_base`, 2 × `#c:dusts/redstone` |
| `xp_pump_upgrade` | same | 4 × `#c:dusts/redstone`, 2 × `minecraft:ender_eye`, 2 × `minecraft:experience_bottle`, `sophisticatedstorage:advanced_pump_upgrade` |

The installed local loose/global datapack directories and 16 ZIPs under the profile’s `datapacks/` contained no recipe in the Storage namespace or with a literal Storage item reference. Other mods can still contribute tag contents or runtime changes. No worlds were opened and no game was run.
