# Anya Storage beta: installation and first base

## Expansion milestone 1

This build adds the elemental wardrobe, redesigned interiors, optional private image uploads and **Ancient Technology**. See [EXPANSION](EXPANSION.md), [TUTORIAL](TUTORIAL.md) and [IMAGES](IMAGES.md). It does NOT add pocket dimensions or portals. Keep the prior beta JAR as a rollback artifact, but do not leave both versions in one mods directory. Never downgrade a valuable updated world without its matching backup.

The first-login guide is automatic by default. Right-click it; `/anya guide recover` is the explicit lost-copy route while carrying one plain Book. `/give @s anyastorage:ancient_technology` is available for operator testing.

This delivery does not modify any installed profile. Choose a disposable or backed-up test world deliberately; keep the same version on its server and clients.

## Environment and safe installation

Use Minecraft 1.21.1, Java 21 and NeoForge 21.1.249 or a later compatible 21.1.x build. Install the same `anya-storage-plus-0.3.0-beta.1.jar` on the server and every connecting client. The sources JAR is not the game mod. Optional material mods are not required.

Start with a backed-up test world. Keep Tom's Simple Storage, Sophisticated Storage/Backpacks, their dependencies and QuickStack installed while validating this beta. There is no migration of their contents. Carry On and Shulker Box Tooltip are not replaced by this project. Do not delete Sophisticated Core until all remaining dependencies have been checked.

The first start creates `config/anya-storage-plus.json`. Operators edit it in-game using `/anya storage` or `/as`; the alias is registered only if not already used. Ordinary players get their protected-slot preferences, not server settings. Config changes are private drafts until Review and Apply. Apply writes a backup and reloads recipes. The command is for settings; the handheld remote is the normal storage interface.

## First test base

Obtain an Anya Storage Hub, Labelled Storage Chest, Storage Remote and Starter Backpack from the Anya Storage creative tab or use these operator commands:

```mcfunction
/give @s anyastorage:hub
/give @s anyastorage:chest
/give @s anyastorage:remote
/give @s anyastorage:backpack
```

Place the hub and chest close together. The initial radius is 16 blocks, measured in three dimensions. Ownership is assigned to the placing player. Put test items in the custom chest by opening it and clicking items in the inventory portion of the screen. Ordinary vanilla chests and barrels are also eligible by default. Shulker boxes are off by default and can be enabled in settings. Arbitrary other mods' containers, player-specific inventories and unopened loot containers are not supported in this beta.

Sneak-right-click the hub with the remote to bind it. Right-click the remote while inside that hub's radius to search its loaded, permitted containers. Discovery is incremental and budgeted; a newly placed or very large base may not appear immediately. The mod never force-loads storage chunks.

Storage screens show real stacks from physical containers, not a second virtual copy. Left-click a listed stack to withdraw it, right-click to withdraw one, and middle-click to favorite that item type. Click an inventory slot below to deposit its stack, or right-click to deposit one. The input bar searches names, registry IDs, `@mod` namespaces and `#tags`. Display icons cannot be dragged out as items.

The Organize button shows a before/after preview; a separate confirmation applies it. Changes to inventories, permissions, configuration or routing invalidate stale plans. A generic overflow chest is useful when designated category containers fill up.

## Base profile, ore feeder and routing

The owner opens Base Profile from the hub screen to name it, manage trust, see performance and feed resources. Trust accepts an online player name or a full UUID. Trust does not bypass claims. Only the owner or an operator may edit the base profile or break the custom storage block.

In Base Profile, click an inventory resource to preview feeding it. Left selects a stack, right selects one. The confirmation shows quantity consumed, points gained, current progress and the next radius cost. Feeding adds points only; the radius-upgrade button separately spends the displayed cost. Default maximum radius is 64, in steps of 8. Operators can edit values, costs and limits in-game. Filled containers and backpacks cannot be burned as feeder fuel.

Sneak-use a bound remote on a supported chest within the hub's radius to configure that chest's category, exact-item filters, priority and overflow. The acting player's claim and lock permissions still apply. Rules belong to that base. Operators can additionally define global item/tag-to-category mappings in the settings menu. Exact item rules take precedence over tag rules.

## QuickStack, Dump and explanations

Assign the initially unbound QuickStack, Dump and Open Backpack keys in Minecraft Controls. Secondary commands are `/anya quickstack`, `/anya dump` and `/anya slots`.

QuickStack moves only items with an existing identical item-and-component match. Dump accepts existing item-type matches more broadly, and designated category/item destinations inside a base. Neither shortcut empties unrelated items into arbitrary empty chests. The main 36 inventory slots are eligible; armor and offhand are never swept. Hotbar slots are protected by default. Protection affects automatic deposits, not an explicit manual deposit click.

Use the protected-slot menu to toggle protection. Shift-click an inventory slot there to preview why QuickStack would accept or skip it; shift-right-click checks Dump. The diagnostics page also previews individual inventory items. Previews move nothing. The last completed action reports moved, protected, unmatched and full/filtered counts.

## Backpack progression and upgrades

Vanilla defaults are Starter 27/1, Iron 45/2, Gold 63/3, Diamond 81/4 and Netherite 108/5, where the numbers are inventory slots/upgrade slots. Upgrade recipes use one predecessor backpack and the configured number of occupied ingredient cells, each consuming one material item. The special upgrade recipe preserves the existing backpack identity, contents, item components and installed upgrades. The in-game tier page is the authoritative recipe preview; dynamic tier recipes are not an EMI/JEI integration.

Sneak-use a backpack to manage installed upgrades. Craft an upgrade, carry it in main inventory, then left-click its icon to install. Right-click returns the exact stored upgrade item, provided there is room. Put an offhand backpack into main inventory before editing its installed upgrades.

- Pickup stores items from main inventory after normal vanilla pickup. Its empty filter matches existing exact stacks; configured filters accept specified item IDs. It is not a ground-item vacuum.
- Refill tops up existing matching hotbar stacks from the backpack.
- Feeding consumes ordinary safe vanilla food only, not custom-component or special-effect food.
- Crafting opens a portable vanilla crafting grid.
- Smelting requires an explicit input filter and ordinary coal/charcoal inside the backpack. It uses the server's smelting recipes, saves progress/fuel, handles recipe remainders and awards recipe experience.
- Compacting is an explicit button for supported, verified vanilla 3x3 compression recipes. It does not discard custom components from named or modified materials.
- Resupply is an explicit button that puts a stored tool/sword in an empty selected hotbar slot. It is not automatic replacement after a tool breaks.

Automatic upgrades run on the first upgraded backpack in main inventory, once per second, and pause while a container screen is open. Nested backpacks, bundles, filled portable containers and block-entity-data containers are rejected. Capacity is allocated on the backpack item itself; reducing a tier's configured size never truncates existing backpacks.

## Dragonsteel, Witherite and other optional materials

Nothing manufactures these ingots. Compatibility controls storage-tier recipes that CONSUME installed materials. Turn on the compatibility master switch, inspect actual detected ingot candidates, or hold a material and choose Add Held Material. A new optional tier starts disabled. Set its name, predecessor, capacity, upgrade slots and ingredient-cell cost, enable it, inspect the reason/recipe preview, then Review and Apply. Missing material IDs cannot be newly enabled. No specific installed Ice and Fire or Cataclysm version is assumed.

The default policy requires the immediate predecessor. Optional fallback to an earlier available tier must be deliberately enabled. Cycles, ambiguous recipes and shrinking progression are rejected before saving. Disabled tier items remain usable; only their recipes/behaviors are gated as described in the menu.

## Endgame cube and labels

The UwU cube has 216 ordinary stack slots. Its recipe defaults to OFF. Operators can explicitly select either one Storage Core plus four Nether Stars and four Dragon's Breath, or one Storage Core plus four Nether Stars and four Dragon Victory Tokens. The Storage Core itself costs eight diamond blocks and one netherite block. The endgame recipe group must also be enabled. Dragon's Breath does not prove repeated kills. A token is awarded once per tracked End-fight dragon UUID when a survival player lands the final blow. Creative kills and untracked spawned dragons do not qualify.

Custom chest labels cycle through heart, star, square, circle, diamond, leaf and gear. Their top and bottom halves are rendered on the moving lid and stationary base. The server label switch hides the sticker without removing items or ownership. The cube has its own face/heart texture.

## Known limits

This is a compiled beta, not a claim of full feature parity with the replaced mods. There is no dedicated equipment/Curios backpack slot, fluid/XP tank, magnet vacuum, arbitrary-container capability adapter, or EMI/JEI recipe plugin. Custom containers intentionally expose no unowned hopper/item-handler access. The exact live modpack, optional material mods, claim integration and a real multiplayer client session still need pack-level testing. Hard process-kill or disk-failure durability across vanilla player/chunk files is not guaranteed. See SAFETY.md and TESTING.md before removing existing storage mods.
