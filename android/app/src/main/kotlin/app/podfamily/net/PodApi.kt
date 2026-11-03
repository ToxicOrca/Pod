package app.podfamily.net

import app.podfamily.crypto.PodCrypto
import app.podfamily.data.PodJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : IOException(message) {
    /** The server no longer knows this device (removed from family, or family deleted). */
    val deviceGone: Boolean get() = (code == 401 && message?.contains("not registered") == true) || code == 410
}

// --- DTOs (byte fields are standard base64, matching Go's []byte JSON encoding) ---

@Serializable data class KeysReq(val sign_pub: String, val box_pub: String)
@Serializable data class JoinReq(val family_id: String, val token: String, val sign_pub: String, val box_pub: String, val proof: String)
@Serializable data class RegisterResp(val family_id: String, val device_id: String)
@Serializable data class MemberDto(
    val device_id: String, val sign_pub: String, val box_pub: String, val role: String,
    val join_proof: String? = null, val created_at: Long = 0, val last_seen: Long = 0,
)
@Serializable data class FamilyResp(val family_id: String, val epoch: Long, val admin: String, val members: List<MemberDto>, val you: String)
@Serializable data class InviteReq(val token_hash: String, val ttl_seconds: Long)
@Serializable data class EnvelopeDto(val device_id: String, val data: String)
@Serializable data class PutEnvelopesReq(val epoch: Long, val envelopes: List<EnvelopeDto>)
@Serializable data class MyEnvelope(val epoch: Long, val data: String)
@Serializable data class EnvelopesResp(val current_epoch: Long, val envelopes: List<MyEnvelope>)
@Serializable data class PostMessageReq(val kind: String, val blob: String, val ttl_seconds: Long = 0)
@Serializable data class MessageDto(val id: Long, val sender: String, val kind: String, val blob: String, val created_at: Long)
@Serializable data class MessagesResp(val messages: List<MessageDto>, val cursor: Long, val more: Boolean = false)
@Serializable data class HistoryResp(val messages: List<MessageDto>)
@Serializable data class HealthResp(val ok: Boolean, val version: String = "", val protocol: Int = 0)
@Serializable private data class ErrorResp(val error: String = "")

/** Signs requests with the device key. */
class Signer(val deviceId: String?, val signSeed: ByteArray)

class PodApi(baseUrl: String) {
    val baseUrl: String = baseUrl.trimEnd('/')

    private val json = "application/json".toMediaType()

    private fun signedRequest(method: String, path: String, body: ByteArray?, signer: Signer?, extra: Map<String, String> = emptyMap()): Request {
        val url = baseUrl + path
        val b = Request.Builder().url(url)
        val bytes = body ?: ByteArray(0)
        when (method) {
            "GET" -> b.get()
            "DELETE" -> b.delete()
            else -> b.method(method, bytes.toRequestBody(json))
        }
        if (signer != null) {
            val httpUrl = b.build().url
            val requestUri = httpUrl.encodedPath + (httpUrl.encodedQuery?.let { "?$it" } ?: "")
            val ts = (System.currentTimeMillis() / 1000).toString()
            val nonce = PodCrypto.b64url(PodCrypto.randomBytes(16))
            val sig = PodCrypto.sign(signer.signSeed, PodCrypto.canonicalRequest(method, requestUri, ts, nonce, bytes))
            b.header("Pod-Timestamp", ts).header("Pod-Nonce", nonce).header("Pod-Signature", PodCrypto.b64(sig))
            signer.deviceId?.let { b.header("Pod-Device", it) }
        }
        extra.forEach { (k, v) -> b.header(k, v) }
        return b.build()
    }

    private suspend fun call(
        method: String, path: String, body: ByteArray?, signer: Signer?,
        extra: Map<String, String> = emptyMap(), client: OkHttpClient = Http.client,
    ): String = withContext(Dispatchers.IO) {
        client.newCall(signedRequest(method, path, body, signer, extra)).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { PodJson.decodeFromString<ErrorResp>(text).error }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}"
                throw ApiException(resp.code, msg)
            }
            text
        }
    }

    private inline fun <reified T> body(v: T): ByteArray = PodJson.encodeToString(v).toByteArray()

    suspend fun health(): HealthResp =
        PodJson.decodeFromString(call("GET", "/healthz", null, null))

    suspend fun createFamily(setupToken: String, signPub: ByteArray, boxPub: ByteArray, signer: Signer): RegisterResp =
        PodJson.decodeFromString(
            call("POST", "/v1/families", body(KeysReq(PodCrypto.b64(signPub), PodCrypto.b64(boxPub))), signer,
                mapOf("Pod-Setup-Token" to setupToken))
        )

    suspend fun join(req: JoinReq, signer: Signer): RegisterResp =
        PodJson.decodeFromString(call("POST", "/v1/join", body(req), signer))

    suspend fun family(s: Signer): FamilyResp = PodJson.decodeFromString(call("GET", "/v1/family", null, s))

    suspend fun createInvite(s: Signer, tokenHash: String, ttlSeconds: Long) {
        call("POST", "/v1/invites", body(InviteReq(tokenHash, ttlSeconds)), s)
    }

    suspend fun removeMember(s: Signer, deviceId: String) {
        call("DELETE", "/v1/members/$deviceId", null, s)
    }

    suspend fun deleteFamily(s: Signer) {
        call("DELETE", "/v1/family", null, s)
    }

    suspend fun putEnvelopes(s: Signer, req: PutEnvelopesReq) {
        call("PUT", "/v1/envelopes", body(req), s)
    }

    suspend fun envelopes(s: Signer): EnvelopesResp = PodJson.decodeFromString(call("GET", "/v1/envelopes", null, s))

    suspend fun postMessage(s: Signer, kind: String, blob: ByteArray, ttlSeconds: Long = 0) {
        call("POST", "/v1/messages", body(PostMessageReq(kind, PodCrypto.b64(blob), ttlSeconds)), s)
    }

    suspend fun messages(s: Signer, after: Long, waitSeconds: Int): MessagesResp =
        PodJson.decodeFromString(
            call("GET", "/v1/messages?after=$after&wait=$waitSeconds", null, s,
                client = if (waitSeconds > 0) Http.longPollClient else Http.client)
        )

    suspend fun history(s: Signer, sender: String, kind: String, sinceSeconds: Long): HistoryResp =
        PodJson.decodeFromString(call("GET", "/v1/history?sender=$sender&kind=$kind&since=$sinceSeconds", null, s))

    suspend fun state(s: Signer): MessagesResp = PodJson.decodeFromString(call("GET", "/v1/state", null, s))
}

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Long-poll requests wait up to 85s on the server. */
    val longPollClient: OkHttpClient = client.newBuilder()
        .readTimeout(100, TimeUnit.SECONDS)
        .build()
}
