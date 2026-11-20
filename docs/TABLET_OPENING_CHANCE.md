# Tablet opening chance

In `tabletintro.6`, the server makes one roll when a player opens an Anya backpack, network, chest or pocket storage menu. The default is **24%** for Cupid's opening animation and **76%** for direct inventory access. On a miss the first ready storage frame accepts input. On a hit the existing 1.05-second expression plays. Holding the Remote quietly still uses the separate idle-visit timer.

## Change it for the server

As an operator, open `/anya storage` and select **Elemental, cosmetics, image and pocket policy**. Enter a whole-number percentage in the input box, then Shift-click **Tablet opening appearance chance**. Choose **Back**, **Review changes**, then **Apply server settings**. Changing just this value takes effect for subsequent openings without restarting or reloading recipes. A menu already open keeps its original roll.

| Value | Effect on eligible openings |
| --- | --- |
| `0` | Always go directly to storage. |
| `24` | Roll a 24% chance on each opening. |
| `100` | Always select the opening animation. |

Pack configuration uses the existing `config/anya-storage-plus.json` file. The integer field is `tabletOpeningChancePercent` inside its `expansion` object. Existing files without that field inherit 24. For an offline file edit, change this field inside the existing object, preserve the other settings and start the server with the edited file.

Use matching `tabletintro.6` builds on server and clients for the server-selected opening behavior. The percentage works with default client launch arguments. An explicit `-Danyastorage.tabletIntro=false` still lets a client suppress the visual. The existing experimental opt-in for the held tablet and its quiet visits is separate.

## Unyielding / Configtopia integration

Anya owns the saved value. A Configtopia percentage control can use these public methods in `dev.anya.storage.Config` on the server.

1. When displaying the control, read `Config.tabletOpeningChance()`. Show its `percent()` and retain its `revision()` with that operator's draft.
2. On the server thread, call `Config.setTabletOpeningChancePercent(actor, displayedRevision, requestedPercent)` for the acting operator. Anya checks the operator, allowed range and revision and handles the save.
3. Read a fresh snapshot after submission. If the save was rejected, let the operator review the current value before trying again.

This is the Anya side of the integration. Configtopia uses a separate adapter for each external setting. Its editor can present this as a whole-server integer with a range of 0–100 and a default of 24.
