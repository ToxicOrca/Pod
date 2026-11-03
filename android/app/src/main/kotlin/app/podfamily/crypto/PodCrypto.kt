package app.podfamily.crypto

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import com.google.crypto.tink.subtle.XChaCha20Poly1305
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * All of Pod's cryptography, built only from standard primitives (via Google Tink):
 *  - Ed25519 for signatures (device identity, request auth, message authorship)
 *  - X25519 + HKDF-SHA256 for wrapping the family key to each device
 *  - XChaCha20-Poly1305 for encrypting messages with the family key
 *  - HMAC-SHA256 for the in-person invite proof
 *
 * The wire formats are specified in docs/PROTOCOL.md. Keep the two in sync.
 */
object PodCrypto {
    private val random = SecureRandom()
    private val b64url = Base64.getUrlEncoder().withoutPadding()
    private val b64urlDec = Base64.getUrlDecoder()
    private val b64 = Base64.getEncoder()
    private val b64Dec = Base64.getDecoder()

    const val ENVELOPE_VERSION: Byte = 1
    const val MESSAGE_VERSION: Byte = 1
    private const val SIG_LEN = 64
    private const val KEY_LEN = 32

    class DeviceKeys(
        val signSeed: ByteArray, // Ed25519 private key (32-byte seed)
        val signPub: ByteArray,
        val boxPriv: ByteArray,  // X25519 private key
        val boxPub: ByteArray,
    ) {
        val deviceId: String get() = deviceId(signPub)
    }

    fun newDeviceKeys(): DeviceKeys {
        val kp = Ed25519Sign.KeyPair.newKeyPair()
        val boxPriv = X25519.generatePrivateKey()
        return DeviceKeys(kp.privateKey, kp.publicKey, boxPriv, X25519.publicFromPrivate(boxPriv))
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun newFamilyKey(): ByteArray = randomBytes(KEY_LEN)

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** Device id = base64url(SHA-256(signPub)[0..16]). Matches the server's derivation. */
    fun deviceId(signPub: ByteArray): String = b64url.encodeToString(sha256(signPub).copyOf(16))

    fun sign(seed: ByteArray, data: ByteArray): ByteArray = Ed25519Sign(seed).sign(data)

    fun verify(pub: ByteArray, sig: ByteArray, data: ByteArray): Boolean = try {
        Ed25519Verify(pub).verify(sig, data); true
    } catch (e: GeneralSecurityException) {
        false
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    // --- encoding helpers ---------------------------------------------------------
    fun b64(data: ByteArray): String = b64.encodeToString(data)
    fun unb64(s: String): ByteArray = b64Dec.decode(s)
    fun b64url(data: ByteArray): String = b64url.encodeToString(data)
    fun unb64url(s: String): ByteArray = b64urlDec.decode(s)
    fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(it) }

    // --- invite proof ---------------------------------------------------------------

    /** Proves the joiner scanned the admin's QR code in person (the server never sees the secret). */
    fun joinProof(inviteSecret: ByteArray, familyId: String, signPub: ByteArray, boxPub: ByteArray): ByteArray =
        hmacSha256(inviteSecret, "pod-join-v1|$familyId|".toByteArray() + signPub + boxPub)

    fun verifyJoinProof(inviteSecret: ByteArray, familyId: String, signPub: ByteArray, boxPub: ByteArray, proof: ByteArray): Boolean =
        MessageDigest.isEqual(joinProof(inviteSecret, familyId, signPub, boxPub), proof)

    // --- family key envelopes ----------------------------------------------------------

    private fun envelopeAad(familyId: String, epoch: Long, recipientId: String) =
        "pod-env-v1|$familyId|$epoch|$recipientId".toByteArray()

    private fun envelopeKey(shared: ByteArray, ephPub: ByteArray, recipientPub: ByteArray): ByteArray =
        Hkdf.computeHkdf("HMACSHA256", shared, ephPub + recipientPub, "pod/envelope/v1".toByteArray(), KEY_LEN)

    /**
     * Wraps the family key for one recipient device and signs it with the admin's key.
     * Format: version(1) || sig(64) || ephPub(32) || xchacha(nonce||ct||tag)
     */
    fun sealEnvelope(
        familyKey: ByteArray, epoch: Long, familyId: String,
        recipientId: String, recipientBoxPub: ByteArray, adminSignSeed: ByteArray,
    ): ByteArray {
        val ephPriv = X25519.generatePrivateKey()
        val ephPub = X25519.publicFromPrivate(ephPriv)
        val shared = X25519.computeSharedSecret(ephPriv, recipientBoxPub)
        val aad = envelopeAad(familyId, epoch, recipientId)
        val ct = XChaCha20Poly1305(envelopeKey(shared, ephPub, recipientBoxPub)).encrypt(familyKey, aad)
        val body = ephPub + ct
        val sig = sign(adminSignSeed, aad + body)
        return byteArrayOf(ENVELOPE_VERSION) + sig + body
    }

    /** Opens an envelope addressed to this device. Throws if it was not signed by the admin. */
    fun openEnvelope(
        data: ByteArray, epoch: Long, familyId: String,
        myDeviceId: String, myBoxPriv: ByteArray, myBoxPub: ByteArray, adminSignPub: ByteArray,
    ): ByteArray {
        if (data.size < 1 + SIG_LEN + 32 + 40 || data[0] != ENVELOPE_VERSION) throw GeneralSecurityException("bad envelope")
        val sig = data.copyOfRange(1, 1 + SIG_LEN)
        val body = data.copyOfRange(1 + SIG_LEN, data.size)
        val aad = envelopeAad(familyId, epoch, myDeviceId)
        if (!verify(adminSignPub, sig, aad + body)) throw GeneralSecurityException("envelope not signed by family admin")
        val ephPub = body.copyOfRange(0, 32)
        val ct = body.copyOfRange(32, body.size)
        val shared = X25519.computeSharedSecret(myBoxPriv, ephPub)
        val key = XChaCha20Poly1305(envelopeKey(shared, ephPub, myBoxPub)).decrypt(ct, aad)
        if (key.size != KEY_LEN) throw GeneralSecurityException("bad family key")
        return key
    }

    // --- messages ------------------------------------------------------------------------

    private fun messageAad(familyId: String, epoch: Long, senderId: String, kind: String) =
        "pod-msg-v1|$familyId|$epoch|$senderId|$kind".toByteArray()

    /**
     * Pads plaintext to a fixed bucket size so the server cannot tell message
     * types or contents apart by length: len(4, big-endian) || data || zeros.
     */
    fun pad(data: ByteArray): ByteArray {
        val needed = data.size + 4
        val bucket = listOf(256, 512, 1024, 2048, 4096).firstOrNull { it >= needed }
            ?: (((needed + 4095) / 4096) * 4096)
        return ByteBuffer.allocate(bucket).putInt(data.size).put(data).array()
    }

    fun unpad(padded: ByteArray): ByteArray {
        if (padded.size < 4) throw GeneralSecurityException("bad padding")
        val len = ByteBuffer.wrap(padded, 0, 4).int
        if (len < 0 || len > padded.size - 4) throw GeneralSecurityException("bad padding")
        return padded.copyOfRange(4, 4 + len)
    }

    /**
     * Encrypts and signs a message for the family.
     * Format: version(1) || epoch(4, big-endian) || sig(64) || xchacha(nonce||ct||tag)
     * sig = Ed25519(sender, aad || ciphertext) so members can't impersonate each other.
     */
    fun sealMessage(
        plaintext: ByteArray, kind: String, familyId: String, epoch: Long,
        familyKey: ByteArray, senderId: String, senderSignSeed: ByteArray,
    ): ByteArray {
        require(epoch in 1..0xFFFFFFFFL) { "epoch out of range" }
        val aad = messageAad(familyId, epoch, senderId, kind)
        val ct = XChaCha20Poly1305(familyKey).encrypt(pad(plaintext), aad)
        val sig = sign(senderSignSeed, aad + ct)
        return ByteBuffer.allocate(1 + 4 + SIG_LEN + ct.size)
            .put(MESSAGE_VERSION).putInt(epoch.toInt()).put(sig).put(ct).array()
    }

    fun messageEpoch(blob: ByteArray): Long? {
        if (blob.size < 5 || blob[0] != MESSAGE_VERSION) return null
        // Unsigned 32-bit, matching PROTOCOL.md (epoch u32).
        return ByteBuffer.wrap(blob, 1, 4).int.toLong() and 0xFFFFFFFFL
    }

    /** Verifies and decrypts a message. Returns null if it fails any check. */
    fun openMessage(
        blob: ByteArray, kind: String, familyId: String,
        senderId: String, senderSignPub: ByteArray, keyForEpoch: (Long) -> ByteArray?,
    ): ByteArray? {
        val epoch = messageEpoch(blob) ?: return null
        if (blob.size < 1 + 4 + SIG_LEN + 40) return null
        val key = keyForEpoch(epoch) ?: return null
        val sig = blob.copyOfRange(5, 5 + SIG_LEN)
        val ct = blob.copyOfRange(5 + SIG_LEN, blob.size)
        val aad = messageAad(familyId, epoch, senderId, kind)
        if (!verify(senderSignPub, sig, aad + ct)) return null
        return try {
            unpad(XChaCha20Poly1305(key).decrypt(ct, aad))
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    // --- request signing -------------------------------------------------------------------

    /** Canonical string signed for every API request. Must match server/auth.go. */
    fun canonicalRequest(method: String, requestUri: String, timestamp: String, nonce: String, body: ByteArray): ByteArray =
        "POD-AUTH-1\n$method\n$requestUri\n$timestamp\n$nonce\n${hex(sha256(body))}".toByteArray()
}
