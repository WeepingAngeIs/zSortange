# Anya Storage Plus server settings

Operators can use commands or the existing settings menu to manage server-wide policy. Both use the same saved settings in `config/anya-storage-plus.json`. A write goes through Anya's validation, revision check and config backup.

## Turn pockets on or off

Pocket storage starts off with default settings. An existing config that explicitly enabled it remains enabled. On a test server with the mod's pocket dimension available, run these commands as an operator.

```text
/anya pocket status
/anya pocket enable
/anya pocket disable
```

The status reports whether pocket access is active. Changes made with the pocket commands apply immediately. Disabling turns off room editing, closes pocket inventory views, and returns active visitors. If a room is being prepared, the command asks you to wait and retry. Enable editing separately with `/anya storage settings set expansion.pocketEditing true` after pocket storage is active.

## Read and change settings

```text
/anya storage settings list
/anya storage settings get expansion.tabletOpeningChancePercent
/anya storage settings set expansion.tabletOpeningChancePercent 24
/anya storage settings set recipes.terminals true
/anya storage settings set maxRadius 64
```

The settings cover server feature switches, recipe and backpack-upgrade switches, network ranges and budgets, tutorial and activity policy, backpack appearance and effects, images, pockets, and Cupid. Type `true` or `false` for a switch. Whole-number settings take integers. Decimal settings use fractions where the setting describes a fraction, such as `0.20` for twenty percent fire damage reduction. A material uses its full item ID, such as `minecraft:diamond`.

The initial hub radius must fit within the maximum hub radius. The UwU Storage Cube recipe uses `0` for off, `1` for Dragon's Breath and `2` for Dragon Victory Tokens. Both active choices require `recipes.endgame`. Changes to Cupid or the pocket access and editing switches apply as soon as they are saved. Other changes use the usual server recipe reload. Reserved pocket activity-log fields stay in the legacy config and are outside the command and adapter interface.

Use `/anya storage` to edit backpack tiers, ore-point values, category rules, monster species and image-uploader lists, or to open the image moderation library. Personal slots and per-base or per-pocket permissions are managed where those settings apply.

## Server API for a pack settings screen

Use these types from `dev.anya.storage`. The adapter supplies an actual operator and calls the methods on the logical server thread.

1. Call `Config.serverSettings(actor)` for a `Config.SettingsSnapshot`. A successful snapshot contains a revision and an immutable map of the current scalar values. A missing or unauthorized operator receives `null`.
2. Use `ServerSettings.keys()` for the sorted, immutable list of supported keys. Call `ServerSettings.definition(key)` for its label, description, kind, default, and optional numeric bounds. Supported kinds are `BOOLEAN`, `INTEGER`, `DECIMAL`, and `REGISTRY_ID`. A key outside the registry has no definition. The usual server checks also apply between dependent settings.
3. Submit one setting with `Config.setServerSetting(actor, displayedRevision, key, value)`. A `true` result means the edit was accepted or the saved value already matched. Watch the settings status for the result of a recipe reload. A `false` result means the operator should reread current settings before trying again.

Keys match the saved scalar paths. Use `network` for a root setting, `expansion.pocketStorage` for an expansion setting, and fixed paths such as `recipes.terminals` or `upgrades.pickup` for switches. Keep the snapshot revision with the displayed values and fetch a fresh snapshot after each submission. Anya owns the config file, validation, audit and reload. A pack settings screen needs an adapter that calls this API. The earlier `Config.tabletOpeningChance()` and `Config.setTabletOpeningChancePercent(...)` methods still accept the existing Cupid option.
