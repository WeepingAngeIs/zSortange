# Verified pre-release build

Source commit: `fb7d74494c7d9e3e30abdd5fda41b400b23aee81`

GitHub Actions run: `43654812369` in `WeepingAngeIs/zSortange`.

The workflow completed successfully on November 14, 2026. It compiled the Minecraft/NeoForge code, passed configuration assertions and completed all 28 required GameTests. The randomized conservation test performs 500 deterministic transfers. The 10 additional persistence tests cover serialization, backpack host identity, visual-only updates, unloaded chunks, ownership, route filters and double-chest partner locks.

The run was triggered by `deeb8ef9c740e39e12538448d6de288cc9e3942f`; a temporary, reviewed source-repair step committed the actual source above before compilation. That step has now been removed from the workflow, and the workflow's repository permission is read-only. New artifacts identify their exact source through `SOURCE_COMMIT.txt` and include both build and GameTest output logs.

This is automated verification, not proof of full-pack compatibility or crash consistency. The actual client appearance, two real players, reconnects, installed optional material mods and live claim configuration remain manual acceptance items. The final distributed JAR should always be checked against its own successful run and included checksum.
