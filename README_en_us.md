# DDS Manager

[中文](README.md) | **English**

A management plugin for authenticated Java Edition Velocity networks.

## Features

- UUID-based player profiles, built-in administrators, centralized `whitelist.json`, and per-server / server-group whitelists
- Last Server, Local/Global chat (Global includes server tags), cross-server Tab, Join/Leave/Switch events, and `/seen`
- Vanilla three-dimension presence without additional backend mods or RCON
- Customizable server tags in Tab, optional dimension indicators, and vanilla-style ping bars
- Xaero/VoxelMap world identity synchronization
- Clickable chat components for whitelist pages, player details, server status, and more
- Hover authorization sources, click-to-add/remove actions, and one-step confirmation for dangerous operations
- Atomic JSON writes with backup recovery, asynchronous logging, and administrative auditing

## Tab Display

Default layout:

```text
⁑ [S]PlayerID   + vanilla ping bars
```

The dimension symbol appears at the beginning of the display name after the player head, followed immediately by the server tag and authenticated Java username. All dimensions use one configurable symbol, `⁑` by default, without `[]`. DDS assigns the symbol color from the player's actual dimension: green for Overworld, red for Nether, light purple for End, and gray when unknown.

```json
"presence": {
  "showServerInTabName": true,
  "showDimensionInTabName": true,
  "dimensionSymbol": "⁑",
  "serverPrefixes": {
    "survival": "S",
    "creative": "C",
    "mirror": "M"
  },
  "serverLabels": {
    "survival": "<gradient:#55ff55:#00ffaa><bold>[S]</bold></gradient>"
  }
}
```

`dimensionSymbol` supports 1–4 visible characters and may be changed to `▏`, `│`, `|`, `·`, or a resource-pack font glyph. Its color cannot be overridden by configuration. Disabling `showDimensionInTabName` hides only the dimension symbol and does not affect the server tag or ping display.

DDS does not provide nicknames. Tab always shows the player's real Java username, and identity, whitelist entries, and permissions always use authenticated UUIDs/usernames.

## Server Name / Tag Formatting

Server tags have two configuration layers:

- `serverPrefixes`: short fallback values. For example, `"survival": "S"` is automatically displayed as `[S]`.
- `serverLabels`: complete tag templates. When configured, these replace `[S]` directly, including brackets, symbols, colors, and styles.

Example:

```json
"serverPrefixes": {
  "survival": "S",
  "creative": "C",
  "mirror": "M"
},
"serverLabels": {
  "survival": "<#55ff88><bold>[S]</bold></#55ff88>",
  "creative": "<gradient:#55ffff:#5555ff><bold>✦C✦</bold></gradient>",
  "mirror": "<light_purple><obfuscated>xx</obfuscated>M<obfuscated>xx</obfuscated></light_purple>"
}
```

DDS uses its own restricted style parser with MiniMessage-like syntax, without loading the MiniMessage runtime. Supported formats:

| Type | Example |
| --- | --- |
| Named color | `<green>[S]</green>`, `<light_purple>[M]</light_purple>` |
| RGB | `<#55ff88>[S]</#55ff88>` or `<color:#55ff88>[S]</color>` |
| Bold | `<bold>[S]</bold>` or `<b>[S]</b>` |
| Italic | `<italic>[S]</italic>`, `<i>[S]</i>`, `<em>[S]</em>` |
| Underline | `<underlined>[S]</underlined>` or `<u>[S]</u>` |
| Strikethrough | `<strikethrough>[S]</strikethrough>` or `<st>[S]</st>` |
| Obfuscated | `<obfuscated>xx</obfuscated>` or `<obf>xx</obf>` |
| Gradient | `<gradient:#55ffff:#5555ff>Creative</gradient>` |
| Rainbow | `<rainbow>Mirror</rainbow>`; use `<rainbow:0.25>Mirror</rainbow>` to adjust phase |
| Transition | `<transition:#55ff55:#5555ff:0.0>[S]</transition>`, phase range `-1` to `1` |
| Custom font | `<font:minecraft:default>[S]</font>`; may be combined with resource-pack font glyphs |
| Reset | `<reset>` |

Supported named colors: `black`, `dark_blue`, `dark_green`, `dark_aqua`, `dark_red`, `dark_purple`, `gold`, `gray`, `dark_gray`, `blue`, `green`, `aqua`, `red`, `light_purple`, `yellow`, and `white`. `grey` / `dark_grey` are also accepted.

To display `<`, `>`, or `\` literally, use `\<`, `\>`, or `\\`. Tag templates are limited to 128 Unicode code points. `<click>`, `<hover>`, `<insertion>`, unknown tags, malformed closing tags, and similar input are rejected to prevent interaction-event injection through configuration. DDS attaches its own required Hover/Click events separately at the component layer.

Resource-pack icon example: if a custom font maps `\uE001` to a server icon, you can use:

```json
"serverLabels": {
  "survival": "<font:dds:icons></font><#55ff88><bold>S</bold></#55ff88>"
}
```

The resulting Tab entries may look like:

```text
⁑ [S]PlayerID
⁑ ✦C✦PlayerID
⁑ <custom icon>SPlayerID
```

## Administrators and Permissions

On first startup, DDS generates `plugins/dds-manager/config.json`. Stop the proxy and add authenticated UUIDs:

```json
{
  "administrators": [
    "00000000-0000-0000-0000-000000000000"
  ]
}
```

Configured administrators are equivalent to `dds-manager.admin`: they can manage all DDS features and bypass the DDS whitelist. Regular players have no management permissions by default. `dds-manager.server.<server>` and `dds-manager.group.<group>` grant only server-entry authorization. Delegated management requires explicit fine-grained management permissions.

Public player commands include `/dds channel [local|global]` and `/dds seen <player>`. Help output and Tab completion are filtered by permission.

## Centralized Whitelist File

`plugins/dds-manager/whitelist.json` provides a centralized network whitelist similar to lls-manager:

```json
{
  "whitelist": ["Alice", "Bob"]
}
```

On first startup, DDS generates the file from existing network-wide authorization data. After editing it, run `/dds reload` and click `[Confirm]` once to synchronize. New names become pending entries and are bound to authenticated UUIDs on first login. Removing a name revokes only the network-wide scope; per-server and server-group authorization remains intact. If the file contains an invalid format or player name, the entire synchronization is rejected instead of partially deleting entries from incomplete data. Network-wide whitelist commands and snapshot imports also write back to this file automatically.

## Whitelist Snapshots and Reliability

`/dds whitelist export [file]` creates UUID-based snapshots, preserving separate historical accounts even if they shared the same username. During import, known UUIDs retain their current names, preventing authorization from being transferred from an old account to a newer account with the same name. Unknown names remain pending for compatibility with older name-based snapshots. Imports validate the entire file first; invalid UUIDs, player names, authorization entries, or duplicate identities reject the whole import. Disk-write failures are reported separately, and successfully persisted entries are not rolled back.

Administrative tasks re-check player connection and permissions immediately before execution; queued actions are cancelled if permission is revoked or the player disconnects. Configuration changes take effect only after a successful disk write. Failed reloads retain the current runtime configuration. If the configuration cannot be read at startup, DDS continues with a safe default that keeps the whitelist enabled.

## Common Administrative Commands

```text
/dds status
/dds server [server]
/dds player <player>
/dds reload
/dds alert <message...>
/dds whitelist status|on|off
/dds whitelist add|remove all <player...>
/dds whitelist add|remove server <server> <player...>
/dds whitelist add|remove group <group> <player...>
/dds whitelist list [page|player]
/dds whitelist check <player> <server>
/dds whitelist export|import [file]
/dds group create|delete <group>
/dds group add|remove <group> <server...>
/dds group list [group]
```

Dangerous operations use a single confirmation step: `[DDS] Dangerous operation: <description> [Confirm]`. Clicking it executes the action directly and outputs only the final result. Audit logs are stored in `logs/audit-YYYY-MM-DD.log`.

## Build and License

```bash
./gradlew clean build
```

DDS Manager is distributed under GPL-3.0. Third-party licenses and upstream acknowledgements are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Major Contributor

[plusls](https://github.com/plusls) — author of [lls-manager](https://github.com/plusls/lls-manager) and a contributor to DDS Manager. DDS's product direction, cross-server management concept, and original requirements were inspired by lls-manager.
