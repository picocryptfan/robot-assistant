// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.ask

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import app.heyrobot.core.files.ShareRefusal
import app.heyrobot.core.files.ShareRules
import android.os.Bundle
import app.heyrobot.assistant.RobotVoiceService
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.ask.AskCaps
import app.heyrobot.core.ask.AskHeard
import app.heyrobot.core.ask.AskRefusal
import app.heyrobot.core.ask.AskShown
import app.heyrobot.core.ask.SharedKind
import app.heyrobot.core.i18n.EverydayStrings
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ask Robot's door (everyday-set-plan.md §7, AR1 to AR7's rules; E7), through AskModel with a phone of the test's own:
 * text goes straight to the panel as a one-time id; a photo and a voice message are read once, capped, then decoded; a
 * phone lock, the locked phone, the switch off and Robot not being the assistant refuse before anything is read; a
 * voice message's container is sniffed before anything decodes it; nothing reaches the panel after Cancel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AskRobotTest {
    @Test fun aSharedLinkToRobotsOwnProviderIsRefusedHoweverItsNameIsWritten() {
        val own = { a: String -> a == "app.heyrobot" || a.startsWith("app.heyrobot.") }
        fun check(uri: String) = ShareRules.check(sharedRef(Uri.parse(uri)), own)
        // E9's item 2: ContentResolver opens the decoded name, so the rule has to see that one.
        for (name in listOf("exports", "capture", "scan", "files_out")) {
            assertEquals(ShareRefusal.OWN, check("content://app.heyrobot.$name/f"))
            assertEquals(ShareRefusal.OWN, check("content://app%2Eheyrobot.$name/f"))
        }
        // Another app's, written in escapes: no provider's name needs them (F50).
        assertEquals(ShareRefusal.NO_PROVIDER, check("content://org%2Eexample.photos/1"))
        assertEquals(null, check("content://org.example.photos/1"))
    }

    private val png = Uri.parse("content://org.example.photos/1")
    private val ogg = Uri.parse("content://org.example.chat/voice/2")
    private val oggBytes = "OggS".toByteArray() + ByteArray(400)

    /** The phone, as the test says: what each answer is, and what was asked of it. */
    private class Phone : AskModel.Port {
        var gate: AskRefusal? = null
        /** A phone lock that starts while the sender hands its bytes over. */
        var gateAfterRead: AskRefusal? = null
        var assistant: Boolean? = true
        var allowed = true
        var read: AskModel.Read = AskModel.Read.Bytes(ByteArray(10))
        var photo: SharedPhoto.Read = SharedPhoto.Read.Ok(byteArrayOf(-1, -40, -1), Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
        var speech: AskRefusal? = null
        var heard: AskModel.Heard = AskModel.Heard.Words("I'm on my way.", 42, false)
        var openOk = true
        var onRead: () -> Unit = {}
        val opened = ArrayList<SharedItem>()
        val reads = ArrayList<Pair<Uri, Int>>()
        var transcribed = 0
        var photos = 0
        val logged = ArrayList<Triple<VoiceEvent, Int, Int>>()
        private var readDone = false

        override fun gate(): AskRefusal? = if (readDone) gateAfterRead ?: gate else gate
        override fun assistant(): Boolean? = assistant
        override fun allowed(uri: Uri): Boolean = allowed
        override fun read(uri: Uri, cap: Int): AskModel.Read {
            reads += uri to cap
            onRead()
            readDone = true
            return read
        }
        override fun photo(bytes: ByteArray): SharedPhoto.Read { photos++; return photo }
        override fun speech(): AskRefusal? = speech
        override fun transcribe(bytes: ByteArray, cancelled: () -> Boolean): AskModel.Heard { transcribed++; return heard }
        override fun open(item: SharedItem): Boolean { if (openOk) opened += item; return openOk }
        override fun log(event: VoiceEvent, a: Int, b: Int) { logged += Triple(event, a, b) }
    }

    private val phone = Phone()

    /** The model with its work done on the calling thread. */
    private fun model(req: AskRequest) = AskModel(phone, req) { it.run() }

    private fun text(s: CharSequence?) = AskRequest(SharedKind.TEXT, s, null)
    private fun refusedWith(m: AskModel) = ((m.ui as AskUi.Refused).why).render(Langs.EN)

    @Before fun clear() {
        AskInbox.clear()
        VoiceLog.resetForTests()
    }

    @Test fun theIntentIsReadAsTheSenderSentIt() {
        val sel = AskRequest.of(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, "The meeting moved to 3."))
        assertEquals(SharedKind.TEXT, sel.kind)
        assertEquals("The meeting moved to 3.", sel.text.toString())
        val sent = AskRequest.of(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hello"))
        assertEquals(SharedKind.TEXT, sent.kind)
        assertEquals("hello", sent.text.toString())
        val photo = AskRequest.of(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, png))
        assertEquals(SharedKind.PHOTO, photo.kind)
        assertEquals(png, photo.uri)
        // Only in the clip, as some senders do.
        val clip = AskRequest.of(Intent(Intent.ACTION_SEND).setType("audio/ogg").apply { clipData = ClipData.newRawUri("", ogg) })
        assertEquals(SharedKind.VOICE, clip.kind)
        assertEquals(ogg, clip.uri)
        // A stream that isn't a URI is nothing; an image claim with nothing to read is nothing; several files are Files'.
        assertNull(AskRequest.of(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, "content://org.example/3")).kind)
        assertNull(AskRequest.of(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png").putExtra(Intent.EXTRA_STREAM, png)).kind)
        assertNull(AskRequest.of(Intent(Intent.ACTION_VIEW, png)).kind)
        assertNull(AskRequest.of(null).kind)
    }

    @Test fun textGoesStraightToThePanelAsAnId() {
        val m = model(text("The meeting moved to 3."))
        m.start()
        assertSame(AskUi.Done, m.ui)
        val item = phone.opened.single()
        assertEquals(SharedKind.TEXT, item.kind)
        assertEquals("The meeting moved to 3.", item.text)
        assertFalse(item.cut)
        assertEquals(emptyList<Pair<Uri, Int>>(), phone.reads)
        assertEquals(listOf(Triple(VoiceEvent.ASK_SHARED, SharedKind.TEXT.id, AskShown.SHOWN.id)), phone.logged)
    }

    @Test fun textPastTheCapIsCutAndSaysSo() {
        val m = model(text("a".repeat(AskCaps.MAX_TEXT + 100)))
        m.start()
        val item = phone.opened.single()
        assertEquals(AskCaps.MAX_TEXT, item.text!!.length)
        assertTrue(item.cut)
        assertEquals(AskShown.CUT.id, phone.logged.single().third)
        assertEquals("Robot took the first 20,000 characters.", cutText(item)!!.render(Langs.EN))
    }

    @Test fun nothingButSpacesIsNothing() {
        val m = model(text(" \n "))
        m.start()
        assertEquals("There's nothing here Robot can take.", refusedWith(m))
        assertEquals(listOf(Triple(VoiceEvent.ASK_REFUSED, AskRefusal.NOTHING.id, SharedKind.TEXT.id)), phone.logged)
        assertTrue(phone.opened.isEmpty())
    }

    /** AR5, and the lock screen and the switch: refused before anything of the share is read. */
    @Test fun aPhoneLockTheLockedPhoneAndTheSwitchRefuseFirst() {
        for ((why, words) in listOf(AskRefusal.PHONE_LOCK to "Robot can't take things from other apps during a phone lock.",
                AskRefusal.LOCKED to "Unlock your phone to use Ask Robot.",
                AskRefusal.OFF to "Ask Robot in other apps is off. You can turn it on in Settings › Actions.")) {
            for (req in listOf(text("hi"), AskRequest(SharedKind.PHOTO, null, png), AskRequest(SharedKind.VOICE, null, ogg))) {
                val p = Phone().apply { gate = why }
                val m = AskModel(p, req) { it.run() }
                m.start()
                assertEquals(words, (m.ui as AskUi.Refused).why.render(Langs.EN))
                assertTrue(p.reads.isEmpty() && p.opened.isEmpty() && p.photos == 0 && p.transcribed == 0)
                assertEquals(listOf(Triple(VoiceEvent.ASK_REFUSED, why.id, req.kind!!.id)), p.logged)
            }
        }
    }

    /** AR6: Robot not your assistant: the line and the way to its setup, nothing read. */
    @Test fun robotNotYourAssistantSaysSo() {
        phone.assistant = false
        val m = model(AskRequest(SharedKind.PHOTO, null, png))
        m.start()
        assertSame(AskUi.NotAssistant, m.ui)
        assertTrue(phone.reads.isEmpty())
        assertEquals(listOf(Triple(VoiceEvent.ASK_REFUSED, AskRefusal.NOT_ASSISTANT.id, SharedKind.PHOTO.id)), phone.logged)
        assertEquals("Make Robot your assistant to use Ask Robot.", EverydayStrings.askNotAssistant().render(Langs.EN))
        // The role held, the service not running yet (just after a restart): try again, not "make Robot your assistant".
        val p = Phone().apply { assistant = null }
        val n = AskModel(p, text("hi")) { it.run() }
        n.start()
        assertEquals("Robot's assistant isn't running yet. Try again in a moment.", (n.ui as AskUi.Refused).why.render(Langs.EN))
        assertTrue(p.opened.isEmpty())
    }

    /** AR3: a photo read once (the image cap), decoded by :files, handed over. */
    @Test fun aPhotoIsReadOnceWithItsCap() {
        val m = model(AskRequest(SharedKind.PHOTO, null, png))
        m.start()
        assertEquals(listOf(png to AskCaps.MAX_IMAGE_BYTES), phone.reads)
        assertEquals(1, phone.photos)
        val item = phone.opened.single()
        assertEquals(SharedKind.PHOTO, item.kind)
        assertTrue(item.jpeg != null && item.thumb != null && item.text == null)
        assertSame(AskUi.Done, m.ui)
        assertEquals("Shared photo", chipText(item).render(Langs.EN))
    }

    @Test fun aPhotoRefusedOnTheWaySaysWhy() {
        val cases = listOf(
            Phone().apply { allowed = false } to "Robot can't take this from that app.",
            Phone().apply { read = AskModel.Read.TooLarge } to "This photo is over 20 MB, more than Robot takes.",
            Phone().apply { read = AskModel.Read.TimedOut } to "The other app didn't hand it over in time. Try again.",
            Phone().apply { read = AskModel.Read.Unreadable } to "Robot couldn't read this photo.",
            Phone().apply { photo = SharedPhoto.Read.Refused(AskRefusal.WRONG_TYPE) } to "Robot can't read this kind of file.",
            Phone().apply { photo = SharedPhoto.Read.Refused(AskRefusal.UNREADABLE) } to "Robot couldn't read this photo.",
            // A phone lock that started while the sender took its time.
            Phone().apply { gateAfterRead = AskRefusal.PHONE_LOCK } to "Robot can't take things from other apps during a phone lock.",
        )
        for ((p, words) in cases) {
            val m = AskModel(p, AskRequest(SharedKind.PHOTO, null, png)) { it.run() }
            m.start()
            assertEquals(words, (m.ui as AskUi.Refused).why.render(Langs.EN))
            assertTrue(p.opened.isEmpty())
            assertEquals(VoiceEvent.ASK_REFUSED, p.logged.last().first)
        }
    }

    /** AR2: a voice message: sniffed, written down, handed over as its words with its length. */
    @Test fun aVoiceMessageIsWrittenDown() {
        phone.read = AskModel.Read.Bytes(oggBytes)
        val m = model(AskRequest(SharedKind.VOICE, null, ogg))
        m.start()
        assertEquals(listOf(ogg to AskCaps.MAX_AUDIO_BYTES), phone.reads)
        val item = phone.opened.single()
        assertEquals(SharedKind.VOICE, item.kind)
        assertEquals("I'm on my way.", item.text)
        assertEquals(42, item.seconds)
        assertEquals("Shared voice message (0:42)", chipText(item).render(Langs.EN))
        assertEquals(listOf(Triple(VoiceEvent.ASK_TRANSCRIBE, 3, AskHeard.WORDS.id), Triple(VoiceEvent.ASK_SHARED, SharedKind.VOICE.id, AskShown.SHOWN.id)), phone.logged)
        // Longer than 3 minutes: the first 3, and it says so.
        val p = Phone().apply { read = AskModel.Read.Bytes(oggBytes); heard = AskModel.Heard.Words("Long.", 300, true) }
        AskModel(p, AskRequest(SharedKind.VOICE, null, ogg)) { it.run() }.start()
        assertTrue(p.opened.single().cut)
        assertEquals("Robot wrote down the first 3 minutes.", cutText(p.opened.single())!!.render(Langs.EN))
        assertEquals(Triple(VoiceEvent.ASK_TRANSCRIBE, 6, AskHeard.CUT.id), p.logged.first())
    }

    @Test fun aVoiceMessageIsSniffedBeforeAnythingDecodesIt() {
        // A JPEG claimed as audio: never decoded.
        phone.read = AskModel.Read.Bytes(byteArrayOf(-1, -40, -1, -32) + ByteArray(100))
        val m = model(AskRequest(SharedKind.VOICE, null, ogg))
        m.start()
        assertEquals("Robot can't read this kind of file.", refusedWith(m))
        assertEquals(0, phone.transcribed)
        assertTrue(phone.opened.isEmpty())
    }

    @Test fun noSpeechModelOrATranscriptionRunningRefusesBeforeReading() {
        for ((why, words) in listOf(AskRefusal.NO_SPEECH_MODEL to "Robot needs its speech recognition model to write down a voice message. You can download it in Settings › Model.",
                AskRefusal.BUSY to "A transcription is running. Stop it, then share the voice message again.")) {
            val p = Phone().apply { speech = why }
            val m = AskModel(p, AskRequest(SharedKind.VOICE, null, ogg)) { it.run() }
            m.start()
            assertEquals(words, (m.ui as AskUi.Refused).why.render(Langs.EN))
            assertTrue(p.reads.isEmpty() && p.transcribed == 0)
        }
    }

    @Test fun noWordsOrAFailedDecodeSaySo() {
        for ((heard, words) in listOf(AskModel.Heard.Not(AskRefusal.NO_WORDS, AskHeard.NO_WORDS, 4) to "Robot heard no words in this voice message.",
                AskModel.Heard.Not(AskRefusal.UNREADABLE, AskHeard.FAILED) to "Robot couldn't play this voice message.",
                AskModel.Heard.Not(AskRefusal.TOO_LARGE, null) to "This voice message is over 25 MB, more than Robot takes.")) {
            val p = Phone().apply { read = AskModel.Read.Bytes(oggBytes); this.heard = heard }
            val m = AskModel(p, AskRequest(SharedKind.VOICE, null, ogg)) { it.run() }
            m.start()
            assertEquals(words, (m.ui as AskUi.Refused).why.render(Langs.EN))
            assertTrue(p.opened.isEmpty())
        }
    }

    /** Cancel (or Home) while the sender hands its bytes over: nothing more is read, nothing reaches the panel. */
    @Test fun closingLetsItsThreadGoAndAStartAfterThatDoesNothing() {
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        val m = AskModel(phone, text("hi"), exec)
        m.close()
        assertTrue(exec.isShutdown)
        m.start()
        assertTrue(m.ui is AskUi.Done)
    }

    @Test fun cancelStopsEverything() {
        lateinit var m: AskModel
        phone.read = AskModel.Read.Bytes(oggBytes)
        phone.onRead = { m.close() }
        m = model(AskRequest(SharedKind.VOICE, null, ogg))
        m.start()
        assertEquals(0, phone.transcribed)
        assertTrue(phone.opened.isEmpty())
        assertSame(AskUi.Done, m.ui)
        // A cancelled transcription isn't a refusal on screen either.
        val p = Phone().apply { read = AskModel.Read.Bytes(oggBytes); heard = AskModel.Heard.Not(AskRefusal.UNREADABLE, AskHeard.CANCELLED) }
        val n = AskModel(p, AskRequest(SharedKind.VOICE, null, ogg)) { it.run() }
        n.start()
        assertTrue(n.ui !is AskUi.Refused)
        assertEquals(listOf(Triple(VoiceEvent.ASK_TRANSCRIBE, 1, AskHeard.CANCELLED.id)), p.logged)
    }

    @Test fun aPanelThatCantOpenSaysSo() {
        phone.openOk = false
        val m = model(text("hi"))
        m.start()
        assertEquals("Robot couldn't open its panel. Try again.", refusedWith(m))
        assertEquals(listOf(Triple(VoiceEvent.ASK_SHARED, SharedKind.TEXT.id, AskShown.NOT_SHOWN.id)), phone.logged)
    }

    /** One waits at a time, under an id only the panel's show carries, taken once, dropped when it waited too long. */
    @Test fun theInboxHoldsOneShareOnce() {
        val a = SharedItem(SharedKind.TEXT, "first")
        val b = SharedItem(SharedKind.TEXT, "second")
        val idA = AskInbox.put(a, 1_000)
        assertTrue(app.heyrobot.assistant.PanelLaunchActivity.sharedId(idA))
        val idB = AskInbox.put(b, 2_000)
        assertNull(AskInbox.take(idA, 2_001))
        assertNull(AskInbox.take(null, 2_001))
        assertSame(b, AskInbox.take(idB, 2_002))
        assertNull(AskInbox.take(idB, 2_003))
        val idC = AskInbox.put(a, 0)
        assertNull(AskInbox.take(idC, AskCaps.WAIT_MS + 1))
        assertFalse(AskInbox.waiting())
    }

    /** The panel takes it only from Ask Robot's own show, never over the lock screen nor during a phone lock. */
    @Test fun thePanelTakesItOnlyUnlocked() {
        fun args(id: String) = Bundle().apply { putString(RobotVoiceService.ARG_SHARED, id) }
        val item = SharedItem(SharedKind.TEXT, "hi")
        var id = AskInbox.put(item, 10)
        assertNull(AskPanel.take(RobotVoiceService.SRC_APP, args(id), locked = false, phoneLock = { false }, now = 11))
        assertSame(item, AskPanel.take(RobotVoiceService.SRC_SHARE, args(id), locked = false, phoneLock = { false }, now = 12))
        id = AskInbox.put(item, 20)
        assertNull(AskPanel.take(RobotVoiceService.SRC_SHARE, args(id), locked = true, phoneLock = { false }, now = 21))
        assertFalse(AskInbox.waiting())
        id = AskInbox.put(item, 30)
        assertNull(AskPanel.take(RobotVoiceService.SRC_SHARE, args(id), locked = false, phoneLock = { true }, now = 31))
        assertFalse(AskInbox.waiting())
        val refused = VoiceLog.entries().filter { it.event == VoiceEvent.ASK_REFUSED }.map { it.a to it.b }
        assertEquals(setOf(AskRefusal.LOCKED.id to SharedKind.TEXT.id, AskRefusal.PHONE_LOCK.id to SharedKind.TEXT.id), refused.toSet())
        assertEquals(2, refused.size)
    }

    /** Only the two actions: anything else finishes before Robot does anything (and no result is ever set: Q5). */
    @Test fun theActivityTakesOnlyTheTwoActions() {
        for (i in listOf(Intent(Intent.ACTION_VIEW, png), Intent(), Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*"))) {
            val c = Robolectric.buildActivity(AskRobotActivity::class.java, i).create()
            assertTrue(c.get().isFinishing)
            assertEquals(android.app.Activity.RESULT_CANCELED, org.robolectric.Shadows.shadowOf(c.get()).resultCode)
            assertNull(org.robolectric.Shadows.shadowOf(c.get()).resultIntent)
            c.destroy()
        }
    }
}
