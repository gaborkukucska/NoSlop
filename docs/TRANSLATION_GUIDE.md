# Translate NoSlop to Your Language 🌍

NoSlop uses a simple, community-friendly JSON file system for translations. You don't need to be a developer to translate the app!

> [!IMPORTANT]
> **DEVELOPER NOTE:** ALWAYS add ALL new user-facing text strings to `content_en.json` (and `content_hu.json` as a baseline) as you go during development! Use the `.tr` extension in Kotlin Compose files (`"My String".tr`) to ensure the text routes through the `LanguageManager`.

If you want to add your language to NoSlop, follow these simple steps:

## Step 1: Duplicate the Base Language File
All languages are stored in the `app/src/main/assets/languages/` directory.
1. Find the base English file: `content_en.json`.
2. Duplicate it and rename the new file using your language's standard 2-letter code.
   * *Example: `content_es.json` for Spanish, `content_fr.json` for French, `content_pt.json` for Portuguese.*

## Step 2: Translate the File
Open your new `.json` file in any text editor. You will see a list of key-value pairs.

**Important Rule:** ONLY translate the text on the **right** side of the colon (`:`). The text on the left side is the "key" the app uses to find the text and must remain in English.

**Example (Translating to Spanish):**
    "System Settings": "Configuración del sistema",
    "App Language": "Idioma de la aplicación",
    "Welcome to NoSlop": "Bienvenido a NoSlop"

## Step 3: Register the Language in the UI
To make your new language show up in the app's dropdown menus, you just need to add it to two files:

1. Open `app/src/main/java/com/noslop/app/ui/OnboardingScreen.kt`
2. Open `app/src/main/java/com/noslop/app/ui/tabs/SettingsTab.kt`

Find the `val languages = listOf(...)` line in both files and add your language code and name to the list.
*Example: Adding Spanish:*
`val languages = listOf("en" to "English", "hu" to "Magyar", "es" to "Español")`

## Step 4: Test & Submit!
1. Build and run the app. Go to Settings -> Account & Preferences -> App Language.
2. Select your new language. The UI should instantly translate!
3. Submit a Pull Request (PR) to the main NoSlop repository with your new `.json` file and the updated Kotlin files.

*Thank you for helping make the mesh accessible to everyone!*
