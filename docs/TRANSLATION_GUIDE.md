# Translate NoSlop to Your Language 🌍

NoSlop uses a simple, community-friendly JSON file system for translations. You don't need to be a developer to translate the app!

> [!IMPORTANT]
> **DEVELOPER NOTE:** ALWAYS add ALL new user-facing text strings to `content_en.json` (and `content_hu.json` as a baseline) as you go during development!
> 
> 1. **Compose UI**: Use the `.tr` extension in Kotlin Compose files (`"My String".tr`) to route text through `LanguageManager`.
> 2. **Non-Compose Code**: Use `LanguageManager.translate("My String")` for Toast messages, notifications, or background workers.
> 3. **Dynamic Parameters & Placeholders**: NEVER use Kotlin string templates directly inside `.tr` or `translate()` (e.g. avoid `"$count members".tr` or `"Error: ${e.message}".tr`), because variable evaluation alters the string key *before* dictionary lookup. Always use fixed key strings with `{placeholder}` tokens and append `.replace("{placeholder}", value)`:
>    - *Correct Compose UI example:* `"{count} members".tr.replace("{count}", memberCount.toString())`
>    - *Correct Toast / Notification example:* `LanguageManager.translate("Failed with code HTTP {code}").replace("{code}", code.toString())`
> 
> As of 2026-08-28, the English translation file contains **710+** translation keys. Run a diff against Hungarian (`content_hu.json`) to check for missing entries and ensure key parity.

If you want to add your language to NoSlop, follow these simple steps:

## Step 1: Duplicate the Base Language File
All languages are stored in the `app/src/main/assets/languages/` directory.
1. Find the base English file: `content_en.json`.
2. Duplicate it and rename the new file using your language's standard 2-letter code.
   * *Example: `content_es.json` for Spanish, `content_fr.json` for French, `content_pt.json` for Portuguese.*

## Step 2: Translate the File
Open your new `.json` file in any text editor. You will see a list of key-value pairs.

**Important Rules:**
1. **Keys stay in English:** ONLY translate the text on the **right** side of the colon (`:`). The text on the left side is the "key" the app uses to locate the translation and must remain in English.
2. **Keep Placeholders Intact:** If a value contains `{placeholder}` tokens (such as `{count}`, `{author}`, `{group}`, `{progress}`, `{error}`, or `{code}`), do not translate or alter the token inside `{}` so dynamic replacements work properly in your language.

**Example (Translating to Spanish):**
```json
"System Settings": "Configuración del sistema",
"App Language": "Idioma de la aplicación",
"Welcome to NoSlop": "Bienvenido a NoSlop",
"{count} members": "{count} miembros",
"Message from {author} in {group}": "Mensaje de {author} en {group}"
```

## Step 3: Register the Language in the UI
To make your new language show up in the app's dropdown menus, add your language code and display name to the `val languages = listOf(...)` list in two files:

1. `app/src/main/java/com/noslop/app/ui/OnboardingScreen.kt`
2. `app/src/main/java/com/noslop/app/ui/tabs/SettingsTab.kt`

*Example: Adding Spanish:*
```kotlin
val languages = listOf("en" to "English", "hu" to "Magyar", "es" to "Español")
```

## Step 4: Test & Submit!
1. Build and run the app. Go to **Settings -> Account & Preferences -> App Language**.
2. Select your new language. The UI should instantly translate!
3. Submit a Pull Request (PR) to the main NoSlop repository with your new `.json` file and updated Kotlin files.

*Thank you for helping make the mesh accessible to everyone!*
