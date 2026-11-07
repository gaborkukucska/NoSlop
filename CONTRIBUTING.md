# Contributing to NoSlop

Thanks for looking. NoSlop is an alpha-stage Android app (with an iOS and desktop port growing in `mvp/`), built by one person, so small and focused contributions are the easiest to review.

Much of the code was written with the help of several leading LLMs (see "How NoSlop Is Built" in the README). NoSlop stays alpha until qualified developers have contributed enough, so human review is the most valuable contribution there is: cryptography and protocol review, Kotlin and Android code review, security testing, and tests. If you use an AI tool for your own contribution, that's fine: say so in the pull request, and test what you submit.

## The easiest ways to help

* **Translate it.** No code needed: copy `app/src/main/assets/languages/content_en.json`, translate the right-hand side, and open a pull request. See [docs/TRANSLATION_GUIDE.md](docs/TRANSLATION_GUIDE.md). Fixing a typo or an awkward phrase in an existing language is just as welcome.
* **Report a bug.** Use the bug report form. Say which build you have (GitHub APK, Play build, or from source), your phone and Android version, and whether *Route Clearnet via Tor* was on. [docs/SUPPORT.md](docs/SUPPORT.md) section 7 shows how to copy the in-app logs.
* **Report a security problem privately.** Not in a public issue. See [SECURITY.md](SECURITY.md).
* **Try it and tell me what is confusing.** Onboarding, the Word Cloud backup, and connecting to a first peer are the parts where fresh eyes help most.

## Before you open a bug report

Logs can contain onion addresses and peer keys. Read them before pasting them into a public issue and remove anything you don't want shared.

## Code changes

1. **Build it.** Follow [docs/BUILD.md](docs/BUILD.md). There are two flavours, `github` (has the in-app update check) and `play` (does not).
2. **Run the unit tests** before you open a pull request, as BUILD.md section 8.1 describes:
   ```bash
   ./gradlew testGithubDebugUnitTest testPlayDebugUnitTest --rerun-tasks
   ```
3. **Keep it focused.** One change per pull request, with a note on how you checked it (a test, a device, or both).

### Rules the project holds to

* **No trackers, no analytics SDKs, no accounts, no algorithmic ranking.** A change that needs one won't fit.
* **User-facing strings** go through the translation system: use `.tr` in Compose and `LanguageManager.translate(...)` elsewhere, and add the string to `content_en.json` (and `content_hu.json`). Never put a Kotlin string template inside `.tr`. Use `{placeholder}` tokens and `.replace(...)`. The details are in the translation guide.
* **Logs must not carry what people search for or watch.** Use `Logger.redact(...)` for search terms, video ids and similar. The architecture tests (for example `CodeHygieneArchitectureTest`) fail on some of these patterns, such as `TypeToken` and unlisted silent catches.
* **Network traffic respects the Tor setting.** Don't add a request that bypasses `HttpClientProvider`.
* **Protocol and format changes are documented.** If you change a packet, a signature, the backup format or the Room schema, update [docs/WIRE_PROTOCOL_REFERENCE.md](docs/WIRE_PROTOCOL_REFERENCE.md) and [docs/TECHNICAL_REFERENCE.md](docs/TECHNICAL_REFERENCE.md), and expect questions: peers on older versions have to keep working or the break has to be deliberate.
* **Record the change** in [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md): what changed, what was tested, and what was *not* verified on a device. That file is the single source of truth for what is done and what is open.

### The `mvp/` port

The iOS and desktop work under `mvp/` has its own rules. Read [docs/migration/README.md](docs/migration/README.md) first.

## Licence

By contributing you agree that your contribution is released under the licence in [LICENSE.md](LICENSE.md).
