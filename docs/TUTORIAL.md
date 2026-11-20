# Ancient Technology

The tutorial item is named exactly **Ancient Technology** (`anyastorage:ancient_technology`). Right-click it to open an illustrated two-page codex. The artwork uses the same actual backpack models as the game, with a parchment/plum book layout, chapter index, readable text, scrolling and ingredient tooltips. Page Up/Page Down turn pages; Home opens Contents; arrow keys or the mouse wheel scroll.

## Live, truthful guidance

The server builds a bounded snapshot when the book opens. It includes leather and all eight leaf starters, each elemental path, capacity tiers, optional-material availability, the nine cosmetic patches, installed upgrades, hub/remote/chest controls, QuickStack, interior privacy and recovery. Ordinary recipe grids come from the current recipe manager. Dynamic tier costs/predecessors and ability values come from current server settings. Disabled or absent recipes are labeled unavailable. Reopen the book after an operator changes settings.

Home-hub permissions and activity controls are explained in two additional live guide pages. Pocket storage and live portals are explicitly labeled unimplemented. The guide is not a teaser claiming that later milestones already exist. Client render captures use a clearly labeled two-page development fixture; production pages are populated from the real server snapshot. The GameTests separately check live recipes/settings.

## One-time automatic grant

`tutorialGrant` defaults to true in the expansion settings. New players receive one book on their first login. Existing players receive it on their first login after installing this milestone, unless they already have a recorded grant or an existing copy. A full inventory defers delivery; the book is not thrown onto the ground. Space is checked again periodically while online.

Two additive records protect the grant: `GuideGranted` in the existing per-player `anya_storage_profiles` SavedData, and a durable UUID-named marker under the world-local `anya_storage/guide_grants` directory. The marker is created with exclusive creation and flushed before the item is handed out. It is never reset by death, respawn or logout. Existing protected-slot masks, favorites, worn-bag references and dragon reward records are preserved.

The policy is deliberately **at most once** for automatic delivery. A process failure between the committed marker and delivery may miss the book instead of creating another one. Explicit recovery covers that case. Filesystem corruption, deleted journals, partial backups and disk failures are not a universal durability guarantee. Back up the whole world, including `anya_storage`, together with normal save data. Do not delete the marker directory to replace a lost book.

## Recovery

`/anya guide` opens a copy already in the player's inventory. `/anya guide recover` consumes exactly one ordinary Book. Named or enchanted books are not consumed. Existing copies are opened instead of duplicating them, and recovery refuses an unsafe/full destination. The command requires other container menus to be closed.

There is also a shapeless **Book + Paper -> Ancient Technology** recipe. Intentional crafting/recovery is separate from the one-time automatic grant; it is not a free regrant loop. Operators independently control `tutorialRecovery`, which gates the command and recipe. `/give @s anyastorage:ancient_technology` remains available to operators for testing.

Both settings are in `/anya storage` -> expansion policy. Changes are drafts until reviewed/applied. Grant/recovery changes do not erase existing copies or player records.

## Verification boundaries

Six guide GameTests cover one-time delivery, loss/reload rejection, full inventory, existing copies, preserved player fields, real recipe/config content and named-book recovery protection. Standalone tests exercise concurrent/exclusive marker creation and partial claims. A separate opt-in CI probe starts a real dedicated server twice against one disposable world and checks SavedData plus the journal on the second process, including a stale-profile simulation.

The restart probe uses a FakePlayer identity to call the production grant service. It is not two real clients logging in. Full-pack network login, respawn and forced disconnect testing remain explicit acceptance tasks. No user's world is loaded by these tests.
