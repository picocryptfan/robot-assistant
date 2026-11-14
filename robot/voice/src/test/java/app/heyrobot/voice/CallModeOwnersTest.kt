// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.4.2, the 8 Nov car report ("media cannot be played anywhere on the device" until Bluetooth went off and on): the
 * phone's communication mode as Robot's owners take and let go of it, on a fake AudioManager. Every way out (done, a
 * refused or failed take, a second release) leaves the normal mode and no communication device; two overlapping owners
 * keep it until the last one goes; a call already on, or one that came in meanwhile, keeps its mode, and Robot's own
 * request is withdrawn so the mode is normal once that call ends.
 */
class CallModeOwnersTest {
    /** Android keeps one mode request per app: Robot's own ([robot]) and a call's ([call]); the call's applies while it's on. */
    private class FakeAudio : CallModeOwners.Port<String> {
        var robot = AudioManager.MODE_NORMAL
        var call: Int? = null
        val now: Int get() = call ?: robot
        var device: String? = null
        var refuse = false
        var throwOnDevice = false
        var throwOnMode = false
        var speaker = 0
        override fun mode() = now
        override fun setMode(m: Int) { if (throwOnMode) throw IllegalStateException("mode"); robot = m }
        override fun setDevice(d: String): Boolean {
            if (throwOnDevice) throw IllegalArgumentException("device")
            if (refuse) return false
            device = d
            return true
        }
        override fun speakerUnlessHeadset() { speaker++; if (device == null) device = "speaker" }
        override fun clearDevice() { device = null }
        override fun warn(where: String, t: Throwable?) {}
    }

    private val audio = FakeAudio()
    private val owners = CallModeOwners<String>()
    private val car = Any()
    private val tts = Any()

    private fun assertNormal() {
        assertEquals(AudioManager.MODE_NORMAL, audio.now)
        assertNull(audio.device)
        assertFalse(owners.held)
    }

    @Test fun aRouteTakenAndReleasedLeavesTheNormalMode() {
        assertTrue(owners.route(audio, car, "sco"))
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.now)
        assertEquals("sco", audio.device)
        assertTrue(owners.held)
        owners.end(audio, car)
        assertNormal()
    }

    @Test fun aRefusedRouteHoldsNothing() {
        audio.refuse = true
        assertFalse(owners.route(audio, car, "sco"))
        assertNormal()
    }

    @Test fun aRouteThatThrowsHoldsNothing() {
        audio.throwOnDevice = true
        assertFalse(owners.route(audio, car, "sco"))
        assertNormal()
    }

    @Test fun aReleaseAfterARefusalOrTwiceIsHarmless() {
        audio.refuse = true
        owners.route(audio, car, "sco")
        owners.end(audio, car)
        assertNormal()
        audio.refuse = false
        owners.route(audio, car, "sco")
        owners.end(audio, car)
        owners.end(audio, car)
        assertNormal()
    }

    @Test fun aRefusedRouteLeavesAnotherOwnersHold() {
        assertTrue(owners.begin(audio, tts))
        audio.refuse = true
        assertFalse(owners.route(audio, car, "sco"))
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.now)
        assertTrue(owners.held)
        owners.end(audio, tts)
        assertNormal()
    }

    @Test fun twoOverlappingOwnersKeepTheModeUntilTheLastGoes() {
        assertTrue(owners.route(audio, car, "sco"))
        assertTrue(owners.begin(audio, tts))
        owners.end(audio, car)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.now)
        assertTrue(owners.held)
        owners.end(audio, tts)
        assertNormal()
        // The other order.
        assertTrue(owners.begin(audio, tts))
        assertTrue(owners.route(audio, car, "sco"))
        owners.end(audio, tts)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.now)
        owners.end(audio, car)
        assertNormal()
    }

    @Test fun callAudioBeginsOnTheSpeakerAndEnds() {
        assertTrue(owners.begin(audio, tts))
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.now)
        assertEquals(1, audio.speaker)
        assertTrue(owners.begin(audio, tts))
        owners.end(audio, tts)
        assertNormal()
    }

    @Test fun aBeginThatThrowsHoldsNothing() {
        audio.throwOnMode = true
        assertFalse(owners.begin(audio, tts))
        audio.throwOnMode = false
        assertNormal()
    }

    @Test fun aCallAlreadyOnIsNeverTouched() {
        for (m in listOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE)) {
            audio.call = m
            audio.device = "their headset"
            assertFalse(owners.route(audio, car, "sco"))
            assertFalse(owners.begin(audio, tts))
            owners.end(audio, car)
            owners.end(audio, tts)
            assertEquals(m, audio.now)
            assertEquals(AudioManager.MODE_NORMAL, audio.robot)
            assertEquals("their headset", audio.device)
            assertFalse(owners.held)
        }
    }

    @Test fun aCallThatCameInMeanwhileKeepsItsModeAndRobotsRequestGoes() {
        assertTrue(owners.route(audio, car, "sco"))
        audio.call = AudioManager.MODE_IN_CALL
        // No owner, old or new, switches the call to communication mode.
        assertFalse(owners.route(audio, car, "sco"))
        assertFalse(owners.begin(audio, tts))
        assertEquals(AudioManager.MODE_IN_CALL, audio.now)
        owners.end(audio, car)
        assertEquals(AudioManager.MODE_IN_CALL, audio.now)
        assertEquals(AudioManager.MODE_NORMAL, audio.robot)
        assertFalse(owners.held)
        // The call ends: Robot's request is gone, so nothing of Robot's comes back.
        audio.call = null
        assertNormal()
    }

    @Test fun aRefusedRerouteKeepsTheOwnersEarlierHold() {
        assertTrue(owners.route(audio, car, "sco"))
        audio.refuse = true
        assertFalse(owners.route(audio, car, "le audio"))
        assertTrue(owners.held)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.now)
        assertEquals("sco", audio.device)
        owners.end(audio, car)
        assertNormal()
    }
}
