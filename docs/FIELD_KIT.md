# Anya Storage Plus field kit

Experimental candidate `0.3.0-beta.7-fieldkit.3` for Minecraft 1.21.1, NeoForge 21.1.249 and Java 21. The Remote-only Cupid opening includes the Keepsake Shelf geometry from fieldkit.2. Use matching builds for the disposable client and server. The mod ID is `anyastorage`.

## Place a keepsake shelf

Both **Keepsake Shelf** and **Delivery Hatch** are craftable and appear in the Anya Storage creative tab. Place either in an ordinary build where you have permission. Inside an enabled pocket, use the room editor to put furniture in the editable part of the room. Pocket furniture belongs to the pocket owner, including when a builder places it on the owner's behalf.

Use the shelf while holding an item. It takes one item from the stack. In first person with your HUD visible, aim at the shelf within five blocks to see the item and label. Use an empty hand to read a long caption in chat. While looking at it, type `/anya furniture caption From our first cave` to write a caption. Use `/anya furniture show` to share the display or `/anya furniture hide` for an owner-only display. The shelf starts private. The visual uses the item's base model. The shelf keeps the original item with its components. Sneak and use the shelf with an empty hand to take it back.

## Send a parcel

Look at your hatch and use `/anya furniture invite PlayerName` to invite an online friend. At the hatch, they can use their held stack or type `/anya furniture deliver Berries for your next trip`. The hatch keeps up to nine separate stacks with their notes. Senders have deposit access only. The owner can sneak and use an empty hand to collect one whole parcel and read the note. A full inventory leaves the parcel in place. Use `/anya furniture close` to clear the invitations.

Claims still apply. In a pocket, viewing a shared display needs Visit and View, sending needs Visit and Deposit, and collecting needs the owner's View and Withdraw rights. Empty furniture can be removed by its owner.

## Plan a corner of the pocket

Visit with room editing enabled and Build permission. Hold a plain supported block or furnishing and aim at the face where you want to put it.

| Command | Effect |
| --- | --- |
| `/anya pocket build plan add` | Mark the spot with a private, walk-through outline. Marking the same spot again replaces its outline. |
| `/anya pocket build plan undo` | Remove the most recent mark. |
| `/anya pocket build plan cancel` | Clear all your marks. |
| `/anya pocket build plan apply` | Build the group using one plain item per mark from your inventory. |

A plan holds eight spots, with room for one optional Labelled Storage Chest per plan. Walk around to judge the arrangement, then stand where all the marked faces are visible and within reach to apply. A missing item, changed permission, blocked sight line, occupied spot or protected area prevents placement. Plans expire after five minutes from the last mark or when the visit ends. The room's exit, original chests and chest approaches remain protected. Storage furniture and optional chests also need View, Deposit and Withdraw.

During a visit, open `/anya pocket build plan` to see which of the four extra chest pads are vacant, occupied or unavailable. Use `/anya pocket build pads` for markers in the room. Marking a vacant pad draws an outline for free. Apply spends one plain Labelled Storage Chest and adds 54 physical slots. The server rechecks reach, permissions and the chest limit at placement. The framed preview draws the added chest from the room's verified layout when the room is loaded and the viewer has permission.

## Pack, find and put things away

Open storage and select **Field kit and storage information** in the action column.

- **Trip kits.** Arrange the amounts you want in your hotbar. Type an optional name and Shift-right-click one of three kit slots to save it. Later, left-click that kit to top up the missing amounts from the storage you're using. For example, if your kit calls for 32 Poké Balls and you already carry 12, it can take up to 20 matching balls. Named or otherwise customized supplies match their components. A shortage or full inventory is reported.
- **Keep counts.** Open **Keep counts**, enter a quantity, then click an item in your inventory. Dump and QuickStack leave that many of its item type on you across your inventory and hotbar. Locked slots count toward the minimum. Zero clears the rule.
- **Find a chest.** Search storage, then Shift-right-click a result. If you can currently view that loaded physical container in your dimension, the menu closes and private sparks mark it. For a pocket in another dimension, visit with permission to find the physical chest.
- **Undo a dump.** Within one minute, Shift-click **Dump** or choose **Undo last dump** in Field kit. For a nearby dump made with a key or command, use `/anya dump undo`. Undo needs the same storage and current View and Withdraw permission. Anya can trace deposits into its own backpacks and storage blocks. External containers can receive a dump but are excluded from undo. Changed stacks and space in your inventory can reduce how many items return.
- **Partial deposits.** After a manual or bulk deposit in the menu, check the report and the briefly highlighted inventory slots to see what stayed with you.

## Cupid on the blue tablet

Hold the Remote in first person to see the tilted silver tablet. Its glass rests dark. During a quiet visit, Cupid can glance to the left for half a second before the screen goes dark again. The longer idles still include her full-body poses, wave and rare boots-first tumble.

The server rolls the 24% default portrait chance only for an authorized Remote open. On a hit, Twin Tails Cupid blinks twice, then the storage controls appear on the frame the animation ends. A miss opens storage as soon as its contents are ready. Direct Anya chests and backpacks open normally. Their independent cameo toggles default off. An enabled chest cameo has its own 1% default chance, and either cameo leaves the inventory usable while it shows.

Operators can adjust the Cupid settings under `/anya storage` → **Elemental, cosmetics, image and pocket policy**, review the changes and apply. The first-person model and its opening presentation are enabled by default. A client can disable both with `-Danyastorage.tabletIntro=false`. [Settings and existing configs](TABLET_OPENING_CHANCE.md)
