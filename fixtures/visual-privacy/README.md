# Two players at the illustrated pocket entrance

This GitHub-only check uses a fresh dedicated server and two graphical Minecraft clients connected to it. It exercises the art.2 entrance on the pinned CE 6.1.4, Sodium and Iris renderer stack. The optional Anya live adapter declares an exact beta.6 dependency. This job preserves that dependency and exercises the fallback supplied by the packaged art.2 mod.

Both players look at the same entrance from fixed positions. Alpha sees the south face and Beta sees the east face. The server warms a real pocket and sets up its optional eighth chest. The capture sequence is:

| Phase | Alpha | Beta |
| --- | --- | --- |
| Before trust | Public starter-room picture | Public starter-room picture |
| Alpha has View | Actual pad diagram including the eighth chest | Public starter-room picture |
| After revocation | Public starter-room picture | Public starter-room picture |

The hosted result includes lossless framebuffer captures, camera and permission observations, and a contact sheet cropped from those same frames. The pixel check projects the physical entrance face into each camera, compares its pixels with both materials from the reviewed JAR and checks the occupied eighth pad separately. The job also exercises ordinary empty-hand use and movement against the source entrance. The evidence identifies which actions occurred on both clients and the server.

The renderer runs on a GitHub machine using software graphics. This check covers the illustrated fallback. It leaves selective live terrain, a real player's GPU, full-modpack compatibility and release approval for separate acceptance.
