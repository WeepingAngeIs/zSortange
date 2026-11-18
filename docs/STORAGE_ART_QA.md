# Storage art preview

The art candidate starts at elemental-pockets checkpoint `21ab18a` and uses its own version, `0.3.0-beta.7-art.1`. The ordinary storage block and item IDs, saved inventories and server-side permission model are shared with the previous beta. A build is a candidate for review. Installing it in an existing world is a separate decision.

## Small disposable checks

The build workflow runs `scripts/ordinary_storage_qa.py` on a standard GitHub-hosted Linux runner. It creates a flat loopback-only dedicated server with Java 21, NeoForge 21.1.249, the packaged Storage Plus candidate, the test-only acceptance fixture, Open Parties and Claims 0.33.0 and Carry On 2.2.6.13. The two integration JARs must match their recorded SHA-256s. The server has a 1,400 MiB heap limit, two-chunk view and simulation distances, and no connected clients. The fixture refuses to reuse an existing server directory. Neither the fixture nor its test actions belong in a playable profile.

The first process attempts a withdrawal with View only. It then grants the simulated guest withdrawal permission, prepares a transfer, and claims the chunk for the owner. It verifies the recorded OPAC owner and the refused transfer, including the unchanged chest and guest inventory. After unclaiming, it moves one named shard and checks that a later revocation blocks a second prepared transfer. The second process checks all 54 chest slots, all 216 cube slots and the empty hub. Seven named shards remain in the chest and one in the cube. The last cube slot holds the original 27-slot backpack with its single totem in slot 26. It also checks ownership, View permission and component data. Pockets are off for both processes. Carry On is loaded alongside OPAC. This fixture exercises OPAC.

The existing core and GameTests exercise randomized transfers, component conservation, rollback, independent rights and optional Accessories. The software-rendered client smoke check captures the actual members screen with a synthetic View-only member. Its screenshot covers the appearance of the controls; the dedicated checks cover the permissions. No full-pack compatibility or production-release conclusion follows from these fixtures.

## Placed art and a real guest reconnect

A separate public GitHub-hosted run starts two small Minecraft clients on the disposable server with pockets off. The owner photographs the placed chest while the guest opens and closes it. The observer checks for one open chime and one close chime at the chest. The test also photographs the two entrance faces and the real member page with the guest selected. It checks the lid movement in the images and compares the illustrated planet, chests and fallback material to the artwork in the tested JAR. It then interrupts the guest's connection, waits for the server to record the departure, and reconnects that guest. The server checks their UUID and the name and exact count of their three amethyst shards.

The entrance illustration is the public four-chest starter layout. It reads no private room data. These captures use software rendering with a small set of mods. Hardware performance and the entire CurseForge pack require separate evidence.

## Previous full-pack observation

In the separate beta.6 trial, the tester reported seeing storage after reopening and seeing chest contents with View-only access while a withdrawal was refused. The guest log independently recorded denials requiring Withdraw. The running dedicated server also read back the same four placed Anya blocks after restart, including 1,792 cobblestone, the same backpack with a totem, and all 56 items in the adjacent chest. The View-only click did not have an independent before-and-after count capture.
