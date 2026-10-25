// FILE: app/src/main/java/com/noslop/app/data/WordCloudGuard.kt
package com.noslop.app.data

import com.noslop.app.crypto.CryptoService
import com.noslop.app.crypto.MnemonicGenerator

/**
 * R6: the rule for the Word Cloud an account keeps on the device ("nothing changes the Word Cloud").
 *
 *  - It is written when the account is created (IdentityRepository.saveIdentity, called only from
 *    onboarding) and never changed afterwards.
 *  - A restore brings back the phrase the backed-up account itself held (the archive's identity JSON,
 *    or the archived identity prefs file of an old archive). It never substitutes the words typed to
 *    open the backup: those only decrypt the file and can differ from the account's phrase.
 *  - The typed words are kept only when the archive carries no phrase AND they derive the restored
 *    account's keys, i.e. they provably are that account's phrase. Otherwise nothing is stored.
 *  - An archive phrase that does not derive the keys is still kept unchanged (a version 1 account's keys
 *    are random, so no phrase ever derives them); for a version 2 account it is logged as a warning.
 *
 * WordCloudWritePathsTest fails if any other code path writes the stored phrase. Words are never logged.
 */
object WordCloudGuard {

    sealed class Decision {
        /** Store [phrase]. [derivesKeys] says whether it provably belongs to the restored keys. */
        data class Keep(val phrase: String, val source: String, val derivesKeys: Boolean) : Decision()
        /** Store no phrase; [reason] goes to the log. */
        data class StoreNothing(val reason: String) : Decision()
    }

    /** True when [phrase] (canonical form) derives the Ed25519 public key [publicKeyB64]. */
    fun phraseDerivesPublicKey(phrase: String?, publicKeyB64: String): Boolean {
        if (phrase.isNullOrBlank()) return false
        return try {
            val derived = CryptoService.deriveIdentityFromSeed(
                MnemonicGenerator.deriveSeed(MnemonicGenerator.normalize(phrase)), "wordcloud-check"
            ).publicKeyB64
            val a = android.util.Base64.decode(derived, android.util.Base64.DEFAULT)
            val b = android.util.Base64.decode(publicKeyB64, android.util.Base64.DEFAULT)
            a.contentEquals(b)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Decides which phrase a restored identity keeps. [archivePhrase] is the `mnemonic` the archive's
     * identity JSON carries (null when absent), [typedPhrase] the words typed to open the backup.
     */
    fun decideRestored(archivePhrase: String?, typedPhrase: String, publicKeyB64: String): Decision {
        val fromArchive = archivePhrase?.takeIf { it.isNotBlank() && it != "null" }
        if (fromArchive != null) {
            return Decision.Keep(fromArchive, "archive", phraseDerivesPublicKey(fromArchive, publicKeyB64))
        }
        val typed = MnemonicGenerator.normalize(typedPhrase)
        return if (phraseDerivesPublicKey(typed, publicKeyB64)) {
            Decision.Keep(typed, "typed words derive the restored keys", derivesKeys = true)
        } else {
            Decision.StoreNothing("the backup carries no Word Cloud and the typed words do not derive the restored keys")
        }
    }
}
