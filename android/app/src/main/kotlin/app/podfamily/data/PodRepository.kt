package app.podfamily.data

import android.content.Context
import app.podfamily.BuildConfig
import app.podfamily.crypto.EncryptedFile
import app.podfamily.crypto.PodCrypto
import app.podfamily.location.Geofence
import app.podfamily.net.ApiException
import app.podfamily.net.EnvelopeDto
import app.podfamily.net.FamilyResp
import app.podfamily.net.JoinReq
import app.podfamily.net.MessageDto
import app.podfamily.net.PodApi
import app.podfamily.net.PutEnvelopesReq
import app.podfamily.net.Signer
import app.podfamily.system.DiagLog
import app.podfamily.system.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

/** Live connection status shown in the UI (not persisted). */
data class ConnStatus(
    val online: Boolean = false,
    val lastSyncAt: Long = 0,
    val lastUploadAt: Long = 0,
    val error: String? = null,
)

object Kinds {
    const val LOC = "loc"
    const val EVENT = "event"
    const val PROFILE = "profile"
    const val PLACES = "places"
    const val SYS = "sys"
}

/**
 * The single source of truth for the app: identity, family, members, places.
 * Owns all network + crypto operations. UI observes [state] and [conn].
 */
class PodRepository private constructor(private val ctx: Context) {

    companion object {
        @Volatile private var instance: PodRepository? = null
        fun get(context: Context): PodRepository =
            instance ?: synchronized(this) {
                instance ?: PodRepository(context.applicationContext).also { instance = it }
            }

        private const val POLL_WAIT_SECONDS = 85
        /** Location/battery data older than this is shown but never alerts. */
        private const val LIVE_WINDOW_MS = 15 * 60_000L
        /** Events (arrive/leave/SOS) delivered late still alert up to this age, with their time shown. */
        private const val EVENT_ALERT_WINDOW_MS = 2 * 60 * 60_000L
        private const val LOW_BATTERY = 15
        /** After a newer family key exists, older-epoch messages are refused this long after it arrived. */
        private const val OLD_EPOCH_GRACE_MS = 24 * 60 * 60_000L
        /** Accept data timestamped at most this far in the future (clock differences). */
        private const val FUTURE_SKEW_MS = 10 * 60_000L
        private const val MAX_SEEN_EVENTS = 300
    }

    private val secretsFile = EncryptedFile(File(ctx.filesDir, "secrets.bin"))
    private val stateFile = EncryptedFile(File(ctx.filesDir, "state.bin"))
    private val lock = Any()
    private val keyOps = Mutex()

    @Volatile private var secrets: Secrets? = null
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()
    private val _conn = MutableStateFlow(ConnStatus())
    val conn: StateFlow<ConnStatus> = _conn.asStateFlow()

    // Upload throttling / retry (memory only). Queued events live in AppState.pendingEvents.
    @Volatile private var lastUploaded: LocPayload? = null
    @Volatile private var pendingLoc: LocPayload? = null
    /** Only one upload pass at a time, so an event is never sent twice or dropped. */
    private val flushLock = Mutex()
    /** Per sync batch: refresh the member list / keys at most once, not once per message. */
    private var batchFamilyRefreshed = false
    private var batchEnvelopesRefreshed = false

    init {
        load()
    }

    // ------------------------------------------------------------------------------------
    // Screenshot demo (debug builds only, started by DemoActivity in src/debug)
    // ------------------------------------------------------------------------------------

    /** Showing made-up demo data: kept in memory only, never saved or synced (no keys = not in a family). */
    @Volatile private var demo = false
    private var demoTrails: Map<String, List<LocPayload>> = emptyMap()

    fun startDemo(state: AppState, trails: Map<String, List<LocPayload>>) {
        check(BuildConfig.DEBUG) { "demo data is for debug builds only" }
        synchronized(lock) {
            demo = true
            secrets = null
            demoTrails = trails
            _state.value = state
        }
        setConn { ConnStatus(online = true, lastSyncAt = System.currentTimeMillis(), lastUploadAt = System.currentTimeMillis()) }
    }

    // ------------------------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------------------------

    private fun load() {
        val s = try {
            secretsFile.read()?.let { PodJson.decodeFromString<Secrets>(it.decodeToString()) }
        } catch (e: Exception) {
            // Keystore problems are rare but do happen on some devices; the recovery kit covers this.
            DiagLog.e("Could not read stored keys: ${e.javaClass.simpleName}: ${e.message}")
            preserveUnreadable("secrets.bin")
            null
        }
        secrets = s
        val st = try {
            stateFile.read()?.let { PodJson.decodeFromString<AppState>(it.decodeToString()) }
        } catch (e: Exception) {
            // Never lose a family over a settings file: rebuild from the copy kept with the keys.
            DiagLog.e("Could not read app state (${e.javaClass.simpleName}); rebuilding from keys")
            preserveUnreadable("state.bin")
            null
        }
        _state.value = when {
            st != null -> st
            s?.family != null -> AppState(family = s.family)
            else -> AppState()
        }
        if (_state.value.family != null && s == null) {
            DiagLog.e("Family config present but keys missing - resetting")
            _state.value = AppState()
        }
        // Older installs: keep a copy of the family config alongside the keys.
        val fam = _state.value.family
        if (s != null && s.family == null && fam != null) {
            runCatching { mutateSecrets { it.copy(family = fam) } }
        }
    }

    private fun preserveUnreadable(name: String) {
        runCatching {
            val f = File(ctx.filesDir, name)
            if (f.exists()) f.copyTo(File(ctx.filesDir, "$name.unreadable"), overwrite = true)
        }
    }

    private fun mutate(block: (AppState) -> AppState): AppState = synchronized(lock) {
        val next = block(_state.value)
        if (next != _state.value) {
            _state.value = next
            if (demo) return@synchronized next
            try {
                stateFile.write(PodJson.encodeToString(next).toByteArray())
            } catch (e: Exception) {
                DiagLog.e("Saving state failed: ${e.message}")
            }
        }
        next
    }

    private fun mutateSecrets(block: (Secrets) -> Secrets) = synchronized(lock) {
        val cur = secrets ?: return@synchronized
        val next = block(cur)
        secretsFile.write(PodJson.encodeToString(next).toByteArray())
        secrets = next
    }

    private fun setConn(block: (ConnStatus) -> ConnStatus) {
        _conn.value = block(_conn.value)
    }

    // ------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------

    val isInFamily: Boolean get() = _state.value.family != null && secrets != null && !_state.value.removed

    val isAdmin: Boolean get() = _state.value.family?.let { it.adminId == it.deviceId } == true

    val myId: String? get() = _state.value.family?.deviceId

    val hasFamilyKey: Boolean get() = secrets?.familyKeys?.isNotEmpty() == true

    private fun family(): FamilyConfig = _state.value.family ?: throw IllegalStateException("not in a family")
    private fun sec(): Secrets = secrets ?: throw IllegalStateException("no identity")
    private fun api(): PodApi = PodApi(family().serverUrl)
    private fun signer(): Signer = Signer(family().deviceId, PodCrypto.unb64(sec().signSeed))

    private fun keyFor(epoch: Long): ByteArray? = secrets?.familyKeys?.get(epoch)?.let { PodCrypto.unb64(it) }

    private fun newestKey(): Pair<Long, ByteArray>? =
        secrets?.familyKeys?.maxByOrNull { it.key }?.let { it.key to PodCrypto.unb64(it.value) }

    private fun newestEpoch(): Long = secrets?.familyKeys?.keys?.maxOrNull() ?: 0

    private fun addFamilyKey(epoch: Long, key: ByteArray) {
        mutateSecrets {
            it.copy(
                familyKeys = it.familyKeys + (epoch to PodCrypto.b64(key)),
                keyAddedAt = it.keyAddedAt + (epoch to (it.keyAddedAt[epoch] ?: System.currentTimeMillis())),
            )
        }
    }

    fun normalizeServerUrl(input: String): String {
        var u = input.trim().trimEnd('/')
        if (!u.startsWith("https://") && !u.startsWith("http://")) u = "https://$u"
        return u
    }

    private fun friendly(e: Throwable): String = when (e) {
        is ApiException -> e.message ?: "Server error ${e.code}"
        is java.net.UnknownHostException -> "Can't reach the server (no internet or wrong address)"
        is java.net.SocketTimeoutException -> "The server took too long to respond"
        is javax.net.ssl.SSLException -> "Secure connection failed"
        is IOException -> "Network problem: ${e.message}"
        else -> e.message ?: e.javaClass.simpleName
    }

    // ------------------------------------------------------------------------------------
    // Onboarding
    // ------------------------------------------------------------------------------------

    /** Creates a new family on the server with this phone as the admin. */
    suspend fun createFamily(serverUrlInput: String, setupCode: String, name: String): Result<Unit> = runCatching {
        // NonCancellable: saving the family flips the UI to the map screen, which
        // cancels the onboarding screen's scope. Setup must still finish.
        withContext(NonCancellable + Dispatchers.IO) {
        val serverUrl = normalizeServerUrl(serverUrlInput)
        require(serverUrl.startsWith("https://")) { "The server address must use https://" }
        val api = PodApi(serverUrl)
        api.health()
        val keys = PodCrypto.newDeviceKeys()
        val resp = api.createFamily(setupCode.trim(), keys.signPub, keys.boxPub, Signer(null, keys.signSeed))
        // The admin id goes into every invite QR code: it must be OUR key, never whatever the server says.
        if (resp.device_id != PodCrypto.deviceId(keys.signPub)) {
            DiagLog.e("SECURITY: server returned a device id that isn't this phone's key")
            throw GeneralSecurityException("The server gave an unexpected identity. Not trusting it.")
        }
        val familyKey = PodCrypto.newFamilyKey()
        synchronized(lock) {
            val s = Secrets(
                signSeed = PodCrypto.b64(keys.signSeed), signPub = PodCrypto.b64(keys.signPub),
                boxPriv = PodCrypto.b64(keys.boxPriv), boxPub = PodCrypto.b64(keys.boxPub),
                familyKeys = mapOf(1L to PodCrypto.b64(familyKey)),
                family = FamilyConfig(serverUrl, resp.family_id, resp.device_id, adminId = resp.device_id),
                trustSeeded = true,
                keyAddedAt = mapOf(1L to System.currentTimeMillis()),
            )
            secretsFile.write(PodJson.encodeToString(s).toByteArray())
            secrets = s
        }
        mutate {
            AppState(
                family = FamilyConfig(serverUrl, resp.family_id, resp.device_id, adminId = resp.device_id),
                myName = name.trim(), myColor = MemberColors[0], currentEpoch = 1,
            )
        }
        val env = PodCrypto.sealEnvelope(familyKey, 1, resp.family_id, resp.device_id, keys.boxPub, keys.signSeed)
        api.putEnvelopes(signer(), PutEnvelopesReq(1, listOf(EnvelopeDto(resp.device_id, PodCrypto.b64(env)))))
        refreshFamily()
        publishProfile()
        DiagLog.i("Created family")
        }
    }.onFailure { DiagLog.e("Create family failed: ${friendly(it)}") }
        .recoverCatching { throw IOException(friendly(it)) }

    /** Joins a family from a scanned invite QR code. */
    suspend fun joinFamily(link: InviteLink, name: String): Result<Unit> = runCatching {
        withContext(NonCancellable + Dispatchers.IO) {
        val api = PodApi(link.serverUrl)
        api.health()
        val keys = PodCrypto.newDeviceKeys()
        val proof = PodCrypto.joinProof(PodCrypto.unb64url(link.secret), link.familyId, keys.signPub, keys.boxPub)
        val resp = api.join(
            JoinReq(link.familyId, link.token, PodCrypto.b64(keys.signPub), PodCrypto.b64(keys.boxPub), PodCrypto.b64(proof)),
            Signer(null, keys.signSeed),
        )
        if (resp.device_id != PodCrypto.deviceId(keys.signPub) || resp.family_id != link.familyId) {
            DiagLog.e("SECURITY: server join response doesn't match the invite or this phone's key")
            throw GeneralSecurityException("The server gave an unexpected answer. Not trusting it.")
        }
        synchronized(lock) {
            val s = Secrets(
                signSeed = PodCrypto.b64(keys.signSeed), signPub = PodCrypto.b64(keys.signPub),
                boxPriv = PodCrypto.b64(keys.boxPriv), boxPub = PodCrypto.b64(keys.boxPub),
                family = FamilyConfig(link.serverUrl, resp.family_id, resp.device_id, adminId = link.adminId),
            )
            secretsFile.write(PodJson.encodeToString(s).toByteArray())
            secrets = s
        }
        mutate {
            AppState(
                family = FamilyConfig(link.serverUrl, resp.family_id, resp.device_id, adminId = link.adminId),
                myName = name.trim(),
                myColor = MemberColors[(1 + (resp.device_id.hashCode() and 0x7fffffff) % (MemberColors.size - 1))],
            )
        }
        refreshFamily()
        DiagLog.i("Joined family, waiting for the admin's phone to approve")
        }
    }.onFailure { DiagLog.e("Join failed: ${friendly(it)}") }
        .recoverCatching { throw IOException(friendly(it)) }

    // ------------------------------------------------------------------------------------
    // Family membership + keys
    // ------------------------------------------------------------------------------------

    /** Reloads the member list from the server, verifying every key against its device id. */
    suspend fun refreshFamily(): FamilyResp {
        val fr = api().family(signer())
        val fam = family()
        if (fr.admin != fam.adminId) {
            // The admin is pinned from the QR code. A different admin means the
            // server is lying - refuse to trust it.
            DiagLog.e("SECURITY: server reported a different family admin")
            throw GeneralSecurityException("Server reported an unexpected family admin. Not trusting it.")
        }
        val prev = _state.value.members
        val removed = _state.value.removedDevices
        val admin = isAdmin
        if (admin) seedTrustIfNeeded(prev, fr)
        val trusted = if (admin) sec().trusted else emptyMap()
        val members = fr.members.mapNotNull { m ->
            val signPub = PodCrypto.unb64(m.sign_pub)
            if (PodCrypto.deviceId(signPub) != m.device_id) {
                DiagLog.e("SECURITY: member key does not match its id, ignoring ${m.device_id.take(6)}")
                return@mapNotNull null
            }
            if (m.device_id in removed) {
                DiagLog.w("SECURITY: server listed a removed device ${m.device_id.take(6)}; ignoring it")
                return@mapNotNull null
            }
            val old = prev[m.device_id]
            val isSelf = m.device_id == fam.deviceId
            // The encryption key is NOT covered by the device id, so it's never taken from the
            // server once known: our own comes from our keys, members' from the verified invite proof.
            val pinned = when {
                isSelf -> sec().boxPub
                admin -> trusted[m.device_id]
                else -> null
            }
            if (pinned != null && pinned != m.box_pub) {
                DiagLog.e("SECURITY: server reported a changed encryption key for ${m.device_id.take(6)}; keeping the verified one")
            }
            // Start from what we already know about them and only overwrite what the server
            // owns, so nothing local (place + since, epochs, profile...) is lost on a refresh.
            val base = old ?: Member(deviceId = m.device_id, signPub = m.sign_pub, boxPub = pinned ?: m.box_pub, role = m.role)
            m.device_id to base.copy(
                deviceId = m.device_id, signPub = m.sign_pub, boxPub = pinned ?: m.box_pub, role = m.role,
                joinedAt = m.created_at,
                approved = if (admin) isSelf || m.device_id in trusted
                else old?.approved == true || m.device_id == fam.adminId || isSelf,
            )
        }.toMap()
        // Never let the server push the epoch far past the keys we actually hold.
        val boundedEpoch = minOf(fr.epoch, newestEpoch() + 16)
        mutate { it.copy(members = members, currentEpoch = maxOf(it.currentEpoch, boundedEpoch)) }
        if (admin && approvePending(fr)) {
            try {
                runPendingRotation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagLog.w("Key change will be retried: ${friendly(e)}")
            }
        }
        return fr
    }

    /**
     * Admin, once: older versions didn't keep a list of verified members. Trust the members
     * this phone had already approved (or, right after restoring an old recovery kit, the
     * members the server lists) so key rotation keeps including them.
     */
    private fun seedTrustIfNeeded(prev: Map<String, Member>, fr: FamilyResp) {
        val s = sec()
        if (s.trustSeeded) return
        val self = family().deviceId
        val fromState = prev.values.filter { it.approved && it.deviceId != self }.associate { it.deviceId to it.boxPub }
        val seed = fromState.ifEmpty {
            fr.members.filter { it.device_id != self && PodCrypto.deviceId(PodCrypto.unb64(it.sign_pub)) == it.device_id }
                .associate { it.device_id to it.box_pub }
        }
        mutateSecrets { it.copy(trusted = it.trusted + seed, trustSeeded = true) }
        DiagLog.i("Recorded ${seed.size} verified member(s)")
    }

    /**
     * Admin only: verifies devices that proved they scanned an invite in person and pins
     * their keys. Returns true if anyone new was verified; the caller then rotates the
     * family key, so newcomers only get a NEW key and can't read older history.
     */
    private suspend fun approvePending(fr: FamilyResp): Boolean = keyOps.withLock {
        val fam = family()
        val now = System.currentTimeMillis() / 1000
        mutateSecrets { s -> s.copy(pendingInvites = s.pendingInvites.filterValues { it.expiresAt > now - 3600 }) }
        var any = false
        for (m in fr.members) {
            if (m.device_id == fam.deviceId || m.device_id in sec().trusted || m.device_id in _state.value.removedDevices) continue
            val proof = m.join_proof?.let { PodCrypto.unb64(it) } ?: continue
            val signPub = PodCrypto.unb64(m.sign_pub)
            if (PodCrypto.deviceId(signPub) != m.device_id) continue
            val boxPub = PodCrypto.unb64(m.box_pub)
            val match = sec().pendingInvites.entries.firstOrNull { (_, inv) ->
                PodCrypto.verifyJoinProof(PodCrypto.unb64url(inv.secret), fam.familyId, signPub, boxPub, proof)
            } ?: continue
            mutateSecrets { s ->
                s.copy(pendingInvites = s.pendingInvites - match.key, trusted = s.trusted + (m.device_id to m.box_pub))
            }
            mutate { st ->
                val mem = st.members[m.device_id] ?: return@mutate st
                st.copy(members = st.members + (m.device_id to mem.copy(approved = true, boxPub = m.box_pub)))
            }
            DiagLog.i("Verified new member ${m.device_id.take(6)}")
            any = true
        }
        if (any) scheduleRotationLocked()
        any
    }

    /**
     * Fetches new family keys and, if any arrived, re-reads the latest family
     * state, since messages we couldn't decrypt before are now readable.
     */
    suspend fun syncKeys(): Boolean {
        val gotNew = refreshEnvelopes()
        if (gotNew) {
            val st = api().state(signer())
            processMessages(st.messages, live = false)
        }
        return gotNew
    }

    /** Fetches and opens any new family keys addressed to this phone. Returns true if any were new. */
    private suspend fun refreshEnvelopes(): Boolean = keyOps.withLock {
        val fam = family()
        val er = api().envelopes(signer())
        val admin = _state.value.members[fam.adminId] ?: run {
            DiagLog.w("Admin not in member list yet")
            return@withLock false
        }
        val s = sec()
        var gotNew = false
        for (env in er.envelopes) {
            if (s.familyKeys.containsKey(env.epoch)) continue
            try {
                val key = PodCrypto.openEnvelope(
                    PodCrypto.unb64(env.data), env.epoch, fam.familyId, fam.deviceId,
                    PodCrypto.unb64(s.boxPriv), PodCrypto.unb64(s.boxPub), PodCrypto.unb64(admin.signPub),
                )
                addFamilyKey(env.epoch, key)
                gotNew = true
                DiagLog.i("Received family key for epoch ${env.epoch}")
            } catch (e: GeneralSecurityException) {
                DiagLog.e("SECURITY: rejected a key envelope (epoch ${env.epoch}): ${e.message}")
            }
        }
        val bounded = minOf(er.current_epoch, newestEpoch() + 16)
        mutate { it.copy(currentEpoch = maxOf(it.currentEpoch, bounded)) }
        gotNew
    }

    /** Admin: create a single-use invite. Returns the QR contents. */
    suspend fun createInvite(ttlSeconds: Long = 30 * 60): Result<InviteLink> = runCatching {
        check(isAdmin) { "Only the family admin can invite" }
        val fam = family()
        val token = PodCrypto.randomBytes(32)
        val secret = PodCrypto.randomBytes(32)
        val hash = PodCrypto.hex(PodCrypto.sha256(token))
        api().createInvite(signer(), hash, ttlSeconds)
        val expires = System.currentTimeMillis() / 1000 + ttlSeconds
        mutateSecrets { it.copy(pendingInvites = it.pendingInvites + (hash to PendingInvite(PodCrypto.b64url(secret), expires))) }
        InviteLink(fam.serverUrl, fam.familyId, PodCrypto.b64url(token), PodCrypto.b64url(secret), fam.deviceId)
    }.recoverCatching { throw IOException(friendly(it)) }

    /**
     * Admin: remove a member and rotate the family key so they can't read anything new.
     * Finishes even if the screen is closed; if the key change can't reach the server
     * right now it is saved and retried automatically until it succeeds.
     */
    suspend fun removeMember(deviceId: String): Result<Unit> = runCatching {
        withContext(NonCancellable + Dispatchers.IO) {
            check(isAdmin) { "Only the family admin can remove members" }
            check(deviceId != family().deviceId) { "Use Delete family to remove yourself" }
            try {
                api().removeMember(signer(), deviceId)
            } catch (e: ApiException) {
                if (e.code != 404) throw e // already removed (e.g. a retry): carry on with the key change
            }
            forgetDevice(deviceId)
            try {
                refreshFamily()
            } catch (e: Exception) {
                DiagLog.w("Member list refresh failed: ${friendly(e)}")
            }
            // If this can't reach the server now, it's saved and retried by the sync loop.
            retryRotationQuietly()
            DiagLog.i("Removed member ${deviceId.take(6)}")
        }
    }.recoverCatching { throw IOException(friendly(it)) }

    /** A device left or was removed: never trust it again, and (admin) change the family key. */
    private suspend fun forgetDevice(deviceId: String) {
        // Already handled (e.g. the server's notice about a removal we just did): don't rotate twice.
        if (deviceId in _state.value.removedDevices) return
        mutate { it.copy(members = it.members - deviceId, removedDevices = it.removedDevices + deviceId) }
        if (isAdmin) keyOps.withLock {
            mutateSecrets { it.copy(trusted = it.trusted - deviceId) }
            scheduleRotationLocked()
        }
    }

    /** Admin, holding keyOps: make sure a key rotation is pending (saved, so it survives restarts). */
    private fun scheduleRotationLocked() {
        if (sec().pendingRotation != null) return
        val base = maxOf(newestEpoch(), minOf(_state.value.currentEpoch, newestEpoch() + 16))
        val epoch = base + 1
        check(epoch < 0xFFFFFFFFL) { "Key epoch out of range" }
        val rot = PendingRotation(epoch, PodCrypto.b64(PodCrypto.newFamilyKey()), System.currentTimeMillis())
        mutateSecrets { it.copy(pendingRotation = rot) }
        DiagLog.i("Key change to epoch $epoch scheduled")
    }

    /**
     * Admin: delivers a pending new family key to every verified member (with the keys pinned
     * when they were verified, never keys the server reports). The new key is only used for
     * sending after the server has accepted it, so a failure never leaves the family unable to
     * read this phone. Safe to call any time; does nothing if no rotation is pending.
     */
    suspend fun runPendingRotation() {
        if (!isAdmin) return
        keyOps.withLock {
            val rot = sec().pendingRotation ?: return@withLock
            val fam = family()
            val key = PodCrypto.unb64(rot.key)
            val seed = PodCrypto.unb64(sec().signSeed)
            val listed = _state.value.members.keys
            val recipients = sec().trusted.filterKeys { it in listed && it != fam.deviceId } + (fam.deviceId to sec().boxPub)
            val envs = recipients.map { (id, boxPub) ->
                EnvelopeDto(id, PodCrypto.b64(PodCrypto.sealEnvelope(key, rot.epoch, fam.familyId, id, PodCrypto.unb64(boxPub), seed)))
            }
            api().putEnvelopes(signer(), PutEnvelopesReq(rot.epoch, envs))
            addFamilyKey(rot.epoch, key)
            mutateSecrets { it.copy(pendingRotation = null) }
            mutate { it.copy(currentEpoch = maxOf(it.currentEpoch, rot.epoch)) }
            DiagLog.i("Family key changed (epoch ${rot.epoch}, ${envs.size} phone(s))")
        }
    }

    /** Leave (member) or delete (admin) the family, then wipe this phone's data. Finishes even if the screen closes. */
    suspend fun leaveFamily(): Result<Unit> = runCatching {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                if (isAdmin) api().deleteFamily(signer()) else api().removeMember(signer(), family().deviceId)
            } catch (e: ApiException) {
                if (!e.deviceGone && e.code != 404) throw e
            }
            wipeLocal()
        }
    }.recoverCatching { throw IOException(friendly(it)) }

    // ------------------------------------------------------------------------------------
    // Recovery kit
    // ------------------------------------------------------------------------------------

    /**
     * Builds an encrypted recovery kit for this phone. Saving it once is enough:
     * after a restore, newer family keys are fetched from the server because
     * they're addressed to this same identity.
     */
    suspend fun exportRecoveryKit(passphrase: String): String = withContext(Dispatchers.Default) {
        val s = sec()
        val st = _state.value
        check(st.family != null) { "Not in a family" }
        val payload = RecoveryPayload(
            createdAt = System.currentTimeMillis(),
            secrets = s,
            // Only durable things: identity, family, places, settings. Live data is re-synced.
            state = st.copy(members = emptyMap(), cursor = 0, insidePlaces = emptySet(), staleNotified = emptySet(), removed = false, pendingEvents = emptyList()),
        )
        val file = app.podfamily.crypto.RecoveryKit.encrypt(PodJson.encodeToString(payload).toByteArray(), passphrase)
        DiagLog.i("Recovery kit created")
        file
    }

    fun markRecoveryKitSaved() {
        mutate { it.copy(recoveryKitSavedAt = System.currentTimeMillis()) }
    }

    /** Restores identity + family from a kit on a fresh install. */
    suspend fun restoreFromKit(file: String, passphrase: String): Result<Unit> = runCatching {
        withContext(NonCancellable + Dispatchers.IO) {
            check(_state.value.family == null) { "This phone is already in a family" }
            val plain = app.podfamily.crypto.RecoveryKit.decrypt(file, passphrase)
            val payload = PodJson.decodeFromString<RecoveryPayload>(plain.decodeToString())
            val restored = payload.state.copy(
                members = emptyMap(), cursor = 0, insidePlaces = emptySet(), staleNotified = emptySet(),
                removed = false, permissionsSetupDone = false, recoveryKitSavedAt = payload.createdAt,
                pendingEvents = emptyList(),
            )
            synchronized(lock) {
                val restoredSecrets = payload.secrets.copy(family = restored.family)
                secretsFile.write(PodJson.encodeToString(restoredSecrets).toByteArray())
                secrets = restoredSecrets
                stateFile.write(PodJson.encodeToString(restored).toByteArray())
                _state.value = restored
            }
            try {
                // Proves the server still knows this identity, then pulls the newest keys.
                refreshFamily()
                syncKeys()
            } catch (e: ApiException) {
                if (e.deviceGone) {
                    wipeLocal()
                    throw IOException("This phone was removed from the family (or the family was deleted) after the kit was made.")
                }
                throw e
            }
            DiagLog.i("Restored from recovery kit")
        }
    }.onFailure { DiagLog.e("Restore failed: ${friendly(it)}") }
        .recoverCatching { throw IOException(if (it is app.podfamily.crypto.RecoveryKit.WrongPassphrase) it.message else friendly(it)) }

    fun wipeLocal() {
        synchronized(lock) {
            secretsFile.delete()
            stateFile.delete()
            secrets = null
            _state.value = AppState()
            lastUploaded = null
            pendingLoc = null
        }
        setConn { ConnStatus() }
        DiagLog.i("Local data wiped")
    }

    private fun handleRemoved() {
        if (_state.value.removed) return
        mutate { it.copy(removed = true, pendingEvents = emptyList()) }
        // A removed phone must not keep the family's keys.
        runCatching { mutateSecrets { it.copy(familyKeys = emptyMap(), pendingRotation = null, keyAddedAt = emptyMap()) } }
        Notifications.show(ctx, Notifications.CH_FAMILY, "Removed from family", "This phone is no longer part of the family. Open Pod to set it up again.")
        DiagLog.w("This device was removed from the family")
    }

    // ------------------------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------------------------

    private suspend fun send(kind: String, json: String, ttlSeconds: Long) {
        val fam = family()
        val (epoch, key) = newestKey() ?: throw IllegalStateException("Waiting for family key")
        check(epoch in 1 until 0xFFFFFFFFL) { "Key epoch out of range" }
        val blob = PodCrypto.sealMessage(json.toByteArray(), kind, fam.familyId, epoch, key, fam.deviceId, PodCrypto.unb64(sec().signSeed))
        api().postMessage(signer(), kind, blob, ttlSeconds)
    }

    private val historyTtl: Long get() = _state.value.historyDays.coerceIn(1, 30) * 86_400L

    suspend fun publishProfile() {
        if (!hasFamilyKey) return
        val st = _state.value
        send(Kinds.PROFILE, PodJson.encodeToString(ProfilePayload(st.myName, st.myColor)), 0)
        mutate { it.copy(profilePublishedEpoch = newestKey()?.first ?: 0) }
    }

    suspend fun updateProfile(name: String, color: Int): Result<Unit> = runCatching {
        mutate { it.copy(myName = name.trim(), myColor = color) }
        publishProfile()
    }.recoverCatching { throw IOException(friendly(it)) }

    /** Departures waiting to be confirmed (place id -> first reading outside). Memory only. */
    @Volatile private var pendingExits: Map<String, Long> = emptyMap()

    /**
     * Called by the location service for every new fix. Returns true if a departure needs
     * confirming, so the service should grab a fresh precise fix right away.
     */
    suspend fun onLocation(loc: LocPayload, forceUpload: Boolean = false): Boolean {
        if (!isInFamily) return false
        val st = _state.value
        val me = st.family!!.deviceId
        // Show ourselves on our own map immediately.
        mutate { s ->
            val m = s.members[me] ?: return@mutate s
            s.copy(members = s.members + (me to withPlace(m.copy(location = loc), loc, s.places)))
        }
        if (!st.sharingEnabled) return false

        // Geofences (departures are confirmed before they're announced).
        val outcome = Geofence.evaluateConfirmed(loc.lat, loc.lon, loc.acc, loc.t, st.places, st.insidePlaces, pendingExits)
        val inside = outcome.inside
        val transitions = outcome.transitions
        pendingExits = outcome.pendingExits
        if (outcome.wantsConfirmFix) DiagLog.i("Possible departure; checking with a fresh fix")
        if (inside != st.insidePlaces) mutate { it.copy(insidePlaces = inside) }
        for (t in transitions) {
            if (!st.shareArrivals) break
            val shared = shareable(loc)
            val at = minOf(t.t ?: System.currentTimeMillis(), System.currentTimeMillis())
            queueEvent(EventPayload(if (t.arrived) "arrive" else "leave", at, t.place.name, t.place.id, shared.lat, shared.lon))
        }

        val last = lastUploaded
        val due = forceUpload || last == null ||
            Geofence.distanceMeters(last.lat, last.lon, loc.lat, loc.lon) > 25 ||
            loc.t - last.t > 4 * 60_000 ||
            (loc.bat != null && last.bat != null && kotlin.math.abs(loc.bat - last.bat) >= 5) ||
            loc.chg != last.chg ||
            transitions.isNotEmpty()
        if (due) {
            pendingLoc = loc
            flushPending()
        }
        return outcome.wantsConfirmFix
    }

    /** Queues an event; it's saved to disk so it survives Android stopping the app. SOS is never dropped. */
    private fun queueEvent(e: EventPayload) {
        mutate { st ->
            var q = st.pendingEvents + e
            while (q.size > 50) {
                val i = q.indexOfFirst { it.type != "sos" }.takeIf { it >= 0 } ?: 0
                q = q.filterIndexed { idx, _ -> idx != i }
            }
            st.copy(pendingEvents = q)
        }
    }

    private val hasPendingWork: Boolean get() = pendingLoc != null || _state.value.pendingEvents.isNotEmpty()

    /**
     * Sends queued events (oldest first) and the newest location. Safe to call any time and
     * from anywhere: only one pass runs at a time, and a pass keeps going until nothing new
     * was queued meanwhile, so nothing is sent twice or left behind.
     */
    suspend fun flushPending(wait: Boolean = false) {
        if (!isInFamily || !hasFamilyKey) return
        if (wait) flushLock.lock() else if (!flushLock.tryLock()) return
        try {
            do {
                while (true) {
                    val e = _state.value.pendingEvents.firstOrNull() ?: break
                    send(Kinds.EVENT, PodJson.encodeToString(e), historyTtl)
                    mutate { st -> st.copy(pendingEvents = st.pendingEvents.drop(if (st.pendingEvents.firstOrNull() == e) 1 else 0)) }
                }
                val loc = pendingLoc
                if (loc != null && _state.value.sharingEnabled) {
                    send(Kinds.LOC, PodJson.encodeToString(shareable(loc)), historyTtl)
                    if (lastUploaded == null) DiagLog.i("First location sent to family")
                    lastUploaded = loc
                    if (pendingLoc === loc) pendingLoc = null
                    setConn { it.copy(lastUploadAt = System.currentTimeMillis(), error = null) }
                } else if (loc != null && pendingLoc === loc) {
                    pendingLoc = null
                }
            } while (hasPendingWork && _state.value.sharingEnabled)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.deviceGone) handleRemoved()
            DiagLog.w("Upload failed: ${friendly(e)}")
        } catch (e: Exception) {
            DiagLog.w("Upload failed: ${friendly(e)}")
        } finally {
            flushLock.unlock()
        }
    }

    suspend fun setSharing(enabled: Boolean) {
        if (_state.value.sharingEnabled == enabled) return
        mutate { it.copy(sharingEnabled = enabled) }
        // Pausing is never silent: the family is told.
        queueEvent(EventPayload(if (enabled) "resumed" else "paused", System.currentTimeMillis()))
        flushPending()
    }

    /** This phone lost background location permission: tell the family once. */
    suspend fun onPermissionLost() {
        if (!isInFamily || _state.value.permissionLostReported) return
        mutate { it.copy(permissionLostReported = true) }
        if (_state.value.sharingEnabled) queueEvent(EventPayload("noperm", System.currentTimeMillis()))
        DiagLog.w("Background location permission was removed")
        flushPending()
    }

    /** Permission is back: tell the family sharing resumed. */
    suspend fun onPermissionRestored() {
        if (!isInFamily || !_state.value.permissionLostReported) return
        mutate { it.copy(permissionLostReported = false) }
        if (_state.value.sharingEnabled) queueEvent(EventPayload("resumed", System.currentTimeMillis()))
        DiagLog.i("Background location permission restored")
        flushPending()
    }

    /** True while an SOS is waiting to be delivered (it keeps retrying automatically). */
    val sosPending: Boolean get() = _state.value.pendingEvents.any { it.type == "sos" }

    /**
     * Sends an SOS. It goes through the saved queue, so if it can't be delivered right now it
     * keeps retrying automatically. Returns failure if it was NOT delivered yet. Calling again
     * while one is waiting retries that one instead of creating a duplicate. Always precise.
     */
    suspend fun sendSos(): Result<Unit> = runCatching {
        withContext(NonCancellable) {
            check(isInFamily) { "Not in a family" }
            if (!sosPending) {
                val me = _state.value.members[myId]?.location
                // Only attach a location that's reasonably current; a fresh fix follows right after.
                val recent = me?.takeIf { System.currentTimeMillis() - it.t < 10 * 60_000L }
                queueEvent(EventPayload("sos", System.currentTimeMillis(), lat = recent?.lat, lon = recent?.lon))
                DiagLog.i("SOS queued")
            }
            flushPending(wait = true)
            if (sosPending) throw IOException(_conn.value.error ?: "Couldn't reach the server")
            DiagLog.i("SOS sent")
        }
    }

    suspend fun checkIn(): Result<Unit> = runCatching {
        val me = _state.value.members[myId]?.location
        val shared = me?.let { shareable(it) }
        send(Kinds.EVENT, PodJson.encodeToString(EventPayload("checkin", System.currentTimeMillis(), lat = shared?.lat, lon = shared?.lon)), historyTtl)
        if (shared != null) send(Kinds.LOC, PodJson.encodeToString(shared), historyTtl)
        Unit
    }.recoverCatching { throw IOException(friendly(it)) }

    suspend fun savePlaces(places: List<Place>): Result<Unit> = runCatching {
        val now = System.currentTimeMillis()
        mutate { it.copy(places = places, placesUpdatedAt = now) }
        send(Kinds.PLACES, PodJson.encodeToString(PlacesPayload(places, now)), 0)
    }.recoverCatching { throw IOException(friendly(it)) }

    /** Edit an existing place (name, radius, position). Shared with the whole family. */
    suspend fun updatePlace(place: Place): Result<Unit> =
        savePlaces(_state.value.places.map { if (it.id == place.id) place else it })

    suspend fun deletePlace(id: String): Result<Unit> {
        mutate { it.copy(placeAlerts = it.placeAlerts - id) }
        return savePlaces(_state.value.places.filter { it.id != id })
    }

    /** This phone's alert choice for a place. Local only. */
    fun setPlaceAlerts(placeId: String, pref: PlaceAlertPref) {
        mutate { it.copy(placeAlerts = it.placeAlerts + (placeId to pref)) }
    }

    fun setMemberMuted(deviceId: String, muted: Boolean) {
        mutate { it.copy(mutedMembers = if (muted) it.mutedMembers + deviceId else it.mutedMembers - deviceId) }
    }

    /**
     * Applies this phone's sharing choices to a location before it is encrypted
     * and sent. Your own map always shows your precise location.
     */
    private fun shareable(loc: LocPayload): LocPayload {
        val st = _state.value
        var l = loc
        if (!st.preciseLocation) {
            // Snap to a ~1 km grid so the exact address is never shared.
            l = l.copy(
                lat = Math.round(l.lat * 100) / 100.0,
                lon = Math.round(l.lon * 100) / 100.0,
                acc = maxOf(l.acc, 1000f),
            )
        }
        return l.copy(
            bat = if (st.shareBattery) l.bat else null,
            chg = if (st.shareCharging) l.chg else null,
            spd = if (st.shareSpeed && st.preciseLocation) l.spd else null,
        )
    }

    /** Change a sharing setting and immediately re-send our location so the family sees the change. */
    suspend fun updateSharing(block: (AppState) -> AppState) {
        mutate(block)
        _state.value.members[myId]?.location?.let { pendingLoc = it }
        flushPending()
    }

    /** Change a local-only display setting. */
    fun updateSettings(block: (AppState) -> AppState) {
        mutate(block)
    }

    fun markPermissionsSetupDone() {
        mutate { it.copy(permissionsSetupDone = true) }
    }

    fun setHistoryDays(days: Int) {
        mutate { it.copy(historyDays = days.coerceIn(1, 30)) }
    }

    // ------------------------------------------------------------------------------------
    // Receiving
    // ------------------------------------------------------------------------------------

    @Serializable private data class SysMsg(val type: String, val device_id: String? = null, val epoch: Long? = null)

    /**
     * Handles a batch of messages. With [advanceCursor], the cursor moves past every message
     * that was handled, even if a later one fails, so a server hiccup never replays (and
     * re-alerts) messages that were already processed.
     */
    private suspend fun processMessages(msgs: List<MessageDto>, live: Boolean, advanceCursor: Boolean = false) {
        batchFamilyRefreshed = false
        batchEnvelopesRefreshed = false
        var done = 0L
        try {
            for (m in msgs) {
                try {
                    processOne(m, live)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    throw e
                } catch (e: Exception) {
                    DiagLog.w("Skipped message ${m.id}: ${e.javaClass.simpleName}")
                }
                done = m.id
            }
        } finally {
            if (advanceCursor && done > _state.value.cursor) mutate { it.copy(cursor = maxOf(it.cursor, done)) }
        }
    }

    private suspend fun refreshFamilyOncePerBatch() {
        if (batchFamilyRefreshed) return
        batchFamilyRefreshed = true
        refreshFamily()
    }

    /** Has an event with this identity already been handled? Records it if not. */
    private fun firstTimeSeen(key: String): Boolean {
        var first = false
        mutate { st ->
            if (key in st.seenEvents) st else {
                first = true
                st.copy(seenEvents = (st.seenEvents + key).takeLast(MAX_SEEN_EVENTS))
            }
        }
        return first
    }

    /**
     * Refuses messages under an old family key once the sender has moved on, or long after
     * a newer key arrived. Stops a removed phone (which only has old keys) from posting.
     */
    private fun epochAcceptable(sender: Member, epoch: Long, createdAtSec: Long): Boolean {
        if (epoch < sender.maxEpoch) return false
        val newest = newestEpoch()
        if (epoch >= newest) return true
        val newestAt = secrets?.keyAddedAt?.get(newest) ?: return true
        return createdAtSec * 1000 < newestAt + OLD_EPOCH_GRACE_MS
    }

    private suspend fun processOne(m: MessageDto, live: Boolean) {
        val fam = family()
        if (m.kind == Kinds.SYS) {
            val sys = PodJson.decodeFromString<SysMsg>(PodCrypto.unb64(m.blob).decodeToString())
            when (sys.type) {
                "member_joined" -> {
                    refreshFamilyOncePerBatch()
                    if (live && sys.device_id != fam.deviceId) {
                        Notifications.show(ctx, Notifications.CH_FAMILY, "New family member", "A new phone joined your family.")
                    }
                }
                "member_removed" -> {
                    val id = sys.device_id ?: return
                    if (id == fam.deviceId) {
                        // Notices aren't signed: only believe it if the server really refuses us.
                        try {
                            api().family(signer())
                            DiagLog.w("SECURITY: ignored a removal notice for this phone that the server doesn't back up")
                        } catch (e: ApiException) {
                            if (e.deviceGone) handleRemoved() else throw e
                        }
                    } else if (id != fam.adminId) {
                        forgetDevice(id)
                        refreshFamilyOncePerBatch()
                    }
                }
                "epoch" -> {
                    if (_state.value.members[fam.adminId] == null) refreshFamilyOncePerBatch()
                    if (!batchEnvelopesRefreshed) {
                        batchEnvelopesRefreshed = true
                        syncKeys()
                    }
                    val newest = newestKey()?.first ?: 0
                    if (newest > _state.value.profilePublishedEpoch) {
                        // New key (or a new member got the key): re-publish so everyone,
                        // including newcomers, can read our profile, location and places.
                        publishProfile()
                        if (isAdmin && _state.value.places.isNotEmpty()) {
                            send(Kinds.PLACES, PodJson.encodeToString(PlacesPayload(_state.value.places, _state.value.placesUpdatedAt)), 0)
                        }
                    }
                    _state.value.members[fam.deviceId]?.location?.let { pendingLoc = it }
                    flushPending()
                }
            }
            return
        }

        if (m.sender in _state.value.removedDevices) return
        var sender = _state.value.members[m.sender]
        if (sender == null) {
            refreshFamilyOncePerBatch()
            sender = _state.value.members[m.sender] ?: return
        }
        val blob = PodCrypto.unb64(m.blob)
        val epoch = PodCrypto.messageEpoch(blob) ?: return
        if (keyFor(epoch) == null && !batchEnvelopesRefreshed) {
            batchEnvelopesRefreshed = true
            refreshEnvelopes()
        }
        val plain = PodCrypto.openMessage(blob, m.kind, fam.familyId, m.sender, PodCrypto.unb64(sender.signPub), ::keyFor)
        if (plain == null) {
            DiagLog.w("Could not decrypt message from ${m.sender.take(6)} (kind ${m.kind})")
            return
        }
        if (!epochAcceptable(sender, epoch, m.created_at)) {
            DiagLog.w("SECURITY: refused an old-key message from ${m.sender.take(6)} (epoch $epoch)")
            return
        }
        if (epoch > sender.maxEpoch) {
            mutate { st ->
                val mem = st.members[m.sender] ?: return@mutate st
                st.copy(members = st.members + (m.sender to mem.copy(maxEpoch = maxOf(mem.maxEpoch, epoch))))
            }
        }
        val text = plain.decodeToString()
        val isMe = m.sender == fam.deviceId
        val nowMs = System.currentTimeMillis()

        when (m.kind) {
            Kinds.LOC -> {
                if (isMe) return
                val loc = PodJson.decodeFromString<LocPayload>(text)
                if (loc.t > nowMs + FUTURE_SKEW_MS) return
                // Freshness from the signed fix time, not the server's timestamp.
                val fresh = live && nowMs - loc.t < LIVE_WINDOW_MS
                val prevBat = _state.value.members[m.sender]?.location?.bat
                mutate { st ->
                    val mem = st.members[m.sender] ?: return@mutate st
                    if ((mem.location?.t ?: 0) >= loc.t) st
                    else st.copy(
                        members = st.members + (m.sender to run {
                            val shown = app.podfamily.location.LocationSmoothing.steady(mem.location, loc)
                            withPlace(mem.copy(location = shown), shown, st.places)
                        }),
                        staleNotified = st.staleNotified - m.sender,
                    )
                }
                val bat = loc.bat
                val st = _state.value
                if (fresh && st.lowBatteryAlerts && bat != null && bat <= LOW_BATTERY && loc.chg != true &&
                    (prevBat == null || prevBat > LOW_BATTERY) && generalAlertAllowed(m.sender)
                ) {
                    val name = sender.name ?: "A family member"
                    Notifications.show(ctx, Notifications.CH_FAMILY, "$name's battery is low", "$name's phone is at $bat%. They may go offline soon.", memberId = m.sender)
                }
            }
            Kinds.PROFILE -> {
                val p = PodJson.decodeFromString<ProfilePayload>(text)
                // On the admin's phone, "approved" only ever comes from a verified invite proof.
                val admin = isAdmin
                mutate { st ->
                    val mem = st.members[m.sender] ?: return@mutate st
                    st.copy(members = st.members + (m.sender to mem.copy(name = p.name.take(40), color = p.color, approved = mem.approved || !admin)))
                }
            }
            Kinds.PLACES -> {
                val p = PodJson.decodeFromString<PlacesPayload>(text)
                if (p.t > nowMs + FUTURE_SKEW_MS) {
                    DiagLog.w("Ignored places from ${m.sender.take(6)} dated in the future")
                    return
                }
                mutate { st -> if (p.t > st.placesUpdatedAt) st.copy(places = p.places.take(100), placesUpdatedAt = p.t) else st }
            }
            Kinds.EVENT -> {
                val e = PodJson.decodeFromString<EventPayload>(text)
                if (e.t > nowMs + FUTURE_SKEW_MS) return
                // Each event is handled once, even if the server re-delivers or replays it.
                if (!firstTimeSeen("${m.sender}:${e.type}:${e.t}")) return
                mutate { st ->
                    val mem = st.members[m.sender] ?: return@mutate st
                    if ((mem.lastEvent?.t ?: 0) > e.t) st
                    else {
                        // An arrival event pins down exactly when they got there.
                        val since = if (e.type == "arrive" && mem.placeTracked && e.placeId != null && e.placeId == mem.placeId &&
                            (mem.placeSince == 0L || e.t < mem.placeSince)) e.t else mem.placeSince
                        st.copy(members = st.members + (m.sender to mem.copy(lastEvent = e, placeSince = since)))
                    }
                }
                val alertable = live && nowMs - e.t < EVENT_ALERT_WINDOW_MS
                if (!isMe && alertable) notifyEvent(m.sender, sender.name ?: "A family member", e)
            }
        }
    }

    /**
     * Keeps track of which place someone is at and since when, for "At Home since 3:12 PM".
     * Uses their arrival event's time when we have it, else the first location seen inside.
     */
    private fun withPlace(mem: Member, loc: LocPayload, places: List<Place>): Member {
        val next = app.podfamily.location.PlaceTracker.update(
            app.podfamily.location.PlaceTracker.State(mem.placeId, mem.placeSince, mem.placeTracked),
            loc, places, mem.lastEvent,
        )
        return mem.copy(placeId = next.placeId, placeSince = next.since, placeTracked = next.tracked)
    }

    /** Should this phone show an alert for this event? SOS always alerts. */
    private fun wantsAlert(senderId: String, e: EventPayload): Boolean {
        val st = _state.value
        if (e.type == "sos") return true
        if (inQuietHours()) return false
        if (senderId in st.mutedMembers && e.type in setOf("arrive", "leave", "checkin")) return false
        if (e.type == "arrive" || e.type == "leave") {
            val placeId = e.placeId ?: st.places.firstOrNull { it.name == e.place }?.id
            val pref = placeId?.let { st.placeAlerts[it] } ?: PlaceAlertPref()
            if (pref.scheduled && !(isoDayNow() in pref.days && inWindow(minutesNow(), pref.startMin, pref.endMin))) return false
            return if (e.type == "arrive") pref.arrive else pref.leave
        }
        return true
    }

    private fun inQuietHours(): Boolean {
        val st = _state.value
        return st.quietHours && inWindow(minutesNow(), st.quietStartMin, st.quietEndMin)
    }

    /** For low-battery and went-quiet warnings: respects muting and quiet hours. */
    private fun generalAlertAllowed(senderId: String): Boolean =
        senderId !in _state.value.mutedMembers && !inQuietHours()

    /** Fires (and removes) a matching one-time alert. Returns true if one fired. */
    private fun fireOneTime(senderId: String, name: String, e: EventPayload): Boolean {
        if (e.type != "arrive" && e.type != "leave") return false
        val st = _state.value
        val eventPlaceId = e.placeId ?: st.places.firstOrNull { it.name == e.place }?.id
        val match = st.oneTimeAlerts.firstOrNull {
            it.memberId == senderId && it.type == e.type && (it.placeId == null || it.placeId == eventPlaceId)
        } ?: return false
        mutate { s -> s.copy(oneTimeAlerts = s.oneTimeAlerts.filter { it.id != match.id }) }
        val verb = if (e.type == "arrive") "arrived at" else "left"
        Notifications.show(ctx, Notifications.CH_ALERTS, "$name $verb ${e.place ?: "a place"}", "Your one-time alert for $name went off.", memberId = senderId)
        return true
    }

    /**
     * Warns once when someone's phone hasn't updated for a while (dead battery,
     * no signal, or Android stopped Pod). Called periodically by the service.
     */
    fun checkStale() {
        val st = _state.value
        val hours = st.staleAlertHours
        if (hours <= 0 || st.family == null) return
        val now = System.currentTimeMillis()
        // If THIS phone is offline, everyone else only looks stale. Don't cry wolf.
        val c = _conn.value
        if (!c.online || now - c.lastSyncAt > 10 * 60_000L) return
        if (inQuietHours()) return // checked again later; nothing is marked as notified
        for (m in st.members.values) {
            if (m.deviceId == st.family.deviceId || m.name == null || m.deviceId in st.staleNotified) continue
            val last = m.location?.t ?: continue
            val pausedAfter = m.lastEvent?.let { (it.type == "paused" || it.type == "noperm") && it.t >= last } == true
            if (pausedAfter || now - last < hours * 3_600_000L) continue
            mutate { s -> s.copy(staleNotified = s.staleNotified + m.deviceId) }
            if (m.deviceId !in st.mutedMembers) {
                Notifications.show(
                    ctx, Notifications.CH_FAMILY, "${m.name} hasn't updated in $hours h",
                    "${m.name}'s phone may be off, out of signal, or Pod may have been stopped. Last seen ${hours}+ hours ago.",
                 memberId = m.deviceId,
                )
            }
        }
    }

    /** Decrypts someone's location history for the last [hours]. */
    suspend fun loadTrail(memberId: String, hours: Int): Result<List<LocPayload>> = runCatching {
      if (demo) return@runCatching demoTrails[memberId].orEmpty()
      withContext(Dispatchers.Default) {
        val fam = family()
        val member = _state.value.members[memberId] ?: throw IllegalStateException("Unknown member")
        val pub = PodCrypto.unb64(member.signPub)
        var since = System.currentTimeMillis() / 1000 - hours * 3600L
        val out = mutableListOf<LocPayload>()
        val seenIds = HashSet<Long>()
        for (page in 0 until 8) {
            val r = api().history(signer(), memberId, Kinds.LOC, since)
            for (msg in r.messages) {
                if (!seenIds.add(msg.id)) continue
                val plain = PodCrypto.openMessage(PodCrypto.unb64(msg.blob), Kinds.LOC, fam.familyId, memberId, pub, ::keyFor) ?: continue
                runCatching { out += PodJson.decodeFromString<LocPayload>(plain.decodeToString()) }
            }
            if (r.messages.size < 5000) break
            // Same second as the last row may hold more rows: re-ask from it and skip ids already seen.
            since = r.messages.last().created_at
        }
        out.sortedBy { it.t }
      }
    }.recoverCatching { throw IOException(friendly(it)) }

    private fun notifyEvent(senderId: String, name: String, e: EventPayload) {
        if (fireOneTime(senderId, name, e)) return
        if (!wantsAlert(senderId, e)) return
        // Delivered late (phone was offline)? Say when it actually happened.
        val ago = System.currentTimeMillis() - e.t
        val at = if (ago > 5 * 60_000L) " (at ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(e.t))})" else ""
        when (e.type) {
            "arrive" -> Notifications.show(ctx, Notifications.CH_ALERTS, "$name arrived", "$name arrived at ${e.place ?: "a place"}$at.", memberId = senderId)
            "leave" -> Notifications.show(ctx, Notifications.CH_ALERTS, "$name left", "$name left ${e.place ?: "a place"}$at.", memberId = senderId)
            "sos" -> Notifications.show(
                ctx, Notifications.CH_SOS, "SOS from $name", "$name needs help$at. Open Pod to see where they are.",
                Notifications.sosId(senderId), memberId = senderId,
            )
            "checkin" -> Notifications.show(ctx, Notifications.CH_FAMILY, "$name checked in", "$name shared their location$at.", memberId = senderId)
            "paused" -> Notifications.show(ctx, Notifications.CH_FAMILY, "$name paused sharing", "$name turned off location sharing$at.", memberId = senderId)
            "resumed" -> Notifications.show(ctx, Notifications.CH_FAMILY, "$name is sharing again", "$name's location is being shared again$at.", memberId = senderId)
            "noperm" -> Notifications.show(
                ctx, Notifications.CH_FAMILY, "$name's phone stopped sharing",
                "Location permission was turned off on $name's phone$at. Ask them to open Pod to fix it.", memberId = senderId,
            )
        }
    }

    // ------------------------------------------------------------------------------------
    // Sync loop (runs inside the foreground service)
    // ------------------------------------------------------------------------------------

    /**
     * Keeps this phone in sync. Never throws (except cancellation); retries with
     * backoff on any failure, so a flaky network or a server reboot heals itself.
     */
    suspend fun runSync() {
        var backoffMs = 2_000L
        var needFull = true
        while (currentCoroutineContext().isActive) {
            if (!isInFamily) {
                delay(5_000)
                needFull = true
                continue
            }
            try {
                if (needFull) {
                    fullSync()
                    needFull = false
                }
                val r = api().messages(signer(), _state.value.cursor, POLL_WAIT_SECONDS)
                processMessages(r.messages, live = true, advanceCursor = true)
                if (r.cursor > _state.value.cursor) mutate { it.copy(cursor = r.cursor) }
                setConn { it.copy(online = true, lastSyncAt = System.currentTimeMillis(), error = null) }
                if (hasPendingWork) flushPending()
                retryRotationQuietly()
                backoffMs = 2_000L
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.deviceGone) {
                    handleRemoved()
                    return
                }
                setConn { it.copy(online = false, error = friendly(e)) }
                DiagLog.w("Sync error: ${friendly(e)}")
                needFull = true
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000)
            } catch (e: Exception) {
                setConn { it.copy(online = false, error = friendly(e)) }
                DiagLog.w("Sync error: ${friendly(e)}")
                needFull = e !is IOException
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000)
            }
        }
    }

    private suspend fun fullSync() {
        refreshFamily()
        val gotKey = syncKeys()
        if (_state.value.cursor == 0L) {
            val st = api().state(signer())
            processMessages(st.messages, live = false)
            mutate { it.copy(cursor = maxOf(it.cursor, st.cursor)) }
        } else {
            // Catch up on everything since we were last online.
            while (true) {
                val r = api().messages(signer(), _state.value.cursor, 0)
                processMessages(r.messages, live = true, advanceCursor = true)
                if (r.cursor > _state.value.cursor) mutate { it.copy(cursor = r.cursor) }
                if (!r.more) break
            }
        }
        if (hasFamilyKey && (gotKey || _state.value.profilePublishedEpoch < (newestKey()?.first ?: 0))) {
            publishProfile()
        }
        setConn { it.copy(online = true, lastSyncAt = System.currentTimeMillis(), error = null) }
        retryRotationQuietly()
        flushPending()
    }

    /** Admin: retry a saved key change that couldn't reach the server earlier. */
    private suspend fun retryRotationQuietly() {
        if (!isAdmin || secrets?.pendingRotation == null) return
        try {
            runPendingRotation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagLog.w("Key change still pending: ${friendly(e)}")
        }
    }
}
