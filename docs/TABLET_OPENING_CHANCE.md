# Cupid settings

The server rolls **Tablet opening appearance chance** when a bound Cupid Tablet successfully opens storage the player may view. The default is **24%**. A hit plays Cupid's 1.05-second opening. A miss opens storage as soon as it is ready. An unselected tablet can show a long squint in the hotbar. Selecting it finishes the current expression in up to 15 game ticks. The hotbar and held screen share that expression and then go dark together. The heart Remote opens storage immediately.

Opening a supported Anya Storage Plus chest or backpack directly opens its inventory immediately. The optional cameos are brief portraits beside the inventory and leave the controls usable. Vanilla and other mods' containers use their ordinary behavior.

## Change it for the server

As an operator, open `/anya storage` and select **Elemental, cosmetics, image and pocket policy**. Click a toggle to change it. To set a percentage, type a whole number in the input box and Shift-click the setting. Choose **Back**, **Review changes**, then **Apply server settings**. Cupid-only changes take effect on subsequent openings without a restart or a recipe reload.

| Setting | Config key in `expansion` | Default and effect |
| --- | --- | --- |
| Tablet opening appearance chance | `tabletOpeningChancePercent` | **24%** for an authorized Cupid Tablet open. Set `0` for immediate access or `100` for the intro on every eligible use. |
| Chest Cupid cameo | `chestCameoEnabled` | **Off**. Enable to allow a cameo on a direct Anya chest open. |
| Chest Cupid cameo chance | `chestCameoChancePercent` | **1%** per eligible direct chest open when its toggle is on. Accepts `0`–`100`. |
| Backpack Cupid cameo | `backpackCameoEnabled` | **Off**. Enable for a cameo on a supported backpack inventory open. |

The existing `config/anya-storage-plus.json` still uses schema 2. Your saved tablet percentage applies to authorized Cupid Tablet opens. Older configs inherit both cameo toggles off and a 1% chest chance when the new keys are absent. Loading an older config does not rewrite it. For an offline edit, put the fields in the existing `expansion` object and preserve the other settings.

Use matching builds on server and clients. The Cupid Tablet has its own item model. A client can use the ordinary item render and open the inventory immediately with `-Danyastorage.tabletIntro=false`.

## Unyielding / Configtopia integration

Anya owns the saved value. A Configtopia percentage control can use these public methods in `dev.anya.storage.Config` on the server.

1. When displaying the control, read `Config.tabletOpeningChance()`. Show its `percent()` and retain its `revision()` with that operator's draft.
2. On the server thread, call `Config.setTabletOpeningChancePercent(actor, displayedRevision, requestedPercent)` for the acting operator. Anya checks the operator, allowed range and revision and handles the save.
3. Read a fresh snapshot after submission. If the save was rejected, let the operator review the current value before trying again.

This is the Anya side of the integration. Configtopia uses a separate adapter for each external setting. Its editor can present this as a whole-server integer with a range of 0–100 and a default of 24.
