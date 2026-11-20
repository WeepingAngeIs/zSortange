# Backpack interface and native back slot (0.3.0-beta.3)

This continues `feature/elemental-pockets`, retaining the same mod ID, inventory components, BagId, recipes, unlocked skins, explicit interior choices, guide grant records and shared-base policy. Nothing in the build installs a JAR into a player profile. Use matching beta.3 on client and server; the menu protocol is version 3 and intentionally rejects old clients.

## What was inspected

The supplied November 14, 2026 client crash log lists Accessories 1.1.0-beta.53+1.21.1, Accessories Compatibility Layer 0.1.10+1.21.1 and Curios 9.5.1+1.21.1. This is evidence about that logged instance, not confirmation of the current test profile. The latest accessible screenshots include both Anya's Oak Grove Backpack and Sophisticated Backpacks' Turtle Backpack. Their interaction behavior must not be conflated. Anya's existing source does not place a backpack block: ordinary use opens storage, and sneak-use opens its upgrade manager. This release does not modify Sophisticated Backpacks or Carry On.

The native Accessories source was inspected at `wisp-forest/accessories` commit `25c072741da8797fc0f6b0f5627c85378aec3808`, whose gradle.properties identifies beta.53 for Minecraft 1.21.1. The adapter uses its actual AccessoriesCapability, back container, SlotReference, AccessoriesAPI and Accessory registration contracts. Accessories remains optional and is not bundled. There is no separate Curios-only adapter or claim of full-pack compatibility.

## Wearing and opening

All single Anya backpack stacks, including starter and leaf starters, are eligible for the native Accessories **back** slot, regardless of capacity tier or skin. Use that mod's equipment screen. The adapter does not create a second inventory, copy the bag, occupy an armor slot, overwrite other gear or traverse its cosmetic slots. Without Accessories, the existing wardrobe-selected worn bag remains available. Crafting bindings and cosmetic patches still work as before.

Open Anya Backpack is configurable in Minecraft Controls. A fresh binding defaults to **B**; an existing saved binding, including an unbound key, stays as saved. QuickStack and Dump remain unbound by default. The client checks active bindings for the same key/modifier and reports a conflict instead of opening Anya storage on top of another action. It never rewrites another mod's keys. The exact current profile's options.txt was not available, so B cannot be declared free in that profile.

Selection priority is: native functional back slot (lowest index), main-hand Anya backpack, the explicitly wardrobe-selected inventory bag, remaining main-inventory bags in inventory order, then offhand. A duplicate BagId across these owned locations fails closed. Spectator/dead players cannot open it; the intent resolves the player's own real server stack and cannot nominate someone else's inventory. The existing menu session/revision, capacity, component and transfer checks remain.

An equipped bag's contents can be opened and edited in place. Removing/moving that host invalidates the old view. One bag supplies elemental effects; duplicate identities cannot multiply bonuses. To install/remove upgrade items, move the equipped bag into main inventory first. That operation deliberately retains the already-tested main-inventory transaction rather than inventing an unsafe equipment transfer. This limitation does not prevent normal storage, appearance choices or use of installed upgrades while equipped.

## True-capacity storage layout

The center shows real physical slots, including empty slots, rather than an arbitrary 45-cell list of occupied stacks. A 27-slot starter displays exactly 27 storage cells. Larger bags use a scrollable viewport up to five rows; wheel, scrollbar drag and vertical Up/Down controls select rows. The occupied/total counter describes saved capacity. At the end, partial rows have no extra inactive cells. Searching/favorites shows only matching occupied entries and identifies the filtered result count; clearing the filter restores physical slot order.

The left rail contains the bag preview, wardrobe and client presentation toggles. The right rail contains controls, outside the grid. Management screens are still icon menus, not extra storage capacity. Click an inventory item below to deposit; click a stored stack to withdraw. Right-click moves one; middle-click toggles a favorite. This is still the intentional server-authoritative click-transfer interface, not vanilla cursor dragging.

The visible display index maps to a server snapshot and real container/slot index. Clients cannot choose arbitrary physical inventories. Scroll and content changes advance the existing revision, so stale clicks are rejected and refreshed. Entire-panel scaling fits small GUI dimensions without changing Minecraft's global GUI scale. Mouse, scrollbar and storage hitboxes share the same transform. Text-input focus suppresses inventory shortcuts while typing.

## Art and rendering

The original backpack faces are 32x32, not 4K. The 22 designs and block geometry remain. This revision reduces noisy grain and repeated stitching rather than destructively shrinking images. The leaf has a pointed diagonal silhouette, stem and contrasting vein. Existing named/cat/galaxy/elemental designs, unlocks and explicitly chosen interiors are retained.

The storage grid uses a quieter themed panel with high-contrast slot backgrounds and themed control borders. When a bag has no explicit interior override, leaf variants choose botanical, water/diamond choose ocean, stone/futuristic choose circuit, magma/spooky choose midnight and galaxy chooses galaxy. Shift-click the wardrobe interior option to restore automatic theming. Explicit interior choices are never overwritten. Uploaded images remain permission-gated and are shown in the cosmetic preview area, not behind item counts where they would impede readability.

The renderer reuses a bounded cache of at most 22 cosmetic preview ItemStacks rather than constructing one per frame. Source pixel counts are only a decoded-image lower bound, not GPU atlas/VRAM usage. A software-GL client probe captures the actual production screen for all configured vanilla capacities plus 144/216 slots, first/last rows and GUI scales 1..4. Its JVM heap sample and capture duration are environment-specific, not hardware FPS claims.

## Verification boundaries

The workflow runs the original safety tests, new scroll/last-slot intent tests for seven capacities, host removal and stale revision tests, pure layout/hitbox assertions, an actual optional Accessories beta.53 server test, the existing two-process guide/shared-base restart check, and real client rendering both without and with Accessories. Consult the exact run and artifact logs for PASS/FAIL, not this list alone.

Accessories tests use isolated server FakePlayers and the real dependency, not the user's full pack. The screen captures are explicitly labelled rendering fixtures; server tests separately execute intent packets. The optional client run also checks that native renderer registration suppresses the duplicate generic item layer. Two real simultaneous clients, full-pack input conflicts, external cosmetic-render overrides, Figura/Origins placement, Carry On, live OPAC rules, death/disconnect through Accessories and human interaction in the test profile still need acceptance testing. No new pocket dimension or live portal is introduced here.
