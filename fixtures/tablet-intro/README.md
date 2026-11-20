# Tablet portrait and double blink

Starting in `tabletintro.6`, the server selects the opening portrait with a configurable 24% default chance. A skipped portrait goes straight to the usable storage inventory. [Operator instructions and the Configtopia integration contract](../../docs/TABLET_OPENING_CHANCE.md)

## Body gestures in fieldkit.1

Cupid's four openings now frame her upper body and keep the original Cloud expressions and double blink. One includes a raised-hand wave. The three everyday idle visits show her standing, shifting her weight, looking around or waving. The fourth visit begins upside down with her boots overhead. She turns upright, catches her balance and slips away. A quiet visit has a one-in-eight chance of picking the tumble when the previous visit was ordinary. The tablet can still open during a visit. The server's opening chance, first-click timing, and independent wait between idle visits are the same.

The art builder below now reads the Cloud model's full bone hierarchy and native textures. It poses the real shoulders, forearms, legs, torso and head. It produces the everyday 9 by 7 sheet and an extra 9 by 1 tumble sheet. Each frame is 224 by 128 pixels before the existing tablet glass and shell are drawn in-game. The small model needs no Figura install or live animation scripts on either client or server. Missing tumble art selects a regular visit. Missing expression art selects the classic blink or glance.

## Cloud expressions in tabletintro.5

The opt-in tablet uses four opening portraits from **Anya Cupid - expressions (Cloud).zip**. One stays near the viewer, while the others turn sideways, look up with a wink or look down with a smirk. The client picks once when the face appears. The next opening uses a different row. All four take the original 1.05 seconds from confirmed storage to the usable inventory. The lift still runs on its own 190 ms clock.

Holding a visible Remote quietly for 55–110 seconds can start a separate idle visit. Cupid turns toward the viewer, changes expression, looks away and fades back to dark glass in 1.14 seconds. The next visit uses a different sequence. Using the tablet interrupts a visit immediately. If the new texture is unavailable, the client can still show the earlier twin-tails blink and short glance.

The Cloud archive supplies the head, horns, hair, flowers, individual lid and iris meshes, and expression overlays. The source archive has SHA-256 `2c7ff2b20aabb475df9ffdb6d666cfe752987ffdf51c3e2477b7a5d9dceb736e`. Reproduce the pre-rendered sheet with Python, NumPy and Pillow.

```sh
python scripts/tablet_expression_art.py "/path/to/Anya Cupid - expressions (Cloud).zip"
```

The 9 × 7 sheet uses nine 224 × 128 frames per row. Opening rows occupy the first four rows and quiet visits occupy the last three. The client samples the sheet using its presentation clock. Rendering the model and reading its expression textures only happens when rebuilding the artwork.

The [offline preview](../tablet-expressions/preview.py) takes paths to the candidate JAR, Java 21 JDK, JOML library, Minecraft 1.21.1 client resources and the original storage screenshot. Its `--help` lists the arguments. It samples the compiled expression and lift clocks. The preview skips the quiet waiting period and shows the idle motions and openings at their actual speed.

## Twin-tails portrait in tabletintro.4

The earlier blue portrait uses the head, pink twin-tail meshes, temple flowers, original eyelids and irises from **Anya Cupid - twin tails.zip**. The avatar credits Sparkie_Kat for its modeling, texturing and animation. The source archive has SHA-256 `bbb6c314b54dc1b2d50b52ce62113fe22814563c28dc30724ef1d5f0db02540b`.

To reproduce the two client textures with Python, NumPy and Pillow, run the following from the project root with the path to the supplied archive.

```sh
python scripts/tablet_guardian_art.py "/path/to/Anya Cupid - twin tails.zip"
```

The generator projects a fixed front view of the model, samples its upper and lower lids, then applies the blue glass and scanlines. The idle frame moves both irises toward screen-left. The three blink rows fill `tablet_guardian.png` and the idle frame fills `tablet_gaze_left.png`. The packaged mod needs only the baked textures. The original avatar archive and its scripts stay outside the JAR.

## Original seated Guardian prototype

This local client prototype starts from Anya Storage Plus expansion.1, commit `534a53030d4958d2fe84523206197b654f30d8cf`. The preview GIF uses the same blink timing as the client and ends on the storage panel from Anya's earlier screenshot. Its two-second final hold makes the GIF easy to watch in a loop. In the client, the open menu stays interactive after the intro.

### Appearance and timing

The source is `Guardian portrait.png` from project #3's **Seated Cupid Guardian** package. Its SHA-256 is `300e74cebdd74cc0183c28db962bac70b7d5e31b3880b48746e788c51e535bdb`. The checked-in texture contains three blue CRT treatments of the same face crop, with open, half-closed and closed eyes. The art script checks the source before rebuilding the texture.

The portrait blinks twice in 1.05 seconds. A confirmed storage page starts the animation. If the menu type is still arriving, the neutral screen waits up to 300 milliseconds. After a timeout the ordinary menu takes over. The intro runs once per opening and can be closed with the normal keys.

### Client integration

The isolated source version is `0.3.0-beta.7-tabletintro.1`. Presentation defaults to off. The local Java property `-Danyastorage.tabletIntro=true` selects it in a future candidate built from this branch. Existing storage menus share one client screen, so the intro covers normal backpack, connected storage and chest views, including pocket storage. Settings and upgrade pages skip it once their page type is available.

The server opens and owns the menu as usual. The client covers its controls during the blink and then draws the same menu instance. Only the existing synchronized page flags are consulted. The patch adds no packet, item, save field, permission or dependency on the dimension mod.

### Offline checks

The changed client sources compiled against expansion.1, Java 21 and cached NeoForge 21.1.249. `GuardianBlinkCheck.java` exercises both blinks, the opt-out, input coverage, the sync timeout, page changes, dismissal and reopening. `preview.py` reads its frame timing from the production clock and verifies the decoded GIF against the source frames and durations. The storage image is a historical screenshot, not a capture of this client prototype.

The next in-game check is a disposable client opening storage, closing during the second blink, and reopening after a resize. A slow connection and a server-initiated close also need a live check. The handoff contains source for review; no playable JAR was built for this intro.
