# Tablet handling check

This candidate builds on `0.3.0-beta.7-tabletintro.2`. The separate `0.3.0-beta.7-tabletintro.3` keeps the same opt-in, silver case, face atlas, idle glance, blink clock and inventory layout.

## Rendering

NeoForge 21.1.249 exposes the selected player's `PlayerRenderer.renderRightHand` and `renderLeftHand`. Minecraft uses these methods for its first-person map arm. They draw the selected skin's standard or slim arm, respect the sleeve setting and pass through NeoForge's first-person arm hook.

The client captures the selected first-person hand and its packed light. The native renderer draws its arm mesh in GUI projection. The wrist follows a point behind the corresponding silver side rail. The shoulder approaches from below. At rest the screen pitches upward by 39 degrees, yaws inward by 19 degrees and rolls five degrees to the gripping side. The GUI canvas and player camera retain their normal transforms. The device is then painted over the tucked wrist, and the open inventory is painted over the device. The native arm is rigid; its grip comes from wrist position and forearm angle.

A local use edge starts raising the device. Without a matching menu it can rise partially, pause briefly and settle back. An authorized storage screen takes the last painted pose, completes the lift at the same rate, and runs its existing blink immediately when ready. The rise duration is controlled by `TabletLift.RISE_MS`, currently 190 ms. Slot interaction uses the existing settled layout throughout the actionable menu. The idle wake remains independent of this clock.

## Offline evidence

The fixtures use Java 21 with the cached 1.21.1 / NeoForge 21.1.249 dependency classpath. The geometry checks use the production JOML matrices. The renderer dispatch check uses the production adapter with a recording player renderer and buffers. The handling preview uses the same transforms and the packaged game's default wide Steve skin as an illustrative model. Its storage frame uses the earlier supplied screenshot. An example menu becomes available 60 ms after use.

The preview is a software projection. Native GPU lighting, shader packs, custom skin or arm renderers and first-person feel in the actual pack still need in-game review. The fixture code and default skin are excluded from the mod JAR.
