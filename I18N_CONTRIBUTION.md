# DDS Manager i18n (proposed contribution)

This patch adds editable language packs (`en_US`, `fr_FR`, `zh_CN`) with
auto-detection from the Minecraft client locale, a configurable console locale,
and fallback to English. Config keys and server / player IDs remain unchanged.

## Settings

The `language` section in `config.json` supports:

```json
"language": {
  "defaultLocale": "en_US",
  "fallbackLocale": "en_US",
  "consoleLocale": "en_US",
  "detectClientLanguage": true
}
```

Player-facing translations live in `plugins/dds-manager/lang/`. The bundled
JSON packs are copied on first startup and missing keys are merged after
updates without replacing custom values. Run `/dds reload` to reload packs.

Keys and substitution variables are documented in `TRANSLATIONS.md`. Restricted
MiniMessage-style formatting is supported; untrusted variable values are escaped.

## Validation

Build / test: `./gradlew clean build`. Check with FR/EN clients: `/dds channel`,
`/dds whitelist check`, server-switch notices and clickable hover labels.

This pull request deliberately does NOT add offline-mode support or change
console confirmation tokens; these can be proposed in separate PRs.
