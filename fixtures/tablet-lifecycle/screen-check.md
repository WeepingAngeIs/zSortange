# Offline client screen dispatch

`run.py` compiles the changed production screen against Java 21, the local NeoForge class path and the expansion.1 support JAR. Supplying `--packaged` skips recompiling the production classes and runs the same check against the candidate JAR instead. Each subprocess has a 45-second limit.

```text
python -B fixtures/tablet-lifecycle/run.py --packaged --jdk <jdk-21-directory> --classpath-file <local-javac-classpath.args> --support-jar <candidate.jar>
```

The fixture invokes the real screen constructor, initialization, rendering and input handlers and records their effects at the desktop boundary. It runs the actual blink and device-bounds code, reads the pose matrix at the existing storage panel's first paint call, and uses those painted coordinates to click a slot. It inspects the actual resulting storage action record in memory.

The cases cover ordinary opening with the option off, hidden input, search and slot clicks on the first revealed frame, resizing, close before first paint, Escape during synchronization and each blink segment, repeat opening, two menus with the same numeric ID, replacement players, disconnect, temporary overlays, and cleanup. A separate case records the held device's pose, follows its movement into the open menu, and checks invalidation after consumption, timeout, switching the held object, or resizing.

`desktop_host.py` supplies isolated stand-ins for the active player, menu page flags, held-object identities, appearance storage, drawing operations, vanilla close/remove callbacks and packet delivery. The fixture stops normal menu painting at the storage panel boundary, before glyphs and inventory cells are drawn. It does not test native input delivery, a graphics driver, server synchronization, actual packet transmission or interaction with a Minecraft world. The stand-ins are compiled into a temporary directory and excluded from the game JAR.
