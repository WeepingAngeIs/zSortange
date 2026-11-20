# Two players at the illustrated pocket entrance

## Still-frame candidate

The isolated `experiment/still-frame-peek` branch builds `0.3.0-beta.7-still.1` and enables `anyastorage.stillFramePeek` on its test server and clients. In the negotiated still view, the home entrance displays a fixed drawing of a room corner and a closed chest. Only the wall tile reflects an actual, authorized decoration block in the pocket. The selected position is 24 blocks east and south of the plot origin at Y=68. Cyan and purple concrete are the two supported materials. An unsupported block, cold column or denied position gives the public illustration. The chest, lighting and perspective in the drawing are public fixture art.

The hosted check compares the physical entrance pixels from two clients across the home grant, a cyan-to-purple edit, an exact-position veto, restoration, home revocation, a fresh grant and final revocation. It checks per-recipient still and diagram responses, observes both player caches, and exercises delayed client application using an earlier authorized response. The ordinary entrance click still requires its own Visit permission. No live portal adapter is installed for this check.

The workflow records the exact candidate SHA-256 at build time, verifies it in all three Minecraft processes, and retains captures and test evidence. The baseline's art.2 binary and run are identified separately below. [Passed illustrated-fallback checkpoint](https://github.com/WeepingAngeIs/zSortange/actions/runs/44445503911)

## Illustrated-fallback baseline

This GitHub-only check uses a fresh dedicated server and two graphical Minecraft clients connected to it. It exercises the art.2 entrance on the pinned CE 6.1.4, Sodium and Iris renderer stack. The optional Anya live adapter declares an exact beta.6 dependency. This job preserves that dependency and exercises the fallback supplied by the packaged art.2 mod.

Both players look at the same entrance from fixed positions. Alpha sees the south face and Beta sees the east face. The server warms a real pocket and sets up its optional eighth chest. The capture sequence is:

| Phase | Alpha | Beta |
| --- | --- | --- |
| Before trust | Public starter-room picture | Public starter-room picture |
| Alpha has View | Actual pad diagram including the eighth chest | Public starter-room picture |
| After revocation | Public starter-room picture | Public starter-room picture |

The hosted result includes lossless framebuffer captures, camera and permission observations, and a contact sheet cropped from those same frames. The pixel check projects the physical entrance face into each camera, compares its pixels with both materials from the reviewed JAR and checks the occupied eighth pad separately. The job also exercises ordinary empty-hand use and movement against the source entrance. The evidence identifies which actions occurred on both clients and the server.

The renderer runs on a GitHub machine using software graphics. This check covers the illustrated fallback. It leaves selective live terrain, a real player's GPU, full-modpack compatibility and release approval for separate acceptance.
