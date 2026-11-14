package com.noslop.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * NOSLOP_DB_ENCRYPTION_V1 (docs/TECHNICAL_REFERENCE.md §16.51): source rules that keep database
 * encryption wired in, and keep it from ever deleting a database it could not open.
 *
 * 1. `getDatabase()` gives Room the [DbEncryption] factory. Without it the app silently goes back to a
 *    plaintext database.
 * 2. The build has SQLCipher, with both androidx.sqlite artifacts on one version, and ProGuard keeps
 *    SQLCipher's classes (its native code finds them by name; a minified build fails without them).
 * 3. The encryption code never uses SQLCipher's ready-made Room helper. That helper leaves SQLCipher's
 *    default error handler in place, which deletes a database file it believes is corrupt.
 * 4. The encryption code never calls `deleteDatabase`, and every SQLCipher connection it opens names
 *    the handler that only logs.
 * 5. Only [DbKeyStore] knows the Keystore alias and the key file, and the key is never written to a log.
 * 6. `BackupManager.planLegacyRederivation`, which can be handed the live `mesh.db`, checks for an
 *    encrypted file before Android's own SQLite sees it. Android's default handler deletes a file it
 *    can't read as a database.
 */
class DbEncryptionArchitectureTest {

    private val appDir: File by lazy {
        val root = File(".").canonicalFile
        val dir = if (File(root, "src/main").exists()) root else File(root, "app")
        assertTrue("Could not locate the app module", File(dir, "src/main").exists())
        dir
    }

    private fun main(path: String) = File(appDir, "src/main/java/com/noslop/app/$path").readText()

    /** Source with comments removed, so a rule about code is not tripped by a sentence about it. */
    private fun code(path: String): String = main(path).lines()
        .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }
        .joinToString("\n")

    @Test
    fun getDatabase_givesRoomTheEncryptionFactory() {
        val source = code("data/NoSlopDatabase.kt")
        val start = source.indexOf("fun getDatabase(")
        assertTrue(start >= 0)
        val body = source.substring(start, source.indexOf("fun closeInstance(", start))
        assertTrue(
            "getDatabase() must pass DbEncryption.openHelperFactory(...) to Room",
            Regex("""\.openHelperFactory\(\s*DbEncryption\.openHelperFactory\(""").containsMatchIn(body)
        )
        assertTrue("mesh.db stays in write-ahead mode", body.contains("JournalMode.WRITE_AHEAD_LOGGING"))
    }

    @Test
    fun build_hasSqlcipher_andOneAndroidxSqliteVersion() {
        val build = File(appDir, "build.gradle.kts").readText()
        assertTrue(
            "SQLCipher dependency missing",
            Regex(""""net\.zetetic:sqlcipher-android:[0-9.]+@aar"""").containsMatchIn(build)
        )
        val versions = Regex(""""androidx\.sqlite:(sqlite|sqlite-framework):([0-9][0-9A-Za-z.\-]*)"""")
            .findAll(build).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(
            "both androidx.sqlite artifacts must be named: $versions",
            setOf("sqlite", "sqlite-framework"), versions.map { it.first }.toSet()
        )
        assertEquals("androidx.sqlite artifacts must share one version: $versions", 1, versions.map { it.second }.toSet().size)
    }

    @Test
    fun proguard_keepsSqlcipher() {
        val rules = File(appDir, "proguard-rules.pro").readLines().map { it.trim() }
        assertTrue(rules.any { it.startsWith("-keep") && it.contains("class net.zetetic.database.**") })
        assertTrue(rules.any { it.startsWith("-keepclasseswithmembernames class net.zetetic.database.**") })
    }

    @Test
    fun encryptionCode_neverUsesSqlciphersOwnRoomHelper() {
        val source = code("data/DbEncryption.kt")
        for (name in listOf("SupportOpenHelperFactory", "SupportHelper", "DefaultDatabaseErrorHandler")) {
            assertFalse(
                "$name leaves SQLCipher's file-deleting error handler in place",
                Regex("""\b$name\b""").containsMatchIn(source)
            )
        }
    }

    @Test
    fun encryptionCode_neverDeletesADatabase_andAlwaysNamesItsOwnErrorHandler() {
        val source = code("data/DbEncryption.kt")
        assertFalse(Regex("""\bdeleteDatabase\s*\(""").containsMatchIn(source))

        // Every SQLCipher connection: SQLiteDatabase.openDatabase(...) and the SQLiteOpenHelper constructor.
        val opens = Regex("""SQLiteDatabase\.openDatabase\(([^;]*?)\)\s*\n""", RegexOption.DOT_MATCHES_ALL)
            .findAll(source).map { it.groupValues[1] }.toList()
        assertEquals("expected the export, the verify and the read connection", 3, opens.size)
        for (args in opens) {
            assertTrue("openDatabase without the logging-only handler: $args", args.contains("neverDelete"))
        }
        assertTrue(
            "the Room helper must be given an error handler",
            Regex("""object\s*:\s*SQLiteOpenHelper\([^)]*errorHandler[^)]*\)""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(source)
        )
        assertTrue(
            "the helper is built with the logging-only handler",
            Regex("""CipherOpenHelper\([^)]*neverDelete\s*\)""").containsMatchIn(source)
        )
    }

    @Test
    fun exportConnection_mayCreateTheCopy() {
        // ATTACH creates the encrypted copy with the flags of the connection it runs on. Without the
        // create flag every conversion fails with error 14 (first device run, 2026-11-13).
        val source = code("data/DbEncryption.kt")
        val start = source.indexOf("override fun export(")
        assertTrue(start >= 0)
        val body = source.substring(start, source.indexOf("override fun verify(", start))
        val attach = body.indexOf("ATTACH DATABASE")
        assertTrue(attach > 0)
        val open = body.substring(0, attach)
        assertTrue(
            "the export connection must be opened with CREATE_IF_NECESSARY",
            open.contains("SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY")
        )
        assertTrue(
            "and must first check that the original is there, so the flag can't create an empty one",
            open.indexOf("source.isFile") in 0 until open.indexOf("SQLiteDatabase.openDatabase(")
        )
    }

    @Test
    fun exportNeverAttachesWithANullKey() {
        // A NULL key in ATTACH means "use the main database's key": a copy meant to be plaintext, or to be
        // under another key, would silently be written under the source's key instead.
        val source = code("data/DbEncryption.kt")
        val attach = Regex("""ATTACH DATABASE \? AS target_db KEY \?", arrayOf<Any>\(([^\n]*)\)\)""").find(source)
        assertTrue("the ATTACH call was not found", attach != null)
        assertTrue(
            "the key argument must fall back to an empty string, never to null",
            attach!!.groupValues[1].contains("targetPassword ?: \"\"")
        )
    }

    @Test
    fun legacyRederivation_checksForAnEncryptedFile_beforeAndroidSqliteOpensIt() {
        val source = code("data/BackupManager.kt")
        val start = source.indexOf("fun planLegacyRederivation(")
        assertTrue(start >= 0)
        val open = source.indexOf("SQLiteDatabase.openDatabase(", start)
        assertTrue(open > start)
        val before = source.substring(start, open)
        assertTrue(
            "planLegacyRederivation must refuse an encrypted file before opening it",
            before.contains("DbFiles(dbFile).state()") && before.contains("FileState.ENCRYPTED")
        )
    }

    @Test
    fun backup_carriesTheEncryptedDatabaseWithItsKey_andARestoreNeverStagesTheKey() {
        val source = code("data/BackupManager.kt")
        val exportStart = source.indexOf("fun exportData(")
        val exportEnd = source.indexOf("fun importData(", exportStart)
        assertTrue(exportStart >= 0 && exportEnd > exportStart)

        val export = source.substring(exportStart, exportEnd)
        assertTrue(export.contains("addToZip(zos, dbFile, BackupDbStage.ENTRY_ENCRYPTED)"))
        assertTrue(export.contains("ZipEntry(BackupDbStage.ENTRY_KEY)"))
        assertTrue(
            "an encrypted database is never put in an archive before its key was read",
            export.indexOf("DbKeyStore.load(context, mayCreate = false)") in 0 until export.indexOf("BackupDbStage.ENTRY_ENCRYPTED")
        )
        assertFalse(
            "the database key goes straight into the archive stream, never into a file",
            Regex("""File\([^)]*ENTRY_KEY""").containsMatchIn(source)
        )

        val restore = source.substring(exportEnd)
        assertTrue(restore.contains("readSmallEntry(zis, DbEncryptionPolicy.KEY_BYTES)"))
        assertFalse(Regex("""restoreFile\([^)]*ENTRY_KEY""").containsMatchIn(restore))
        val prepare = restore.indexOf("BackupDbStage.prepare(")
        assertTrue("the staged database is prepared before the commit", prepare in 0 until restore.indexOf("commitStarted = true"))
        assertTrue("the archive's key is zeroed when the restore ends", restore.contains("DbKeyStore.wipe(stagedDbKey)"))
    }

    @Test
    fun aDatabaseThatCantBeOpened_givesAStandIn_notAnException() {
        // The app queries the database from process start; an exception there is a crash on every launch.
        val source = code("data/DbEncryption.kt")
        val start = source.indexOf("private fun open(): SupportSQLiteOpenHelper {")
        val end = source.indexOf("private fun openStandIn(", start)
        assertTrue(start >= 0 && end > start)
        val open = source.substring(start, end)
        assertFalse("open() must not throw; it answers with the stand-in", Regex("""\bthrow\b""").containsMatchIn(open))
        assertEquals(2, Regex("""return openStandIn\(""").findAll(open).count())

        val standIn = source.substring(end, source.indexOf("private fun openPlaintext(", end))
        assertTrue("the stand-in is in memory: it has no file name", standIn.contains(".name(null)"))
        assertTrue(standIn.contains("DbEncryption.State.LOCKED"))
    }

    @Test
    fun torIsNotStarted_onTheStandIn() {
        // On the empty stand-in a message could arrive, be acknowledged and be lost.
        val appDirJava = File(appDir, "src/main/java/com/noslop/app")
        val starters = appDirJava.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { it.readText().contains("TorService.startTor(") }
            .toList()
        assertEquals(listOf("NoSlopApp.kt", "NoSlopViewModel.kt"), starters.map { it.name }.sorted())
        for (file in starters) {
            val text = file.readText()
            var at = text.indexOf("TorService.startTor(")
            while (at >= 0) {
                val before = text.substring(maxOf(0, at - 1500), at)
                assertTrue(
                    "${file.name}: TorService.startTor must come after a DbEncryption.isLocked() check",
                    before.contains("if (com.noslop.app.data.DbEncryption.isLocked()) return@launch")
                )
                at = text.indexOf("TorService.startTor(", at + 1)
            }
        }
    }

    @Test
    fun theScreen_waitsForTheDatabase_beforeItDecidesOnboardedOrNot() {
        val activity = main("MainActivity.kt")
        val gate = activity.indexOf("val dbStatus by com.noslop.app.data.DbEncryption.status.collectAsState()")
        val onboarded = activity.indexOf("val isOnboarded by viewModel.isOnboardingComplete.collectAsState()")
        assertTrue("the database gate must come before the onboarding decision", gate in 0 until onboarded)
        val between = activity.substring(gate, onboarded)
        assertTrue(between.contains("LockedDatabaseScreen(viewModel, locked)"))
        assertTrue(between.contains("DbEncryption.State.NOT_OPENED"))
        assertTrue(between.contains("DbEncryption.State.CONVERTING"))
        assertEquals("both the locked screen and the splash end the composition there", 2, Regex("""return@MyApplicationTheme""").findAll(between).count())
    }

    @Test
    fun startOver_renamesTheDatabase_andNeverDeletesIt() {
        val source = code("data/DbEncryption.kt")
        val start = source.indexOf("fun setAsideUnreadableDatabase(")
        val body = source.substring(start, source.indexOf("internal fun describe(", start))
        assertTrue(body.contains("DbFiles.moveWithSidecars(main, File(main.path + SET_ASIDE_SUFFIX))"))
        assertFalse(Regex("""\.delete\(\)|deleteWithSidecars|deleteRecursively""").containsMatchIn(body))
        // And nothing cleans the set-aside copy up later.
        assertEquals(1, Regex("""SET_ASIDE_SUFFIX\b""").findAll(source.substring(start)).count())
    }

    @Test
    fun onlyTheKeyStore_knowsTheAliasAndTheKeyFile() {
        val dataDir = File(appDir, "src/main/java/com/noslop/app")
        val offenders = dataDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") && it.name != "DbKeyStore.kt" }
            .filter { f -> f.readText().let { it.contains("noslop_db_key_v1") || it.contains("\"db_key.bin\"") } }
            .map { it.name }.toList()
        assertEquals("only DbKeyStore may name the database key's alias or file", emptyList<String>(), offenders)
    }

    @Test
    fun keyMaterial_isNeverLogged() {
        val logCall = Regex("""Logger\.(debug|info|warn|error)\s*\((.*)""")
        val keyNames = Regex("""\$\{?\s*(key|rawKey|raw|password|readBack|blob)\b""")
        val violations = ArrayList<String>()
        for (file in listOf("data/DbKeyStore.kt", "data/DbEncryption.kt")) {
            main(file).lines().forEachIndexed { i, line ->
                val call = logCall.find(line) ?: return@forEachIndexed
                if (keyNames.containsMatchIn(call.groupValues[2])) violations += "$file:${i + 1}: ${line.trim()}"
            }
        }
        assertEquals("a log line interpolates key material", emptyList<String>(), violations)
    }
}
