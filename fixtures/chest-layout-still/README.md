# Closed-chest still preview

This local candidate layers a closed-chest illustration over an illustrated floor at each occupied, registered room position. All chest pixels come from one bundled texture. Enabling `-Danyastorage.chestLayoutStill=true` on the client selects this version of the existing non-live preview, even if the separate marker preview is enabled. The client uses the existing pad subscription and the server's existing configuration. On a connection without that pad channel, the watched face uses the public illustration.

The existing observation sends the home entrance position and focus token, plus one occupancy byte for an authorized layout. Room positions below are fixed offsets from the room origin and already part of the room layout.

| Mask bit | Room X | Y | Room Z | Position |
| --- | ---: | ---: | ---: | --- |
| 0 | 6 | 65 | 6 | Original chest |
| 1 | 10 | 65 | 6 | Original chest |
| 2 | 14 | 65 | 6 | Original chest |
| 3 | 18 | 65 | 6 | Original chest |
| 4 | 6 | 65 | 14 | Optional pad |
| 5 | 10 | 65 | 14 | Optional pad |
| 6 | 22 | 65 | 14 | Optional pad |
| 7 | 26 | 65 | 14 | Optional pad |

The existing server checks the viewer's home access, line of sight, the loaded plot and all eight claim positions, including empty pads, before granting the layout. An incomplete, unavailable or denied layout uses the entire public illustration. A server clear retires the focus on receipt. Looking away, opening a screen, unloading or leaving the connection retires the local focus. The client lease expires after 24 client ticks or 1.2 seconds, whichever comes first. A fresh grant requires a new focus token after a clear.

The one-byte layout represents occupancy only. Contents, NBT, names, lid motion and decoration are outside the rendering inputs. Movement, physical entry, collision, reach and pocket storage use their existing paths. The inherited observation limits are eight viewers at once, a heartbeat no more than once every ten server ticks per viewer, and a 1,200-tick maximum focus session. Each draw uses up to eight closed-chest symbols.

## Offline checks

From the project root with Java 21 on the path and Python 3 with Pillow capable of `ImageChops.getbbox(alpha_only=False)`:

```text
python fixtures/chest-layout-still/compare.py --output build/chest-layout-comparison
javac --release 21 -d build/chest-layout-focus src/main/java/dev/anya/storage/pocket/EntranceFocusState.java fixtures/chest-layout-still/EntranceFocusCheck.java
java -cp build/chest-layout-focus dev.anya.storage.pocket.EntranceFocusCheck
```

The comparison uses the renderer's projection and packaged textures with fabricated inputs. It covers all 16 optional-pad configurations, 32 comparisons that change one pad and 243 ineligible masks. The state check covers independent viewer state, revocation, delayed updates, fresh grants, removal, both lease limits and local clear. Production client files and current observation dependencies were type-checked with Java 21 against cached NeoForge 21.1.249 and the earlier `art.2` dependency classes.

The earlier single-marker preview passed a separate hosted check at `b151a9da6498f351d60d4ba513129325e6c575d3`. This candidate still needs a two-player in-game check with different permissions, a claim change at a registered position, and an in-focus revoke and regrant. A full modpack and real-GPU acceptance run is a separate milestone. [Earlier hosted evidence](https://github.com/WeepingAngeIs/zSortange/actions/runs/44453939811)
