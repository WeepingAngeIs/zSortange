# Anya Storage Plus

**0.3.0-beta.1, expansion milestone 1**. Minecraft **1.21.1**, NeoForge **21.1.249+ within 21.1.x**, Java **21**. The mod ID remains `anyastorage`; this extends the existing storage mod, not a replacement project.

The verified `0.2.0-beta.1` remains on `main` while this milestone is developed on `feature/elemental-pockets`. No installed profile, live server or valuable world is modified by producing this build. Use the same playable JAR on server and clients in a disposable or backed-up test world. Do not install two versions together.

## Implemented in this milestone

- Independent elemental identity, cosmetic skin/interior and storage tier. Storage upgrades retain contents, item components, custom name, BagId, installed upgrades and appearance.
- Leather starter plus eight leaf-species starters, craftable elemental bindings and nine cosmetic patches using existing vanilla materials. No ore generation.
- One selected worn backpack, modest configurable armor/fire-reduction/swim/knockback effects, and bounded client-only cosmetic particles.
- Twenty-two blocky model/texture variants, six built-in interior themes, custom upgrade icons and an in-game wardrobe. Bow Cat and Midnight Cat are original cat designs, not branded/licensed character assets.
- NEW private, server-stored interior PNG uploads with strict validation, quotas, rate limits, explicit viewers and operator moderation. Uploads and sharing default OFF.
- Optional decorative monster backpacks with fresh empty drops, default OFF. They never occupy or overwrite an equipment slot.
- **Ancient Technology**, an illustrated tutorial item with live server recipes/settings, chapter navigation, a durable one-time automatic grant and explicit recovery.

Existing hub/remote search, physical containers, QuickStack, routing, installable upgrades, configurable optional material tiers, safe settings drafts and previews remain. Exact installed Dragonsteel/Witherite progression must still be verified in the target pack; use Add Held Material rather than inventing IDs.

## Not implemented here

Pocket dimensions, pocket scenery, separate visit/view/deposit/withdraw permissions, shared pocket logs, home-to-pocket access, a portal preview block and live portal rendering are **not implemented**. `pocketStorage=true` is rejected. No Immersive Portals dependency is installed or bundled. The approved low-cost preview remains a future design requirement, not a shipped renderer.

## Start here

Right-click **Ancient Technology**, normally given on the first login after installing this version. `/anya guide` opens an owned copy; `/anya guide recover` consumes one ordinary Book. A Book + Paper recovery craft is also available. Automatic grant/recovery are independently configurable in the operator expansion policy.

Use `/anya storage` or collision-safe `/as` for settings. The bound handheld remote remains the everyday storage interface. Open a backpack and choose **Style** for its wardrobe. Craft a binding/patch, combine it with one backpack, then freely switch among that backpack's unlocked looks. Choose **Wear this backpack** for its worn effects/model.

## Documentation and evidence

- [Installation and existing storage controls](docs/INSTALL.md)
- [Elemental, cosmetic and progression details](docs/EXPANSION.md)
- [Ancient Technology and grant persistence](docs/TUTORIAL.md)
- [Image upload security and privacy](docs/IMAGES.md)
- [Immersive Portals CE source audit and unimplemented pocket plan](docs/PORTAL_AUDIT.md)
- [Automated checks and remaining acceptance tests](docs/TESTING.md)
- [Inventory safety boundaries](docs/SAFETY.md)

The read-only build workflow compiles, runs configuration/image/journal assertions, launches 40 required GameTests, starts the dedicated server twice against a disposable persistence world, and captures the actual Minecraft renderer. The artifact contains `SOURCE_COMMIT.txt`, logs, source, screenshots and SHA-256 checksums. Consult that exact run's result; source code alone is not evidence of a passing build.

Full-pack multiplayer, real client disconnects, image upload round trips, wardrobe interaction, worn placement with Figura/Origins, Carry On, OPAC and the actual renderer stack still require acceptance testing. Do not remove the old storage mods or assume hard-crash consistency across separate Minecraft save files.
