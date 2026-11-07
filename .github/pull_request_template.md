## What this changes

<!-- One or two sentences. Link the issue if there is one. -->

## How you checked it

- [ ] `./gradlew testGithubDebugUnitTest testPlayDebugUnitTest --rerun-tasks` passes
- [ ] Tried on a device or emulator (say which, and what you did)

## Checklist

- [ ] New user-facing strings use `.tr` or `LanguageManager.translate` and are in `content_en.json` (and `content_hu.json`)
- [ ] No search terms, video ids or keys in logs (`Logger.redact`)
- [ ] Packet, signature, backup or Room schema changes are in `docs/WIRE_PROTOCOL_REFERENCE.md` and `docs/TECHNICAL_REFERENCE.md`
- [ ] `docs/PROJECT_STATUS.md` says what changed and what was not verified on a device
