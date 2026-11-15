# Shared home-base permissions and activity (0.3.0-beta.2)

This continues the existing elemental/cosmetic/interior/guide milestone. It does not introduce a second mod, a pocket dimension or an installation step. The original beta and player inventory data remain intact.

## Separate hub-interface permissions

An owner or operator opens a hub, Base Profile, then Member Permissions. Select a listed member, or enter an online player name/full UUID and press Select/add. A new member receives **View only**. Toggle View, Deposit, Withdraw and Read Activity Log independently. Changes apply immediately; they are base policy, not server-config drafts. Removing a member removes their explicit override too. Owners/operators retain base management access.

Existing trusted members retain their original View + Deposit + Withdraw access unless the owner edits it. They do not automatically gain activity-log access. Rights are additive fields in the existing hub block entity, not a replacement world/player save format. Inventories, names, routes, ore progress, owner, protected player slots, backpack identifiers and guide grant records are not migrated or reset.

A deposit-only member sees a private-contents notice instead of storage stacks. They can explicitly click their own inventory to deposit. QuickStack and Dump require View + Deposit because matching provides information about contents. Withdrawal requires View + Withdraw; Organize requires View + Deposit + Withdraw. The server checks rights both on the intent and before committing the inventory transaction. Changing policy invalidates previously prepared plans.

These permissions govern this particular hub interface. **They do not protect an ordinary physical base from walking in, opening an unlocked vanilla chest directly or using another mod.** OPAC/locks still govern those routes. Separate pocket Visit permission must not be advertised until a pocket runtime enforces all its entry and container paths. Trust does not bypass OPAC, locks, loaded-chunk checks or world borders.

## Optional, bounded activity log

In `/anya storage` -> expansion policy, review/apply:

| Setting | Default | Meaning |
| --- | --- | --- |
| `activityLogging` | false | Record successful transfers through the home hub interface. |
| `activityActorIds` | false | Include an actor UUID, not a player name. Opt-in only. |
| `activityMemberLogs` | false | Permit members with an explicit Log right to read history. |
| `activityLogEntries` | 64 | Retained entries per base, bounded to 1..128. |

Open Base Profile -> Base activity log. Authorized members reach it through Base information. Entries contain UTC time, operation and accepted item count. Deposit, Withdraw, QuickStack and Dump are recorded only after a successful nonzero transaction. Failed, cancelled and zero-move requests do not create transfer entries. An entry summarizes the operation, not each individual stack. Direct chest access, crafting, sorting, third-party mods and physical world changes are not a complete audit trail.

There are **no item types, item names, item components, backpack contents, private image references/pixels, network addresses or arbitrary messages** in these records. Actor UUIDs are private player identifiers; leave collection off unless the server deliberately needs it. Public block-update packets contain neither history nor member permissions.

Owners/operators can clear the current base history with a two-click confirmation. Disabling logging stops collection and hides history, but does not automatically erase it. Turning actor collection off immediately redacts the viewer and strips IDs from loaded histories on the next maintenance/save; unloaded chunk files are scrubbed when loaded/saved. Older server backups can still retain prior records. Reducing the limit drops the oldest entries. Log fields are validated on load and malformed entries are ignored without touching stored items.

The activity log is saved with the hub. It is a convenience record, **not** a durable inventory journal or a crash-recovery guarantee. Do not replay a log entry to recreate items. The separate player/chunk save consistency limit still applies.

## Pocket storage remains gated

No dimension, teleport, remote pocket inventory, scenery renderer, chunk ticket or Immersive Portals adapter is created by this change. The existing `pocket*` options remain reserved and the `pocketStorage` gate still rejects enablement. The new `activity*` settings apply to ordinary home hubs only.

The next pocket implementation still needs an enforced 32x32 build boundary, Visit separated from View/Deposit/Withdraw, physical-container protection against every access route, expiring per-viewer leases, bounded active destinations and cleanup after the last visitor/viewer. It must keep a cheap animated preview fallback and must not imply four build chunks means zero processing or only four loaded/rendered chunks. The existing renderer audit and unresolved exact-pack compatibility remain applicable.

## Validation boundaries

The build adds exhaustive bit-mask assertions and twelve Minecraft GameTests for legacy trust, independent rights, save compatibility, malformed/duplicate rights, revocation during a real transaction, deposit-only transfers, read-only organization rejection, independent members, log budgets/privacy, disabled logging and public-packet privacy. The two-process dedicated-server probe now checks a real placed hub's permissions, existing inventory/progression and opt-in activity across save/stop/restart, in addition to the tutorial journal/profile checks.

Consult the exact final workflow result; test source alone is not a passing result. These use isolated FakePlayer identities, not two real connected clients. Full-pack OPAC, Carry On, live menus, disconnects and simultaneous-player acceptance remain outstanding. No installed profile JAR is changed by building this artifact.
