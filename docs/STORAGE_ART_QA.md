# Storage art preview

The art candidate starts at elemental-pockets checkpoint `21ab18a` and uses its own version, `0.3.0-beta.7-art.1`. The ordinary storage block and item IDs, saved inventories and server-side permission model are shared with the previous beta. A build is a candidate for review. Installing it in an existing world is a separate decision.

## Small disposable checks

The build workflow runs `scripts/ordinary_storage_qa.py` on a standard GitHub-hosted Linux runner. It creates a flat loopback-only dedicated server with Java 21, NeoForge 21.1.249, the packaged Storage Plus candidate, the test-only acceptance fixture, Open Parties and Claims 0.33.0 and Carry On 2.2.6.13. The two integration JARs must match their recorded SHA-256s. The server has a 1,400 MiB heap limit, two-chunk view and simulation distances, and no connected clients. The fixture refuses to reuse an existing server directory. Neither the fixture nor its test actions belong in a playable profile.

The first process checks a non-operator test actor with View, a denied withdrawal, an allowed one-item withdrawal, a foreign OPAC claim, and revocation between preparation and commit. It saves a named item split seven and one across containers, plus a backpack carrying one totem in the last slot of a 216-slot cube. The second process verifies the same backpack identity, item components, counts, empty slots, ownership and View permission from the saved world. Pockets are off for both processes. These checks use a simulated server-side guest. A network reconnect needs two actual sessions and separate evidence. Carry On is loaded here; this particular test exercises the OPAC interaction.

The existing core and GameTests exercise randomized transfers, component conservation, rollback, independent rights and optional Accessories. The software-rendered client smoke check captures the actual members screen with a synthetic View-only member. Its screenshot covers the appearance of the controls; the dedicated checks cover the permissions. No full-pack compatibility or production-release conclusion follows from these fixtures.

## Previous full-pack observation

In the separate beta.6 trial, the tester reported seeing storage after reopening and seeing chest contents with View-only access while a withdrawal was refused. The guest log independently recorded denials requiring Withdraw. The running dedicated server also read back the same four placed Anya blocks after restart, including 1,792 cobblestone, the same backpack with a totem, and all 56 items in the adjacent chest. The View-only click did not have an independent before-and-after count capture.
