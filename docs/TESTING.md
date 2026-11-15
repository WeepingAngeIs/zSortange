# Expansion milestone 1 verification

The current pipeline distinguishes compilation, pure assertions, real server runtime, a two-process persistence probe, client asset rendering and unperformed full-pack acceptance. Consult the final artifact's exact `SOURCE_COMMIT.txt` and workflow result, not an earlier commit's success.

## Automated lanes

`gradle clean build` runs 343 configuration assertions, 540 expansion/PNG-security assertions, 109 durable-guide-journal assertions and 1,154 image-rate/filter assertions. These are assertion counts, not that many independent integration tests. `gradle runGameTestServer` runs 40 required Minecraft/NeoForge GameTests, including the prior 500-transfer randomized conservation case.

Expansion GameTests cover appearance/tier independence, nonmutating style crafts, all-component preservation, one worn backpack, duplicate-ID rejection, modifier removal, appearance serialization and closed upload defaults. Guide GameTests cover one-time grant, full inventory, existing copies, named-book recovery protection, existing profile fields and live recipe/settings pages.

The `guidePersistence=seed` and `guidePersistence=verify` opt-in runs start two separate ordinary dedicated-server processes against one disposable world. They verify production SavedData/journal persistence, no automatic regrant after the original item is removed, preservation of other profile fields and a stale-SavedData recovery check. The caller is a FakePlayer test identity, not a real client connection.

The `clientSmoke` opt-in run starts the actual Minecraft client with software OpenGL. It verifies model registration/texture atlas availability, captures 22 backpack variants, six default interiors and two book page layouts, and exits. Book screenshots use a clearly labeled development fixture. Production guide pages read the server's live snapshot, checked independently in GameTests. Captures are inspected for missing textures, loading overlays, blur, clipping and text readability.

The probes run only under explicit JVM properties in CI. They do not start on normal user launches. No target profile or user world is used.

## Still not verified

No full-pack two-client gameplay session has been run. Test actual login/respawn/disconnect, wardrobe interaction, worn-model fit with player scales/Figura/Origins, client texture reload, Carry On, OPAC, Apotheosis spawning and the target renderer/shader stack. Image authorization, sharing/revocation, moderation, upload/download and disconnect must additionally be exercised end to end with two real clients, malformed senders and permission changes. Existing server-thread conservation tests are not a claim of hard-crash transactions across Minecraft saves.

Pocket dimensions, granular pocket permissions/logs, portal views and their disconnect/unload tests are unimplemented, not failing or silently passed tests. The CE document audit is not runtime compatibility evidence.

## Tutorial acceptance checklist

Use a disposable server copy and two accounts. Confirm exactly one first-login book each, no regrant after death/reconnect/restart, delayed delivery for a full inventory, independent automatic/recovery switches, named-book protection, correct default and overridden recipe pages, and preserved old player fields. Verify controls at common GUI scales and long optional-tier names. A crash between journal commit and delivery may omit the free copy; explicit recovery is the documented safe path.

## Existing storage acceptance remains required

# Verification and beta acceptance

The build workflow compiles the mod, runs configuration assertions, then launches a real Minecraft 1.21.1 / NeoForge 21.1.249 GameTest server. Artifacts are uploaded as playable builds only after these required steps succeed. Consult the exact workflow result and SOURCE_COMMIT.txt in the artifact, not the mere existence of source code.

The original 18 GameTests passed on source commit 040904185639ae61cfbfc82cf7948ac006f9f552. That suite includes 500 deterministic randomized transfers. The subsequent persistence/visual-update/ownership tests added in this revision must be judged by their own completed CI run; this document does not predeclare their outcome.

## Automated cases

StorageGameTests covers partial/full capacity, names/durability and component preservation, non-merging of different components, stale source and destination snapshots, permission revocation, plan replay, unexplained item creation, authorized recipe transformations, injected failure and rollback, restrictive slots, spherical range, backpack tier upgrades retaining contents/UUID, missing optional materials, disabled endgame recipe defaults, blocked unowned automation and randomized conservation.

PersistenceGameTests covers custom chest and cube serialization, private state surviving visual-only updates, excluding the backpack host slot, stale backpack identity after replacement, portable-item component serialization, unloaded hub checks without chunk loading, route-filter intersection, owner/trusted-player distinctions and locks on either half of a double chest.

An intentional exception from the faulty-container test is expected in the test log; success is determined by the final required-test result, not by the absence of the word ERROR. First-boot server.properties initialization messages are likewise not failed inventory tests.

## Manual acceptance before replacing old mods

1. Back up a disposable copy of the actual server. Install the same beta JAR on two clients and the server. Verify normal login, GUI scale, item models, chest facing, lid animation and label synchronization.
2. Create bases owned by different players, trust/untrust players, test inside and outside OPAC claims, test locked vanilla/double chests, borders, unloaded chunks and dimensions. A compatibility adapter failure must deny access, not silently allow it.
3. Open the same chest/backpack through allowed paths, perform repeated/shift/right clicks while another player or hopper changes contents, and verify exact component-bearing item totals. Try deleting/replacing the source block while a menu is open.
4. Test search, favorites, protected hotbar/main slots, QuickStack versus Dump, category/tag rules, exact-item routes, priorities, overflow and before/after organization previews. Verify stale previews cancel rather than overwriting newer contents.
5. Feed ore with both cancellation and confirmation, fill progress near its cap, buy radius upgrades and verify the same radius controls remote access, scanning and base QuickStack. Compare the impact preview after reducing range.
6. Install/remove each backpack upgrade with full and nearly-full inventories. Verify filters, normal pickup behavior, safe food, refill, portable-grid item returns, fuel/progress/remainders/XP, manual compaction and manual tool resupply.
7. Enable an optional tier with the ACTUAL installed Dragonsteel/Witherite item, craft it with a filled named backpack, then disable the tier and reduce its configured capacity. Existing contents must remain accessible and untruncated. Test missing-mod startup on a copy.
8. Save/stop/restart normally and compare all item counts, components, ownership, routes, protected slots and progression. Test disconnect/reconnect separately. Forced crash testing belongs only on disposable copies and is not a claimed crash-consistency guarantee.
9. Choose an endgame recipe deliberately. Verify tracked survival dragon victories, no creative/untracked-token reward and the chosen cube ingredients. Test the last slot of a filled cube across restart.
10. Only after the complete pack passes acceptance should old storage mods be removed. Check dependency relationships before removing Sophisticated Core. There is no automatic migration of their inventories.
