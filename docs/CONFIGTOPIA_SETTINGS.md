# Connecting Configtopia to Anya Storage Plus

The `0.3.0-beta.7-packsettings.1` candidate exposes version 1 of the Storage Plus operator API for Minecraft 1.21.1 and NeoForge 21.1.249. The pack screen can display Anya's settings and submit a single edit to Anya's existing validator, revision check, backup and save transaction. The native operator screen and the adapter share `config/anya-storage-plus.json`.

The hosted artifact includes `SCHEMA.json` with all 71 definitions (43 toggles, 20 integers, 7 decimals and 1 registry ID). It contains definition defaults. Use an authorized snapshot for a server's current values. For example, a newly created configuration stores room editing enabled for first use while pocket storage is off. Existing servers retain their saved choice.

## Discover the controls

1. On the logical server, check for mod ID `anyastorage`. Resolve `dev.anya.storage.Config` and `dev.anya.storage.ServerSettings` through the mod class loader. Check that public static integer `Config.PACK_SETTINGS_API` equals `1` and resolve the signatures below. Treat absence, an unknown version or an incompatible signature as unavailable. Hide the optional controls or show a disabled explanation.
2. Get the actual connected `ServerPlayer` from the server-side request. Reads and writes must run on the server thread with that player and operator permission level 2. Discard an open settings page on disconnect or permission loss. A client-provided player ID is insufficient.
3. Read `Config.serverSettings(ServerPlayer)`. An authorized call returns a snapshot with `revision()` and immutable `values()` keyed by setting ID. A denied call returns null. Present only keys available in that snapshot. Keep its revision with the displayed values.
4. Enumerate `ServerSettings.keys()` and call `ServerSettings.definition(String)` for each key. Use its `key()`, `label()`, `description()`, `kind()`, `defaultValue()`, `minimum()`, `maximum()`, `unit()`, `applyMode()` and `confirmValue()` accessors. Numeric bounds are inclusive. Bounds that do not apply are null. An empty unit needs no suffix. Use the actual snapshot value for the current setting.

| Kind | Control and wire value |
| --- | --- |
| `BOOLEAN` | Toggle submitting lowercase `true` or `false`. |
| `INTEGER` | Bounded whole number, submitted in base ten. |
| `DECIMAL` | Finite decimal in the declared units. For a `fraction`, 0.05 means 5%. |
| `REGISTRY_ID` | A full namespaced identifier such as `minecraft:diamond`. Anya validates the setting and the rest of the configuration together. |

## Save and confirm

Call `Config.applyServerSetting(ServerPlayer, long expectedRevision, String key, String value, boolean confirmed)` with the displayed revision and `confirmed=false` for the first submission. The response record exposes `status()`, `message()` and `revision()`. Use status for control flow and show the returned message to the operator.

| Result | Caller action |
| --- | --- |
| `applied` or `unchanged` | Read a fresh snapshot. An unchanged edit retains the revision. |
| `confirmation_required` | Show the operator the proposed change. Only an explicit second confirmation should submit the same key, exact value and revision with `confirmed=true`. |
| `rejected` | Show the reason. A stale revision requires a fresh snapshot and a new user decision. A disconnected or unauthorized actor gets revision `-1`. |
| `recovery_required` | Stop edits, show the server message and allow operator recovery before retrying. |
| `pending_reload` | A resource reload is in progress. Wait for its result before reporting completion or enabling another write. |

Poll `Config.serverSettingsStatus(ServerPlayer)` on the server thread while a reload is pending. It returns the same record type. At `ready`, reread the snapshot and compare the resulting revision and setting with the submission. A failed reload can restore the previous value at a newer revision. At `recovery_required`, show the server message and stop edits pending operator recovery. Treat `rejected` as loss of access. A definition's `applyMode()` is `live` or `resource_reload` for a single edit.

`confirmValue()` is the Boolean target that requires confirmation for a change, or null. In v1 this covers disabling pocket storage, disabling image moderation, and enabling image sharing. A pending confirmation belongs to one connected player object, revision, key, value and settings surface. It expires after 600 game ticks (about 30 seconds at 20 TPS). Blindly sending `confirmed=true` cannot commit the first request. When an operator cancels, discard the pending interaction on the client. A later attempt starts with `confirmed=false`.

## Scope in a modpack

- The snapshot describes server-wide configuration. Use the reported units for hub radius in blocks, room/session limits and elemental bonuses. A radius limit describes storage reach. Ownership, claims, actual inventories, private images, camera feeds, individual room decor and player preferences stay with their existing owners and interfaces.
- `expansion.pocketStorage`, `expansion.pocketEditing` and the four tablet/chest/backpack Cupid settings apply live. Turning off pocket storage can end active access. The tablet opening cameo defaults to 24 percent, with an inclusive 0–100 range. At zero, eligible tablet openings go straight to inventory. The four chest and cube open/close sounds are emitted by the server. These and the two client-side entrance-frame cues use Minecraft's Blocks volume. The client-side tablet warning uses Players volume. All seven follow each listener's local volume settings.
- Use the native screen for custom tier lists, individual tier rows, rule collections and other structured editors absent from the catalogue. Delegate complex operations to their native controls. The existing operator command `/anya storage settings` opens the page. Its `list`, `get <key>` and `set <key> <value>` subcommands remain human-facing shortcuts. A pack adapter can offer the native page as a fallback when Anya is present. It should avoid issuing commands upon discovery or editing the JSON itself.

## Version boundary

Earlier builds supply a tablet percentage API and, on later candidates, a value snapshot and Boolean-returning single-setting setter. Those signatures alone do not establish support for v1 metadata, confirmations or reload results. Detect the version at runtime. [Earlier tablet contract](TABLET_OPENING_CHANCE.md)

The inspected Unyielding 1.2.0 Configtopia preview has SHA-256 `59cd3adfcd4c07403f81cadd575a2ad01bddf3bb236c449491236bbfd3cf612d`. Its matching Server-Jars source is the `codex/configtopia-excavation` checkout at `3d7ea8da66830b811fb848ebcf63b7ce4b349cdd`. Its generic discovery reads loaded NeoForge COMMON and SERVER specs and it has a dedicated adapter for UwU Dimension excavation. A Storage-specific caller is required for this JSON-backed API. Availability in a real Configtopia menu requires a Configtopia build containing that caller and a joint in-game check. The Storage-only reflective fixture exercises the producer contract independently. [Server-Jars source repository](https://github.com/WeepingAngeIs/Server-Jars) · [Reflective fixture](../fixtures/pack-settings/java/probe/ReflectiveSettings.java)
