package app.podfamily

import app.podfamily.crypto.PodCrypto
import app.podfamily.data.InviteLink
import app.podfamily.location.Geofence
import app.podfamily.data.Place
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.GeneralSecurityException

class PodCryptoTest {
    private val familyId = "fam123"

    @Test fun envelopeRoundTrip() {
        val admin = PodCrypto.newDeviceKeys()
        val kid = PodCrypto.newDeviceKeys()
        val key = PodCrypto.newFamilyKey()
        val env = PodCrypto.sealEnvelope(key, 3, familyId, kid.deviceId, kid.boxPub, admin.signSeed)
        val opened = PodCrypto.openEnvelope(env, 3, familyId, kid.deviceId, kid.boxPriv, kid.boxPub, admin.signPub)
        assertArrayEquals(key, opened)
    }

    @Test fun envelopeRejectsWrongSignerAndWrongEpoch() {
        val admin = PodCrypto.newDeviceKeys()
        val evil = PodCrypto.newDeviceKeys()
        val kid = PodCrypto.newDeviceKeys()
        val env = PodCrypto.sealEnvelope(PodCrypto.newFamilyKey(), 1, familyId, kid.deviceId, kid.boxPub, evil.signSeed)
        try {
            PodCrypto.openEnvelope(env, 1, familyId, kid.deviceId, kid.boxPriv, kid.boxPub, admin.signPub)
            fail("accepted envelope from non-admin")
        } catch (e: GeneralSecurityException) { }
        val good = PodCrypto.sealEnvelope(PodCrypto.newFamilyKey(), 1, familyId, kid.deviceId, kid.boxPub, admin.signSeed)
        try {
            PodCrypto.openEnvelope(good, 2, familyId, kid.deviceId, kid.boxPriv, kid.boxPub, admin.signPub)
            fail("accepted envelope for wrong epoch")
        } catch (e: GeneralSecurityException) { }
    }

    @Test fun epochIsUnsigned32Bit() {
        val alex = PodCrypto.newDeviceKeys()
        val key = PodCrypto.newFamilyKey()
        val big = 3_000_000_000L // above Int.MAX_VALUE: must survive the round trip
        val blob = PodCrypto.sealMessage("x".toByteArray(), "loc", familyId, big, key, alex.deviceId, alex.signSeed)
        assertEquals(big, PodCrypto.messageEpoch(blob))
        assertNotNull(PodCrypto.openMessage(blob, "loc", familyId, alex.deviceId, alex.signPub) { if (it == big) key else null })
        try {
            PodCrypto.sealMessage("x".toByteArray(), "loc", familyId, 0x1_0000_0000L, key, alex.deviceId, alex.signSeed)
            fail("accepted an epoch that doesn't fit in 32 bits")
        } catch (e: IllegalArgumentException) { }
    }

    @Test fun messageRoundTripAndTamper() {
        val alex = PodCrypto.newDeviceKeys()
        val sam = PodCrypto.newDeviceKeys()
        val key = PodCrypto.newFamilyKey()
        val text = """{"lat":45.52,"lon":-122.68}""".toByteArray()
        val blob = PodCrypto.sealMessage(text, "loc", familyId, 5, key, alex.deviceId, alex.signSeed)
        assertEquals(5L, PodCrypto.messageEpoch(blob))
        assertEquals(1 + 4 + 64 + 24 + 256 + 16, blob.size) // padded to the 256-byte bucket
        val out = PodCrypto.openMessage(blob, "loc", familyId, alex.deviceId, alex.signPub) { if (it == 5L) key else null }
        assertArrayEquals(text, out)
        // Sam can't pretend to be Alex.
        assertNull(PodCrypto.openMessage(blob, "loc", familyId, alex.deviceId, sam.signPub) { key })
        // Kind is bound: a location can't be replayed as an event.
        assertNull(PodCrypto.openMessage(blob, "event", familyId, alex.deviceId, alex.signPub) { key })
        // Flipped bit is rejected.
        val bad = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(PodCrypto.openMessage(bad, "loc", familyId, alex.deviceId, alex.signPub) { key })
        // Unknown epoch.
        assertNull(PodCrypto.openMessage(blob, "loc", familyId, alex.deviceId, alex.signPub) { null })
    }

    @Test fun joinProof() {
        val secret = PodCrypto.randomBytes(32)
        val k = PodCrypto.newDeviceKeys()
        val proof = PodCrypto.joinProof(secret, familyId, k.signPub, k.boxPub)
        assertTrue(PodCrypto.verifyJoinProof(secret, familyId, k.signPub, k.boxPub, proof))
        val other = PodCrypto.newDeviceKeys()
        assertFalse(PodCrypto.verifyJoinProof(secret, familyId, other.signPub, other.boxPub, proof))
        assertFalse(PodCrypto.verifyJoinProof(PodCrypto.randomBytes(32), familyId, k.signPub, k.boxPub, proof))
    }

    @Test fun padding() {
        for (n in listOf(0, 1, 251, 252, 253, 1000, 5000)) {
            val data = PodCrypto.randomBytes(n)
            val p = PodCrypto.pad(data)
            assertTrue(p.size >= n + 4)
            assertArrayEquals(data, PodCrypto.unpad(p))
        }
    }

    @Test fun inviteLinkRoundTrip() {
        val l = InviteLink("https://pod.example.com", "fam", "tok", "sec", "adm")
        val parsed = InviteLink.parse(l.toUri())
        assertEquals(l, parsed)
        assertNull(InviteLink.parse("pod://join?v=1&u=http%3A%2F%2Finsecure&f=a&t=b&s=c&a=d"))
        assertNull(InviteLink.parse("https://evil.example.com"))
    }

    @Test fun deviceIdIsStable() {
        val k = PodCrypto.newDeviceKeys()
        assertEquals(22, k.deviceId.length)
        assertEquals(k.deviceId, PodCrypto.deviceId(k.signPub.copyOf()))
    }

    @Test fun geofenceHysteresis() {
        val home = Place("h", "Home", 45.5152, -122.6784, 150)
        var inside = emptySet<String>()
        // Arrive
        Geofence.evaluate(45.5152, -122.6784, 20f, listOf(home), inside).let { (s, t) -> inside = s; assertEquals(1, t.size); assertTrue(t[0].arrived) }
        // Small move just outside the radius: still inside (hysteresis)
        Geofence.evaluate(45.5152 + 0.0015, -122.6784, 20f, listOf(home), inside).let { (s, t) -> inside = s; assertTrue(t.isEmpty()) }
        // Clearly gone
        Geofence.evaluate(45.527, -122.6784, 20f, listOf(home), inside).let { (s, t) -> inside = s; assertNotNull(t.singleOrNull { !it.arrived }) }
        assertTrue(inside.isEmpty())
    }
}
