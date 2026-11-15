# Experimental room editing: narrow supported subset (beta.6)

This extends beta.5 on the existing `feature/elemental-pockets` branch. No backpack model, texture or screen redesign is part of this milestone. The existing pocket ledger format, player records, hub identities, backpack identities and all original chest positions are retained. `pocketEditing` defaults to false.

## Gates and commands

The existing `-Danyastorage.pocketPrototype=true` JVM gate and `pocketStorage` operator switch remain required. Room editing additionally requires `pocketEditing`. The expansion settings menu discovers this new boolean like the other server options; changes use the existing draft/review/apply workflow.

Owners may edit. A delegated member needs both **Visit** and the new independent **Build** bit in Hub -> Base Profile -> Member Permissions. Legacy masks remain unchanged; old storage trust never silently gains Visit or Build. Build does not imply View, Deposit or Withdraw. Adventure/dead/spectator players cannot edit. The acting identity, loaded home hub, entrance identity, OPAC access and target construction permission are checked, not borrowed from the owner.

- `/anya pocket build`: scope/help and live editing-gate status.
- `/anya pocket build place`: hold a plain supported block and look at a face within five blocks. The server derives the adjacent destination, not a client-supplied coordinate. One item is consumed, including in creative mode.
- `/anya pocket build remove`: look at a supported decorative block or empty optional chest. Its plain block item returns directly to main inventory. A full inventory refuses removal, without dropping items into the world.

Ordinary placement, mining, piston activity and mover callbacks remain blocked inside the pocket. The explicit commands are intentionally the only construction route in this milestone. No new tool or ore is introduced.

## Decorations and geometry

Supported inert blocks: stone bricks, quartz block, oak planks, cherry planks, black concrete, purple concrete, cyan concrete and sea lantern. Component-bearing block items are refused rather than stripping metadata or importing block-entity inventories. Fluids, gravity blocks, fire sources, containers from other mods, redstone and machinery are not approved.

The 32x32 plot is unchanged. Decoration is limited to relative X/Z 2..29 and world Y 65..72, minus the original chest approach/lid areas, four optional chest approaches and the five-block-wide landing/exit route. The floor, boundary walls, roof, exit lodestone and original four chests remain protected. `/anya pocket exit` and automatic recovery remain available regardless of Build permission or the editing gate.

## Four optional chest pads

Plain **Anya chests** can be added only at relative X 6, 10, 22 and 26, Z 14, Y 65. Each adds 54 physical slots. The original four chests remain required, for at most eight chests / 432 slots in this subset. `pocketContainers` may lower the placement budget. Reducing it never hides or truncates already-present storage. These are optional physical blocks in the existing pocket region files, not another item database.

Chest construction additionally requires View + Deposit + Withdraw. New pad chests inherit the pocket owner's identity and pocket tag; they do not become a builder-owned shortcut around shared permissions. Only empty pad chests can be removed. A filled chest is never converted to a portable block item. Moving the four original chests remains unsupported.

Discovery reads at most eight fixed loaded positions, validates owner/pocket/type/capacity, and rejects duplicated storage identities. Adding, removing or replacing a chest invalidates earlier layout snapshots before inventory transfers commit. Turning editing off does not hide existing pad contents.

## Transaction and protection boundaries

A single represented world block and the player's actual main inventory participate in the existing snapshot/conservation transaction. Validation includes current raycast, loaded target, world/object identity, pocket epoch, hub permission revision, configuration revision and optional-chest registry snapshot. Edits are single-use, server-thread only, reentrancy-guarded and rate-limited to one successful edit per player per five game ticks. The single-block subset never creates thousands of writes from one request.

An in-process write failure attempts the existing snapshot rollback, including empty-chest metadata restoration. A failed rollback quarantines further edits to that pocket for the server session and logs a preservation warning. This is not durable crash recovery. Preserve and inspect a world after such a fault rather than restarting to bypass quarantine.

Vanilla/NeoForge break/placement events, Carry On's event route, explosions and sided/capability automation are separate negative tests. The absence of an item-handler capability and denied sided slots is not a guarantee against an unsupported mod directly rewriting arbitrary block entities. Those mods are not certified or enabled as construction inputs.

**Minecraft's player and region saves remain non-atomic.** A power failure between saving the placed block and consuming/saving its item can still diverge. The prior selected interruption cut points do not solve this; all experimental gates remain. Do not use valuable worlds or downgrade a world containing new rights/optional room storage. Roll back complete backups only.

## Acceptance evidence, not assumptions

The new packaged room workflow uses the existing clean installed-server harness and two genuine Minecraft clients over loopback TCP, with the packaged production JAR hash verified on each side. It selects a different test scenario rather than rerunning the unchanged beta.5 acceptance. The fixture checks delegated commands, denied Build/storage combinations, actual chest deposit, occupied/full-inventory removal, optional-layout stale transfers, real Carry On events, OPAC construction denial, explosions, held components, forged targets, cooldown/replay, socket interruption after editing, boundary escape with an obstructed original return, idle ticket/chunk cleanup and a separate server-process reload.

Some negatives and dependency interactions are invoked server-side inside that real-client environment. They are not all simulated mouse input. The restart inspection has no connected client and loads chunks explicitly as test setup, not production behavior. Consult the exact workflow result and marker logs for what passed; this document is not a substitute for a green run.

The original regression workflow is still relevant because permission masks and physical-container discovery changed. It is not evidence for real-client construction by itself.

## Live rendering remains separate

The pinned Immersive Portals CE source audit was reopened at `ad2e7ee5d73e4ff78bd0befade4f797265e65939`. Its documented renderer contract and source version are not an Anya integration test. In `Portal.java`, making a portal invisible explicitly leaves collision, teleportation and chunk loading active; hiding it is therefore not a lease-cleanup mechanism. `specificPlayerId` supplies a per-player restriction, but its interaction with Anya's distinct View/Visit rights, packet privacy and multiple viewers still needs an adapter and runtime tests.

The available software-OpenGL clients can capture vanilla room rendering. That does not establish an optional CE renderer backend, shader pack or the current user profile's exact versions. No live adapter or CE dependency was installed, bundled or enabled. The cheap non-live cube preview remains unchanged, requests no destination data, and is not advertised as a live room view. Arbitrary editable construction and machinery are still future work.
