# Anya Storage Plus field kit

Experimental candidate `0.3.0-beta.7-creativetablet.1` for Minecraft 1.21.1, NeoForge 21.1.249 and Java 21. The Room upgrades link opens the one-chest planner during an authorized visit. Use matching builds for the disposable client and server. The mod ID is `anyastorage`.

## Use a backpack on the ground

Crouch and right-click solid ground with your backpack. Right-click the bag with a completed skin patch, elemental binding or upgrade disc to apply it. To increase capacity, hold the full material cost for its next tier and right-click once. An empty hand opens the bag. Crouch and right-click it empty-handed to pick it up. Crouch-use the carried bag in the air for upgrade settings.

Names follow the visible design and storage tier. For example, an oak leaf design at the Gold tier reads **Leaf Backpack · Gold**. Custom anvil names keep priority. In any Anya storage inventory, click **Tooltips: On** in the upper right to hide hover tips. Click **Tooltips: Off** to restore them. The choice is saved for your player in that world.

## Place a Keepsake Globe

Craft a **Keepsake Globe** with three glass blocks and one plank of any type. Put glass at the top center, middle left and middle right of a crafting table, with the plank at the bottom center. The globe and **Delivery Hatch** also appear in the Anya Storage creative tab.

Place a globe like a normal block. Right-click it with an item to put one inside. The item turns behind the glass and can be seen from any side. Right-click with an empty hand to take it out. Breaking the globe drops the displayed item with its original components. Anyone who can interact or break blocks there can use it, subject to the world's claim rules.

Existing shelves become globes at the same position. If an old shelf contained a hidden keepsake, its original owner must pick that item up once. The globe then works like a newly crafted one. Inside a pocket, use the room editor to place or remove an empty globe. Room permissions apply there, and the displayed model appears when a permitted visitor aims at it.

## Send a parcel

Look at your hatch and use `/anya furniture invite PlayerName` to invite an online friend. At the hatch, they can use their held stack or type `/anya furniture deliver Berries for your next trip`. The hatch keeps up to nine separate stacks with their notes. Senders have deposit access only. The owner can sneak and use an empty hand to collect one whole parcel and read the note. A full inventory leaves the parcel in place. Use `/anya furniture close` to clear the invitations.

Claims still apply. Inside a pocket, the visitor needs View to see a keepsake, Deposit to place one and View and Withdraw to take one. Delivery Hatches also require their sender invitation or owner rights. Empty the hatch before its owner removes it.

## Enable pockets in a test world

As an operator, run `/anya pocket enable`, then `/anya pocket status`. The setting is saved and takes effect once the command succeeds. Use `/anya pocket disable` to switch off storage and room editing. If a room is being prepared, wait for it to finish and retry the disable.

To test entry, crouch-right-click the hub with your tablet, then crouch-right-click the Home Void Cube with the same item. Stand up and right-click the cube to visit. To enable editing separately, use `/anya storage settings set expansion.pocketEditing true`.

## Plan a corner of the pocket

Visit with room editing enabled and Build permission. Hold a plain supported block or furnishing and aim at the face where you want to put it.

| Command | Effect |
| --- | --- |
| `/anya pocket build plan add` | Mark the spot with a private, walk-through outline. Marking the same spot again replaces its outline. |
| `/anya pocket build plan undo` | Remove the most recent mark. |
| `/anya pocket build plan cancel` | Clear all your marks. |
| `/anya pocket build plan apply` | Build the group using one plain item per mark from your inventory. |

A plan holds eight spots, with room for one optional Labelled Storage Chest per plan. Walk around to judge the arrangement, then stand where all the marked faces are visible and within reach to apply. A missing item, changed permission, blocked sight line, occupied spot or protected area prevents placement. Plans expire after five minutes from the last mark or when the visit ends. The room's exit, original chests and chest approaches remain protected. Storage furniture and optional chests also need View, Deposit and Withdraw.

During a visit, open chat and click **Room upgrades** in the welcome message. You can bring the link back by right-clicking the floor while holding a plain Labelled Storage Chest. The planner shows the four pads and the cost. To build, aim at a vacant pad and use **Mark pad** to add an outline for free. **Apply plan** spends one chest and adds 54 physical slots. Press Enter to confirm the suggested action. The server rechecks reach, permissions and the chest limit at placement. The framed preview draws the added chest from the room's verified layout when the room is loaded and the viewer has permission.

Bound pocket tablets show the real slot total and how many of the four upgrade pads are occupied. Hover over **Upgrades** to see how to find **Room upgrades** during a visit.

## Pack, find and put things away

Open storage and select **Field kit and storage information** in the action column.

- **Trip kits.** Arrange the amounts you want in your hotbar. Type an optional name and Shift-right-click one of three kit slots to save it. Later, left-click that kit to top up the missing amounts from the storage you're using. For example, if your kit calls for 32 Poké Balls and you already carry 12, it can take up to 20 matching balls. Named or otherwise customized supplies match their components. A shortage or full inventory is reported.
- **Keep counts.** Open **Keep counts**, enter a quantity, then click an item in your inventory. Dump and QuickStack leave that many of its item type on you across your inventory and hotbar. Locked slots count toward the minimum. Zero clears the rule.
- **Find a chest.** Search storage, then Shift-right-click a result. If you can currently view that loaded physical container in your dimension, the menu closes and private sparks mark it. For a pocket in another dimension, visit with permission to find the physical chest.
- **Undo a dump.** Within one minute, Shift-click **Dump** or choose **Undo last dump** in Field kit. For a nearby dump made with a key or command, use `/anya dump undo`. Undo needs the same storage and current View and Withdraw permission. Anya can trace deposits into its own backpacks and storage blocks. External containers can receive a dump but are excluded from undo. Changed stacks and space in your inventory can reduce how many items return.
- **Partial deposits.** After a manual or bulk deposit in the menu, check the report and the briefly highlighted inventory slots to see what stayed with you.

## Cupid Tablet and Creative Tablet

Craft a **Cupid Tablet** when terminal recipes are enabled. Sneak-right-click a hub with the tablet to bind it, then use it in air within the hub radius to open connected storage. For a pocket, sneak-use the bound tablet on its Home Void Cube. Physical chests and backpacks open directly. A heart Remote already in a chest becomes a regular Cupid Tablet when you carry or use it. Its hub, pocket link and custom name carry over.

The **Creative Tablet** is in the Anya Storage Creative tab. Bind it at the hub the same way. A player in Creative mode can use it at a distance, including across dimensions, to open the one bound network or pocket. The server checks the acting player and destination permissions each time. A Creative Tablet given to a Survival player cannot open storage.

An unselected tablet sometimes shows a close-up of Cupid squinting. Selecting that tablet finishes her blink and sideways glance, then both its hotbar icon and its held screen go dark.

The server rolls the 24% default portrait chance for an authorized Cupid Tablet open. On a hit, Twin Tails Cupid blinks twice, then the storage controls appear on the frame the animation ends. A miss opens storage as soon as its contents are ready. Direct Anya chests and backpacks open normally. Their independent cameo toggles default off. An enabled chest cameo has its own 1% default chance, and either cameo leaves the inventory usable while it shows.

Operators can adjust the Cupid settings under `/anya storage` → **Elemental, cosmetics, image and pocket policy**, review the changes and apply. The detailed first-person render and opening presentation are enabled by default. A client can use the ordinary item render and open the inventory immediately with `-Danyastorage.tabletIntro=false`. [Settings and existing configs](TABLET_OPENING_CHANCE.md)
