# Storage safety design and boundaries

## Transfer design

The logical server owns inventories, settings and menu actions. The client sends only a menu-session identity, monotonically increasing text-input sequence, ordinary menu clicks, or a bounded shortcut action. Client-supplied counts and destination stacks are not accepted. Display slots are read-only virtual icons; shift-click, drag, number-key swaps and pick-all cannot extract them.

Each transfer snapshots all participating slots and preserves item identity, components, damage and count. The plan inserts only accepted capacity, and subtracts the same count from the source. Before writing, it checks every snapshot, permission guard and inventory size. A component-aware multiset comparison enforces item conservation. Plans are one-shot and reject reentrant commits. Setter failures attempt to restore all original inventories. A restoration failure locks future transfers for the session and logs the problem.

This mod only mutates its own stable inventories and explicitly supported vanilla containers. It does not attempt to treat all modded item handlers as transactional. Unresolved loot tables, arbitrary machines, player-specific inventories and Ender Chests are excluded. No discovery path intentionally loads a missing chunk.

## Settings and progression

Operator drafts are deep copies. Apply requires operator permission, the server thread, a matching revision, valid bounds and progression, actual newly enabled materials, and a check against known backpack inventories. Occupied out-of-range item or upgrade slots prevent a capacity reduction. Removal of an in-use tier is refused. Physical inventories retain their allocated capacity. Disabled crafting does not erase existing storage.

The configuration is written to a same-directory temporary file, flushed, backed up, and atomically renamed before becoming live. A failed write leaves the live rules unchanged. This protects the configuration file; it does not turn Minecraft's entire world save into one transaction.

## Claims and ownership

A remote button stores dimension, position and hub identity; moving to another dimension or replacing a hub invalidates that access. Range, ownership, trust, locks and protection are checked again at use/commit time. Trust in a hub does not imply trust in a protected claim. OPAC integration is optional and fail-closed when its detected API cannot be used. Several recognized unsupported claims mods are also fail-closed. An unrecognized protection system requires an explicit integration review.

## Known beta limitations

- There is no write-ahead recovery journal spanning player data, SavedData and chunk files. A hard crash or power failure at a bad save boundary can still expose Minecraft's multi-file consistency risks. Do not call the mod crash-atomic or duplication-proof.
- Backpack identities are world-owned. Moving an item between worlds without its SavedData does not move the stored inventory.
- Creative copies intentionally alias one inventory. This prevents a simple content-cloning path, but creative operators can otherwise create items by design.
- Automated feeding/refill/compacting have a limited explicit implementation, not feature parity with every Sophisticated Backpacks upgrade. Full-pack event-hook behavior needs testing.
- The current claim adapter and the client renderer require testing against the exact installed versions and client configuration. A headless GameTest run is not that certification.
- Recipe conflict detection checks the shown crafting layout and reports conflicts. It cannot certify every third-party recipe implementation or override their selection order.
- There is no migration/import from the old storage mods. Never remove those mods from an occupied world as an automatic installation step.
- Structural configuration bounds and discovery budgets are hard safety limits even when the menu allows customization.

## Recovery procedure

Stop the affected server before restoring a backup. Restore a coherent whole-world backup, including region files, player data and the overworld data directory, rather than restoring one backpack or player file in isolation. Keep logs and the exact JAR/configuration when investigating a mismatch. Do not retry transfers repeatedly after a rollback failure; resolve the cause and verify a copy of the world first.
