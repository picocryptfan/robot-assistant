package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0 audit ipc-1 and ipc-2: another app's showAssist (SHOW_SOURCE_APPLICATION, any Bundle it likes) can't run the brief,
 * raise Ready cards, wipe the parked list, open a headset or call session or fake the camera's return; Robot's own app gets
 * at most "from the app" and a conversation, with its single-use nonce.
 */
class ShowTrustTest {
    private val token = "0b5c1c4e-6d0a-4f7e-9d2b-3f0a1e2d3c4b"
    private val forged = setOf("robot_source", "robot_routine", "robot_ready", "robot_parked", "robot_announce", "robot_handsfree_call",
        "robot_headset_entry", "robot_headset_locked", "robot_conversation", "robot_vis", "robot_nonce", "android.intent.extra.ASSIST_PACKAGE")

    @Test fun anAppsShowKeepsNothingWithoutTheNonce() {
        var asked = 0
        // Even with the assistant's token copied in: SHOW_SOURCE_APPLICATION is never the assistant's own show.
        val from = ShowTrust.from(fromApplication = true, token = token, expected = token, nonce = null) { asked++; true }
        assertEquals(ShowTrust.From.OTHER, from)
        assertEquals(0, asked)
        assertEquals(emptySet<String>(), ShowTrust.keep(forged, from))
        assertNull(ShowTrust.source(from, "wake"))
        assertEquals(ShowTrust.From.OTHER, ShowTrust.from(true, null, token, "guess") { false })
    }

    @Test fun robotsAppKeepsOnlyTheAppSourceAndAConversation() {
        val from = ShowTrust.from(true, null, token, "n") { it == "n" }
        assertEquals(ShowTrust.From.ROBOT_APP, from)
        assertEquals(setOf("robot_source", "robot_conversation"), ShowTrust.keep(forged, from))
        // A source it names (wake, capture, headset) is still "from the app": no spoken replies, no capture return.
        assertEquals("app", ShowTrust.source(from, "wake"))
        assertEquals("app", ShowTrust.source(from, "capture"))
    }

    @Test fun theAssistantsOwnShowKeepsItsArgumentsButNeverTheTokenOrNonce() {
        val from = ShowTrust.from(false, token, token, null) { true }
        assertEquals(ShowTrust.From.ASSISTANT, from)
        assertEquals(forged.filter { it.startsWith("robot_") }.toSet() - "robot_vis" - "robot_nonce", ShowTrust.keep(forged, from))
        assertEquals("capture", ShowTrust.source(from, "capture"))
    }

    @Test fun noTokenOrAWrongOneIsAPlainPanel() {
        // The corner swipe, the assist gesture: no SHOW_SOURCE_APPLICATION and no token.
        assertEquals(ShowTrust.From.OTHER, ShowTrust.from(false, null, token, null) { true })
        assertEquals(ShowTrust.From.OTHER, ShowTrust.from(false, token.dropLast(1) + "5", token, null) { true })
        assertEquals(ShowTrust.From.OTHER, ShowTrust.from(false, "", token, null) { true })
    }

    @Test fun theNonceIsFreshMatchingAndWellFormed() {
        val n = "0123456789abcdef0123456789abcdef"
        assertTrue(ShowTrust.NonceCheck.valid(n, 1_000, n, 1_000 + ShowTrust.NonceCheck.TTL_MS))
        assertFalse(ShowTrust.NonceCheck.valid(n, 1_000, n, 1_001 + ShowTrust.NonceCheck.TTL_MS))
        assertFalse(ShowTrust.NonceCheck.valid(n, 1_000, n, 999))
        assertFalse(ShowTrust.NonceCheck.valid(n, 1_000, n.reversed(), 1_000))
        assertFalse(ShowTrust.NonceCheck.valid(null, 1_000, n, 1_000))
        assertFalse(ShowTrust.NonceCheck.valid("short", 1_000, "short", 1_000))
        assertEquals(n to 42L, ShowTrust.NonceCheck.decode(ShowTrust.NonceCheck.encode(n, 42)))
        assertNull(ShowTrust.NonceCheck.decode("garbage"))
        assertNull(ShowTrust.NonceCheck.decode(null))
    }
}
