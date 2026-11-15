# Build evidence

The playable artifact is produced by the read-only `.github/workflows/build.yml` pipeline from its exact checkout. `SOURCE_COMMIT.txt`, `SHA256SUMS.txt`, build/GameTest/persistence/client logs and actual client screenshots travel together with the JAR and repository source archive.

A pre-release verification of the illustrated guide and restart probe succeeded on source `db8da93413c256980e9970ce88ce17eeb55f4fd0`, GitHub Actions run `43670701970`. It passed 343 configuration, 540 expansion/security, 109 grant-journal and 1,154 image-protocol assertions; all 40 required GameTests; both dedicated-server persistence phases; and client render capture. Any later source commit must be judged by its own completed run, not this earlier result.

The old verified beta remains at `533582032fe8bc2aa97fc6e93c1bf8d76c781540` on the preserved main branch. No installation, valuable-world migration, optional renderer installation or live-server deployment is performed by these builds.

The final delivery's verification text identifies its precise source commit, run, JAR SHA-256 and outstanding limitations. The broader manual acceptance list is in TESTING.md.
