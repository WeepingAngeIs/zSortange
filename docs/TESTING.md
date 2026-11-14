# Verification and beta acceptance

The build workflow is the source of truth for compilation and automated test results. The configuration test writes its actual assertion count to `build/test-results/core-tests.txt`; do not infer a pass merely from this checklist. The headless NeoForge test server exits unsuccessfully when a required GameTest fails.

## Automated tests authored

Configuration defaults, JSON deep-copy/round-trip behavior, numeric bounds, map-key validation, tier IDs/names, optional-root rejection, predecessor cycles, nondecreasing progression, configured limits, presets, atomic replacement, previous-file backup, temporary-file cleanup, and failed-write preservation.

GameTests cover partial destination capacity and replay rejection; component-preserving transfers without merging unlike stacks; stale snapshots; permission-guard changes; setter-failure rollback; conservation checks; backpack identity aliasing and SavedData round-trip; nested backpack/payload rejection; backpack upgrade recipe identity preservation; absent optional materials; default core recipe and disabled endgame route; occupied tier reduction rejection; and physical container metadata/inventory persistence.

## Manual acceptance still required

Use a disposable copy of the server world with at least two clients and the exact modpack:

1. Client joins, opens every screen and searches without missing textures or rendering exceptions. Check GUI scales and keybinding conflicts.
2. Open/close the chest from all four orientations. Confirm its symbol halves remain attached to the opening lid and base. Verify the cube face/heart and button item.
3. Create two bases with different owners; test owner, trusted player, stranger, operator and spectator. Repeat inside OPAC claims, at borders, with allies/parties and with vanilla named-key locks. An unsupported protection result must not grant access.
4. Fill destinations completely and partly, including named, enchanted, damaged and component-bearing items. Repeatedly click, shift-click, reconnect, open from two clients, remove a destination, unload a chunk and move outside range. Compare inventories before and after.
5. Confirm protected slots, armor and offhand are not QuickStack sources. Verify category dump, routing rules and Organize previews, including expiration and simultaneous edits.
6. Test every backpack upgrade separately, then together, including personal/server/per-bag switches and filters. Verify that modded food/event hooks cannot duplicate remainders or consume an item twice.
7. Change range, disable crafting, reduce capacity with occupied high slots, remove an in-use tier and submit two operator drafts. None may silently delete contents or overwrite a newer revision.
8. Test optional materials using actual installed Ice and Fire/Cataclysm versions. Add exact registered items in the editor, configure predecessors, enable gates, craft upgrades and remove optional mods only in a disposable compatibility test.
9. Select each endgame route deliberately; verify material counts and managed End dragon token behavior. Do not mistake Dragon Breath for repeated-dragon-kill enforcement.
10. Perform normal save/shutdown/restart, then controlled crash testing in a disposable world. Compare chunk/player/SavedData contents. Hard-crash consistency is not yet a claimed guarantee.

Keep the existing storage mods until all required acceptance cases pass and players have moved their items intentionally. No migration is performed by this mod.
