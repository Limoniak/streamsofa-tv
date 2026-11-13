package dev.lumen.tv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.lumen.core.SecretStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chiffre les secrets du cœur Rust (jetons OAuth) en AES-256-GCM avec une clé qui ne quitte
 * jamais l'Android Keystore. Un fichier par secret : `IV (12 octets) || texte chiffré`.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val dir = File(context.noBackupFilesDir, "secrets").apply { mkdirs() }

    override fun load(key: String): ByteArray? {
        val file = fileFor(key)
        if (!file.exists()) return null
        val data = file.readBytes()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
        cipher.updateAAD(key.toByteArray())
        return cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES)
    }

    override fun save(key: String, blob: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        // Le nom du secret est authentifié : un fichier renommé ne se déchiffre pas.
        cipher.updateAAD(key.toByteArray())
        val data = cipher.iv + cipher.doFinal(blob)
        // Écriture atomique : un refresh de jeton interrompu ne doit pas corrompre l'ancien.
        val tmp = File(dir, "${fileFor(key).name}.tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(fileFor(key))) error("écriture du secret impossible")
    }

    override fun delete(key: String) {
        fileFor(key).delete()
    }

    private fun fileFor(key: String): File {
        require(key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            "nom de secret invalide"
        }
        return File(dir, key)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lumen_secrets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
