package app.podfamily.crypto

import com.google.crypto.tink.subtle.XChaCha20Poly1305
import org.json.JSONObject
import java.security.GeneralSecurityException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Passphrase-encrypted recovery kit.
 *
 * File (JSON): {"format":"pod-recovery","v":1,"kdf":"pbkdf2-sha256","iter":N,"salt":b64,"data":b64}
 * key  = PBKDF2-HMAC-SHA256(passphrase, salt, N, 32 bytes)
 * data = XChaCha20-Poly1305(key).encrypt(payload, aad = "pod-recovery-v1")
 *
 * Without the passphrase the file is useless. With both, whoever holds them
 * can act as this phone, so keep the passphrase private.
 */
object RecoveryKit {
    const val DEFAULT_ITERATIONS = 600_000
    const val MIN_PASSPHRASE = 10
    private val AAD = "pod-recovery-v1".toByteArray()

    class WrongPassphrase : GeneralSecurityException("Wrong passphrase, or the file is damaged")

    private fun derive(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(payload: ByteArray, passphrase: String, iterations: Int = DEFAULT_ITERATIONS): String {
        require(passphrase.length >= MIN_PASSPHRASE) { "Passphrase must be at least $MIN_PASSPHRASE characters" }
        val salt = PodCrypto.randomBytes(16)
        val key = derive(passphrase, salt, iterations)
        val data = XChaCha20Poly1305(key).encrypt(payload, AAD)
        return JSONObject()
            .put("format", "pod-recovery").put("v", 1)
            .put("kdf", "pbkdf2-sha256").put("iter", iterations)
            .put("salt", PodCrypto.b64(salt)).put("data", PodCrypto.b64(data))
            .toString(2)
    }

    fun decrypt(file: String, passphrase: String): ByteArray {
        val o = try { JSONObject(file) } catch (e: Exception) { throw GeneralSecurityException("This isn't a Pod recovery kit") }
        if (o.optString("format") != "pod-recovery" || o.optInt("v") != 1 || o.optString("kdf") != "pbkdf2-sha256") {
            throw GeneralSecurityException("This isn't a Pod recovery kit (or it's from a newer version)")
        }
        val iterations = o.getInt("iter")
        if (iterations < 100_000 || iterations > 10_000_000) throw GeneralSecurityException("Invalid recovery kit")
        val key = derive(passphrase, PodCrypto.unb64(o.getString("salt")), iterations)
        return try {
            XChaCha20Poly1305(key).decrypt(PodCrypto.unb64(o.getString("data")), AAD)
        } catch (e: GeneralSecurityException) {
            throw WrongPassphrase()
        }
    }
}
