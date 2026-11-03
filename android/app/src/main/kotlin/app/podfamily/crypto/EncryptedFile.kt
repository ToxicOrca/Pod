package app.podfamily.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A small file encrypted with an AES-256-GCM key that lives in the Android
 * Keystore (hardware-backed on most phones), written atomically so a crash or
 * power loss mid-write can never corrupt it.
 *
 * Used for the device's private keys, the family keys and the cached family
 * state, so nothing sensitive sits on disk in plaintext.
 */
class EncryptedFile(private val file: File, private val keyAlias: String = "pod_storage_v1") {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Must stay usable from the background service while the phone is locked.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return gen.generateKey()
    }

    fun exists(): Boolean = file.exists()

    @Synchronized
    fun read(): ByteArray? {
        if (!file.exists()) return null
        val data = file.readBytes()
        if (data.size < 13) return null
        val iv = data.copyOfRange(0, 12)
        val ct = data.copyOfRange(12, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    @Synchronized
    fun write(plaintext: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plaintext)
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(out)
            fos.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("could not replace ${file.name}")
        }
    }

    @Synchronized
    fun delete() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }
}
