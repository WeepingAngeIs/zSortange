# Anya Storage Plus

**Skygrid camera candidate** for Minecraft 1.21.1, NeoForge 21.1.249 and Java 21. Select your outdoor camera with the tablet, link your wall monitor, and look at the screen. The monitor shows nearby visible surfaces with their block textures, local lighting, rain and a limited set of moving figures. A visible player uses a standard Minecraft model. The outdoor area must already be loaded, and the camera and monitor must both allow you to view them. Looking at a screen does not keep its outdoor area loaded.

With room building enabled, enter with Build permission and place a vanilla chest against a wall, in a corner or elsewhere inside the room. Outside, right-click your linked tablet to use its storage. Leave the landing and exit clear. New rooms start empty. Existing chests keep their items inside the original blocks. The entrance keeps its illustration.

### Earlier milestones


The Twin Tails tablet beta restores eight close-up Cupid sequences on both physical tablets. [Expression guide and timings](docs/TWIN_TAILS_EXPRESSIONS.md).

For the experimental field-kit build, see [player controls for storage, Keepsake Globes, Delivery Hatches, room planning, the Cupid Tablet and the UwU Tablet](docs/FIELD_KIT.md).

**0.3.0-beta.6, gated room-editing safety milestone.** Continues beta.5 in the same mod and branch. See [the exact narrow editing scope and acceptance boundaries](docs/ROOM_EDITING_BETA6.md). No profile or player world is modified by building this source.

The four original pocket chests, shell and recovery paths remain protected. Separate Visit and Build rights, eight inert decorative materials, and four optional plain Anya-chest pads are implemented behind the additional default-off `pocketEditing` switch. Arbitrary containers, machinery, unrestricted construction and live portal rendering are not implemented. Existing backpack art/UI and the non-live entrance preview are retained.

The sections below are retained historical beta.4 notes. For the newer packaged beta.5 acceptance and beta.6 room scope, use the exact tagged/source-commit evidence, not the historical untested list.


**0.3.0-beta.4, gated home-pocket safety prototype**. Minecraft **1.21.1**, NeoForge **21.1.249+ within 21.1.x**, Java **21**. The mod ID remains `anyastorage`; this extends the existing storage mod, not a replacement project.

The verified `0.2.0-beta.1` remains on `main` while this milestone is developed on `feature/elemental-pockets`. No installed profile, live server or valuable world is modified by producing this build. Use the same playable JAR on server and clients in a disposable or backed-up test world. Do not install two versions together.

## Backpack interface update

Beta.3 adds a true-capacity scrollable storage view, separate cosmetic/control rails, a conflict-aware configurable B shortcut, native optional Accessories back-slot access, and restrained pixel-art refinements. See [Controls, evidence and limits](docs/UI_BETA3.md). No profile or world is installed or migrated by this build.

## Implemented in this milestone

New in beta.2: independent home-hub View/Deposit/Withdraw/Log permissions, privacy-first bounded activity history, legacy trust preservation, and dedicated-server restart coverage for the added fields. See [Shared bases](docs/SHARED_BASES.md).

- Independent elemental identity, cosmetic skin/interior and storage tier. Storage upgrades retain contents, item components, custom name, BagId, installed upgrades and appearance.
- Leather starter plus eight leaf-species starters, craftable elemental bindings and nine cosmetic patches using existing vanilla materials. No ore generation.
- One selected worn backpack, modest configurable armor/fire-reduction/swim/knockback effects, and bounded client-only cosmetic particles.
- Twenty-two blocky model/texture variants, six built-in interior themes, custom upgrade icons and an in-game wardrobe. Bow Cat and Midnight Cat are original cat designs, not branded/licensed character assets.
- NEW private, server-stored interior PNG uploads with strict validation, quotas, rate limits, explicit viewers and operator moderation. New configurations allow private uploads. Sharing defaults off.
- Optional decorative monster backpacks with fresh empty drops, default OFF. They never occupy or overwrite an equipment slot.
- **Ancient Technology**, an illustrated tutorial item with live server recipes/settings, chapter navigation, a durable one-time automatic grant and explicit recovery.

Existing hub/remote search, physical containers, QuickStack, routing, installable upgrades, configurable optional material tiers, safe settings drafts and previews remain. Exact installed Dragonsteel/Witherite progression must still be verified in the target pack; use Add Held Material rather than inventing IDs.

## Gated home-pocket prototype

Beta.4 adds a distinct craftable Home Void Cube entrance, durable identity/return ledger, separate Visit right, bounded active leases, and protected physical-chest pocket access behind an explicit disposable-test JVM gate plus the server switch. It does not turn the old UwU inventory cube into a dimension. Existing beta.3 backpack features remain.

Read [Pocket milestone, gates and exact limitations](docs/POCKET_MILESTONE.md) before attempting a disposable-world test. Construction, arbitrary storage/machinery, live portal rendering and full-pack approval are NOT implemented. Normal launches still reject enabling pocket storage. No portal dependency or installed profile is changed.

## Start here

Right-click **Ancient Technology**, normally given on the first login after installing this version. `/anya guide` opens an owned copy; `/anya guide recover` consumes one ordinary Book. A Book + Paper recovery craft is also available. Automatic grant/recovery are independently configurable in the operator expansion policy.

Use `/anya storage` or collision-safe `/as` for settings. The bound handheld remote remains the everyday storage interface. Open a backpack and choose **Wardrobe** for its wardrobe. Craft a binding/patch, combine it with one backpack, then freely switch among that backpack's unlocked looks. Choose **Wear this backpack** for its worn effects/model.

## Documentation and evidence

- [Installation and existing storage controls](docs/INSTALL.md)
- [Elemental, cosmetic and progression details](docs/EXPANSION.md)
- [Ancient Technology and grant persistence](docs/TUTORIAL.md)
- [Image upload security and privacy](docs/IMAGES.md)
- [Immersive Portals CE source audit and unimplemented pocket plan](docs/PORTAL_AUDIT.md)
- [Automated checks and remaining acceptance tests](docs/TESTING.md)
- [Inventory safety boundaries](docs/SAFETY.md)

The read-only build workflow compiles, runs configuration/image/journal/layout assertions, launches the required GameTests with and without the optional Accessories test dependency, starts the dedicated server twice against a disposable persistence world, and captures the actual Minecraft renderer with and without Accessories. The artifact contains `SOURCE_COMMIT.txt`, logs, source, screenshots and SHA-256 checksums. Consult that exact run's result; source code alone is not evidence of a passing build.

Full-pack multiplayer, real client disconnects, image upload round trips, wardrobe interaction, worn placement with Figura/Origins, Carry On, OPAC and the actual renderer stack still require acceptance testing. Do not remove the old storage mods or assume hard-crash consistency across separate Minecraft save files.
