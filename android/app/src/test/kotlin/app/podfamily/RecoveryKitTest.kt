package app.podfamily

import app.podfamily.crypto.RecoveryKit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test
import java.security.GeneralSecurityException

class RecoveryKitTest {
    private val payload = """{"hello":"family"}""".toByteArray()

    @Test fun roundTrip() {
        val file = RecoveryKit.encrypt(payload, "correct horse battery", iterations = 100_000)
        assertArrayEquals(payload, RecoveryKit.decrypt(file, "correct horse battery"))
    }

    @Test fun wrongPassphraseRejected() {
        val file = RecoveryKit.encrypt(payload, "correct horse battery", iterations = 100_000)
        try {
            RecoveryKit.decrypt(file, "wrong horse battery")
            fail("accepted wrong passphrase")
        } catch (e: RecoveryKit.WrongPassphrase) { }
    }

    @Test fun tamperedFileRejected() {
        val file = RecoveryKit.encrypt(payload, "correct horse battery", iterations = 100_000)
        val bad = file.replace("\"iter\": 100000", "\"iter\": 100001")
        try {
            RecoveryKit.decrypt(bad, "correct horse battery")
            fail("accepted tampered file")
        } catch (e: GeneralSecurityException) { }
    }

    @Test fun shortPassphraseRefused() {
        try {
            RecoveryKit.encrypt(payload, "short", iterations = 100_000)
            fail("accepted short passphrase")
        } catch (e: IllegalArgumentException) { }
    }
}
