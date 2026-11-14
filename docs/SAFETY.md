# Inventory and permission safety model

## What the implementation does

Storage stays in the physical block entity or in the backpack item's data components. The network index caches block positions, not a duplicate item database. A screen sends an intent (menu ID, session nonce, revision, action and slot), never a trusted quantity or replacement stack. Server menus reject vanilla display-slot moves and validate the acting player, menu identity and view revision. Operations are serialized on the server thread.

A transaction copies source/destination slot state, simulates acceptance, rechecks every snapshot and permission, then applies changed slots. Exact item/component totals must match before and after. Legitimate crafting, smelting, installed-upgrade changes and ore consumption declare their exact validated inputs and outputs. Destinations are checked for slot acceptance and stack limits. A stale or already-used plan is rejected. If a supported inventory throws or fails read-back verification, rollback is attempted. A container that fails rollback is quarantined from later transactions until restart/manual recovery. The error is not silently retried.

Backpack transfers exclude the outer backpack's host inventory slot from the player-inventory snapshot. This prevents its changing container component from being counted both as an outer stack and as the inner inventory. Replaced backpack identities and externally changed container components invalidate the open view. Oversized/corrupt backpack data is refused rather than silently truncated. Tier upgrades retain existing allocated capacity, components and contents.

No remote operation force-loads chunks. The acting player's current access is checked for each container and again before commit, including world border, spawn protection, custom ownership/trust, vanilla container locks and both halves of double chests. Unopened loot containers and unsupported/player-specific inventories are excluded. The Open Parties and Claims adapter calls its public server API using the acting player, not the hub owner's identity; API failures deny access. A cancellable StorageAccessEvent lets another protection addon deny access. Known unsupported protection mods fail closed. This does not establish compatibility with every unknown third-party protection system.

Configuration edits are private drafts. Operator permission and revision are rechecked at Apply. Files are written to a temporary file, forced to disk and atomically renamed, with the previous file copied to a backup. A recipe reload is initiated and failure attempts to restore/reload the prior settings. Recipe/tier disabling never unregisters existing items or deletes their contents.

## Limits that must not be disguised as guarantees

These are server-thread snapshot transactions, NOT distributed crash-consistent storage across vanilla save files. Player data, chunk data and SavedData can be saved at different times. A hard process kill, disk failure, selectively restored backup, arbitrary hostile inventory mod, or rollback failure can still leave inconsistent state. A normal serialization test and orderly server shutdown do not prove safety under those conditions. Do not market this beta as duplication-proof.

Do not restore only player data or only storage chunks after a problem; restore a consistent world backup including player data, dimensions and SavedData. Inventory rollback errors are a stop-and-investigate condition. Inspect server logs and preserve the affected world before taking further actions.

The mod intentionally avoids arbitrary IItemHandler inventories, unknown loot wrappers and unowned custom-container automation in this beta. Vanilla chests/barrels can still participate in normal vanilla automation; interaction with other transfer mods needs multiplayer testing. Carry On and other mods that move block entities are outside the implementation's guarantees.

## Verification distinction

Automated GameTests exercise simulated concurrent edits, full destinations, exact components, rejected permission predicates, duplicate-plan replay, injected write failures, serialization, ownership/trust and selected world access cases. They do not substitute for two real clients, the user's complete mod list, live OPAC claims, disconnect/reconnect testing, client rendering review, or controlled crash testing on a disposable copy. See TESTING.md for the remaining acceptance checks.
