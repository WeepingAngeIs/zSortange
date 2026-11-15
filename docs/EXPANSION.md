# Expansion milestone 1: elements, wardrobe and interiors

## Separate choices, preserved identity

Anya Storage Plus keeps `Element`, `LeafSpecies`, `Skin`, `Interior` and the optional interior image reference separate from `Tier`, saved capacity and upgrade slots. Applying a binding or skin patch copies the original backpack stack, preserving its UUID, contents, upgrades, custom name and other item components. It does not reconstruct a fresh empty backpack. Existing beta.1 bags default to the plain appearance and do not require an inventory migration.

A binding or patch is consumed once to unlock that choice on the backpack. Open the backpack's **Style** panel to switch among unlocked elements/skins without paying again. The default `elemental` skin follows the selected element. A chosen Bow Cat, Galaxy or other skin does not change stats and is not erased by a capacity upgrade. The unlocked collection stays with the bag, not a global player entitlement.

## Acquisition

The default leather recipe uses six Leather, two String and one Chest. Each leaf starter uses four matching leaves, two Leather and two String. Oak, spruce, birch, jungle, acacia, dark oak, mangrove and cherry each have a recipe and appearance. No new ores are generated.

The five elemental bindings use four Cobblestone, Diamonds, Magma Blocks, Prismarine Shards or accepted Leaves, respectively, around one String. Combine one binding with one backpack. Cosmetic patches use two Paper, a dye, a themed vanilla material and String. The exact recipes are generated as normal recipe JSON; **Ancient Technology** reads the active recipe manager so pack overrides and disabled recipes are reflected rather than silently contradicted.

Default capacity/upgrade-slot progression remains Starter 27/1, Iron 45/2, Gold 63/3, Diamond 81/4 and Netherite 108/5. Optional tiers use the existing in-game tier editor and exact installed material registry. Dragonsteel and Witherite are not hard dependencies or manufactured ingots. Missing materials remain unavailable. Capacity is saved on an existing bag; changing definitions does not silently truncate its inventory.

Tier-based home radius bonuses are not implemented in this milestone. The existing hub's ore-fed radius remains the authority for ordinary network access.

## Worn effects

Use **Wear this backpack** in the wardrobe. The selection references one real backpack in main inventory or offhand; it does not make a second copy, replace armor, or use a Curios slot. Duplicate matches for the selected BagId disable its benefits. Merely carrying several bags does not stack their attributes.

| Element or tier | Default effect while worn |
| --- | --- |
| Plain / leaf | No combat attribute bonus |
| Stone | +2 armor points |
| Diamond | +4 armor points |
| Magma | 20% reduction to damage tagged as fire; no immunity or extinguishing |
| Water | +10% swim-speed multiplier while in water; no water breathing |
| Netherite and configured descendants | +0.025 knockback resistance |

Server operators can disable effects or adjust their bounded values. Fixed transient modifier IDs avoid accumulating duplicate bonuses. Effects are reconciled during player ticks and cleared on unequip, loss of the selected item, death, logout and clone handling. The mod does not remove another mod's modifiers or supply permanent fire resistance. Balance with the installed Origins/armor setup is still a manual acceptance item.

## Cosmetics and controls

Twenty-two model/texture variants cover the plain/elemental looks, eight leaf species and nine optional skins: Bow Cat, Midnight Cat, Spooky, Cute Spooky, Futuristic, Galaxy, Oak, Cherry and Dark Oak. They use original stitched pixel surfaces, buckles, pockets and blocky silhouette details.

Six built-in interiors are Atelier, Botanical, Midnight, Galaxy, Circuit and Ocean. Cycle the interior in the wardrobe; choosing a built-in lining detaches the custom image without deleting it from the library. Custom icons replace the seven basic upgrade item images. The left panel displays the selected backpack model and an authorized private image where present.

Client controls toggle wearable models, cosmetic particles and private image display. Client preferences are in `config/anya-storage-plus-client.json`. Models are culled beyond 48 blocks. Particles are culled beyond 24 blocks, default to a global client budget of four spawns per second and have a hard ceiling of eight. Leaf/cherry particles require movement; restrained Galaxy stars may twinkle at rest. Magma/water use small themed particles. This is cosmetic only and does not change server item transfers.

The rendered asset captures verify Minecraft item-model/texture loading and book composition. They do not certify placement on every player/Figura/Origins model, shader pack or GUI interaction.

## Monster backpacks

Default OFF. Eligible natural/chunk-generation monsters have a 1.5% default selection chance and a 25% default player-kill reward chance. The default species list is zombie, husk, skeleton and stray. Operators can configure the list, probability and drop chance. Render layers currently cover vanilla humanoid zombie/skeleton families; arbitrary listed modded monsters are not automatically rendered.

The monster wears a decorative data marker, not an armor-slot item. Existing names, custom data and armor are checked before selection. The system never overwrites equipment. A reward is a freshly initialized, empty starter with a new ID, not a copy of a monster/player inventory. Existing marked monsters may require retracking after display policy changes. Full-pack Apotheosis/spawn-mod behavior still needs testing.

## Additional direction, not shipped promises

The implemented per-backpack unlock collection supports a collecting progression without sacrificing a favorite skin. Later cosmetic-only additions could include biome discovery patches and small removable charms. These should remain independent of capacity and combat effects. They are ideas, not unimplemented buttons advertised as working features.
