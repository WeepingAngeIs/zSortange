# 0.3.0-beta.4: gated home-pocket safety prototype

This is an additive milestone on `feature/elemental-pockets`. It is not a new mod, an installation, or a full-pack acceptance certificate. Existing backpack UI, native Accessories integration, BagId/contents/cosmetics, ordinary hub range/progression, guide grant records, and the 216-slot UwU Cube remain distinct and preserved.

## Deliberate release gate

Production access remains **OFF**. Creation, crafting, entry and storage require BOTH `-Danyastorage.pocketPrototype=true` in a disposable server's JVM and `pocketStorage=true` in the operator expansion policy. A normal launch still rejects enabling that switch. This build itself changes no profile launch arguments, installations or world files outside its isolated tests.

The dimension definition is registered with the mod, but no entrance is allocated or destination requested just because a server starts. Recovery/protection handlers remain available if a gate is later disabled. The existing ordinary UwU Cube is still a 216-slot inventory, not this entrance.

## Implemented prototype flow

The **Home Void Cube (Experimental)** recipe consumes four obsidian, two ender pearls, two amethyst shards and one configured `pocketCoreMaterial` (default `minecraft:diamond`), arranged anywhere in a full 3x3 grid. It does not consume an existing hub, backpack or filled container. Missing configured items cannot match. No ore generation is added.

Place it near an existing home hub owned by the same player. Bind a remote to the hub, then sneak-use it on the entrance. The remote receives a pocket binding. Ordinary hub binding clears that pocket binding. Right-click the entrance to queue an authorized visit; using the pocket-bound remote queues a storage-view lease. An unprepared pocket must be visited once before remote access.

Before preparation, look at the entrance and use `/anya pocket theme galaxy`, `beach` or `wasteland`. These are modest block-material palettes, not elaborate landscapes or per-player sky renderers. Scenery becomes fixed once construction starts, so restyling cannot overwrite inventories. The 32x32 plot initially contains four actual 54-slot Anya chests and an exit lodestone. They are real block entities in region files, not a second virtual inventory. **Construction, moving/removing these chests, imported machinery and arbitrary third-party containers are locked in this prototype.** That restriction is intentional pending protection/automation acceptance.

Right-click the exit lodestone or use `/anya pocket exit` to return. An unfinished visitor is returned on login rather than automatically granted another visit. Entry stores a durable return record before teleporting, does not snapshot/copy inventory, and refuses mounted/passenger travel. A blocked return tries a safe overworld spawn landing; if both are entirely unsafe, the record remains and administrator assistance is required. No arbitrary blocks are overwritten to create an emergency platform.

## Identity, preservation and interrupted work

`anya_storage/pockets.v1.dat` is a bounded, checksummed, atomic-replace world ledger with fail-closed validation and stale-writer detection. It contains persistent pocket/owner/cell/home-anchor identities and unfinished return coordinates, **not item stacks**. A cell is never deleted or reused. Existing hub identity is added lazily to an additive `StorageIdentity` NBT field; old inventory/progression/policy fields are not replaced. Protected pocket chests carry an additive `PocketId`. Normal backpack data is not migrated.

Preparation uses RESERVED -> BUILDING -> READY transitions. Chunk data is saved before READY. An interrupted BUILDING state from another process refuses automatic regeneration; preserve that test world for inspection. Generation also refuses occupied shell/chest/exit positions rather than overwriting them.

Destroying or moving an entrance makes its recorded location invalid, closes views and returns visitors. It does not delete or duplicate the region inventories. `/anya pocket` lists the caller's own identities. The recorded owner can look at their replacement entrance, hold a hub-bound remote, and use `/anya pocket reanchor <UUID>` after all users leave. This changes the anchor epoch, not the cell/contents. Old/copied entrances no longer match the record. This is not blanket Carry On support.

## Cooperative access and claims

`Visit=16` is independent of View=1, Deposit=2, Withdraw=4 and Log=8. Legacy trust remains mask 7, so old trust never silently grants Visit. Existing rights and the hub's in-game permission controls remain. Physical pocket chest access uses the same snapshot/intent UI and rights checks as remote access. Visit-only users receive no stored item stacks. Snapshot transactions recheck the live policy revision, anchor identity/epoch, real block entity identity, loaded chunks, permissions and claim checks before committing.

The home hub must be loaded. Home access is still its **spherical** range, not the outline of an OPAC claim. The actual player's claim checks run at the hub, entrance and physical chest positions with the explicit target dimension. Unsupported/mismatched claim APIs fail closed. `StorageAccessEvent` now includes the target `level` while retaining the existing constructor. Cross-dimensional OPAC behavior still requires the target-pack acceptance test; the real OPAC dependency is not part of these isolated prototype tests.

Ordinary network discovery refuses tagged pocket chests, preventing a nearby hub/QuickStack path from bypassing pocket rights. Pocket chests expose no sided inventory automation. Placement, breaking, pistons, explosions, vehicles/projectiles/mob entities and unsupported item use are blocked inside the test room. Normal damage is suppressed there. The death/drop hook still releases the visit and labels existing drops with their original player owner; it does not copy items. Death, grave mods and item-despawn semantics need full-pack testing. This safety prototype is not a promise of survival-death parity.

Successful pocket transfers use the existing home activity log policy, which defaults off, records counts rather than item components, and retains its separate privacy/read permissions. It is not an item-recovery journal.

## Bounded lifetimes and honest chunk accounting

Runtime leases are in-memory only, capped at 16 users and the configured active-pocket limit (1..8), with an 80-tick renewal timeout and `pocketSessionTicks` absolute lifetime (default 6000; bounded 200..12000). One viewer leaving does not release another's lease. Revocation, range loss, logout, menu closure, expiry, anchor loss and shutdown remove Anya-owned requests. Return recovery may request up to two destination chunks and holds its requests for five ticks.

The **build plot** spans four chunks. The structural boundary ring requests **16 destination chunk positions**, plus up to two home positions, per active pocket. Ticket propagation and player simulation/view distance can load additional chunks. These counts are not total loaded chunks, GPU meshes or zero processing. New-room preparation performs at most one synchronous destination-chunk request and 256 shell writes per global tick, not per viewer. The final chest/exit writes and save are a separate bounded completion step. Actual save/initialization latency remains to be profiled in the target pack.

### Entrance window

Look at a side of a nearby Home Void Cube to see an illustrated starter room. The four chests in the illustration follow the standard row in `PocketService.chestPositions`. Every entrance shows the same artwork, including an unlinked cube or a world with pockets disabled. The illustration represents the starter design. Personal decorations, optional chest pads and stored items are outside its scope.

The client draws one textured face over the block selected by the normal crosshair. Looking away exposes the original four-frame 16×16 abstract material. The original particles retain their 16-block distance limit, global limit of four per tick and client preference. The illustrated face uses the public texture packaged with the mod. It creates no destination render, chunk request, image file, permission cache or interaction path. Physical entry continues to use the separately gated Visit action.

**Possible next step for a personalized view.** A small first experiment could use a per-viewer diagram of the eight registered chest positions. The server would verify the entrance and hub identities, range, View permission and claims covering the sampled positions, and read only a fully loaded room. It could send an eight-bit presence mask to the authorized viewer. All clients could draw the same artist-authored shell at the indicated positions. Drawings would omit inventory stacks, names, signs and arbitrary block entities. This would depict the actual registered chest arrangement as a diagram. Capturing the actual room pixels would be a different design.

Before using such a mask with player data, test two independent viewers, revocation while open, disconnect, expiry, changed claims, replacement of the entrance and unloading the room. The personalized view needs an explicit per-viewer clear on loss of authority, a short client expiry for missed updates, and a return to the public material on level/session changes. The current artwork requires none of these private messages. The optional live-view experiment and its evidence are described in [the separate fixture](../fixtures/live-view/README.md).

## What evidence means

Read the exact hosted run's logs before calling this build verified. Pure JVM tests cover ledger persistence, corruption/stale writes, boundaries, independent return records and lease quotas/deadlines. The isolated `-PpocketTest` normal dedicated-server run exercises actual dimension crossings with two ordinary ServerPlayer identities using embedded mock network channels, physical chest conservation, Visit privacy, revocation recovery, remote access, entrance destruction/reanchor and logout/login hooks. These are not two real networked clients. The ordinary-player transition save and bounded player-file reread are exercised, unlike the earlier FakePlayer harness. A separate final assertion waits 600 idle ticks and checks the 16 destination positions without requesting them; consult its `ANYA_POCKET_UNLOAD_OK` marker for the result. The dedicated two-process fixture verifies the actual ledger and four physical chunk inventories plus pending return records; it is not a real player's reconnect/respawn test. Existing beta.3 tests and render fixtures remain.

Remaining acceptance gates: two real simultaneous clients; forced socket loss; actual death/respawn/graves; OPAC cross-dimensional behavior and protected physical interactions; Carry On and other inventory-moving mods; shutdown during generation/transfer; exact renderer stack; full-pack engine chunk retention and hardware/server-load measurements. Minecraft's separate player and chunk saves are not an atomic inventory database. No claim of hard-crash item durability is made.

A saved prototype flag does not prevent server recovery after the JVM test flag is removed: startup validates it as dormant without rewriting the file. Effective access stays off and returns still recover. New settings applications still refuse pocket enablement without the JVM gate.

Normal player transitions invoke only that player's vanilla save, then boundedly re-read the player file and confirm UUID, dimension and position before clearing the return marker. A failed verification retains recovery. This confirms the position record, not cross-file atomic item durability. The normal dedicated lifecycle fixture uses ordinary ServerPlayer objects to exercise that save path. Other older tests use FakePlayer and do not independently prove it. Neither fixture substitutes for real-client reconnect/respawn acceptance.
