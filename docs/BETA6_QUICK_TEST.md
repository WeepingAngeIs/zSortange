# Beta.6 quick test checklist

No setup change is required by this checklist. No test profile or world is launched automatically. The read-only profile observation supplied by Anya reported no saved expansion settings, so pocket/build enablement is **not assumed**. These checks are for a separately chosen disposable/backed-up test world, never valuable storage.

## Ordinary backpack checks (no pocket switches needed)

1. Open **Ancient Technology**. If a copy was lost, carry one ordinary Book and use `/anya guide recover`. Automatic grants and recovery can be disabled by server policy.
2. Open a starter backpack by using it. Verify **27 real storage slots**, put in a few inexpensive named items, close/reopen, and confirm their names/counts. Keep the existing crafting leaf binding; check that its chosen appearance remains after an ordinary tier upgrade. The default tier/upgrade-slot progression is Starter 27/1, Iron 45/2, Gold 63/3, Diamond 81/4, Netherite 108/5. Server settings may override these.
3. In Controls, find the single existing **Open first backpack** binding. A fresh assignment suggests B; an existing assignment may still be unbound. Pick a free key if there is a conflict. No other bindings should change. Selection prioritizes the functional Accessories back slot, then main hand, wardrobe-selected inventory bag, remaining inventory, then offhand.
4. Where the installed Accessories version supports the tested native back slot, equip the starter there and open it. Remove it and verify the old view closes. Installing/removing upgrade items still requires moving the backpack into main inventory. Do not confuse Sophisticated Backpacks' Turtle Backpack with Anya's item.
5. Check scroll/slot readability at your usual GUI scale. Wardrobe is on the left, storage in the center, actions on the right. Click inventory to deposit and stored items to withdraw; right-click moves one. This is not vanilla cursor dragging.

Basic backpack storage, elemental effects and the wardrobe are not gated by the pocket prototype. Custom image uploads/sharing and monster backpacks default off and are not prerequisites. `/anya storage` is the operator settings menu; use ordinary backpack/remote controls for normal access.

## Pocket/build checks are a separate optional session

Do **not** enable these just to run the ordinary checklist. Pockets require both `-Danyastorage.pocketPrototype=true` on the disposable server and the `pocketStorage` operator switch. Editing additionally requires `pocketEditing`. No saved expansion settings is not evidence that these are enabled.

Once separately enabled for a disposable test: the Home Void Cube is the entrance, not the existing 216-slot UwU inventory cube. Link it with your hub-bound remote. Confirm separate Visit/storage rights, spherical home range, exit/recovery, and permissions on a second player. Construction uses `/anya pocket build place` and `remove`, not unrestricted block placement; Build is separate from Visit and storage rights. Only the supported inert palette and optional empty Anya chest pads are approved. Preserve the protected exit path. Leave damage suppression on.

Live see-through portals are **not implemented**. The animated entrance is a non-live preview and does not load the destination. Exact renderer/shader, grave and other inventory-moving compatibility remain pack-specific checks. A full backup is required for rollback; do not downgrade a world containing newer pocket/build data.
