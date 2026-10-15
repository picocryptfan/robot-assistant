// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 0.7 after the review: an app you turn on in Settings › Messages is pinned to its signing key, package by package, and a
 * notification from a package signed by anyone else is ignored; a messages.json from before the pins trusts each package of
 * an app that was on at its first notification. messages.json changes one at a time, under its lock, read from the file.
 */
class MessageSignersTest {
    private val signal = "a".repeat(64)
    private val other = "b".repeat(64)
    private val fdroid = "c".repeat(64)

    @Test fun aPackageIsReadOnlyWhileSignedAsWhenYouTurnedItOn() {
        val pin = AppPin(setOf(signal), "com.android.vending")
        assertEquals(PinVerdict.TRUST, MessageSigners.verdict(pin, SignerNow(setOf(signal)), pinnedStillSigns = false, firstUse = false))
        // Case aside, the same key.
        assertEquals(PinVerdict.TRUST, MessageSigners.verdict(pin, SignerNow(setOf(signal.uppercase())), false, false))
        // A look-alike under the same package name, signed by someone else: ignored, whatever "first use" says.
        assertEquals(PinVerdict.CHANGED, MessageSigners.verdict(pin, SignerNow(setOf(other)), false, firstUse = true))
        // A key rotated with Android's lineage: Android says the kept one still signs it.
        assertEquals(PinVerdict.TRUST, MessageSigners.verdict(pin, SignerNow(setOf(other)), pinnedStillSigns = true, firstUse = false))
        // Android can't say who signs it now.
        assertEquals(PinVerdict.CHANGED, MessageSigners.verdict(pin, SignerNow(null), false, false))
        assertEquals(PinVerdict.CHANGED, MessageSigners.verdict(pin, SignerNow(setOf(signal, other)), false, false))
        // No pin: installed after the app was turned on, unless the app was on before the pins (first use).
        assertEquals(PinVerdict.NOT_PINNED, MessageSigners.verdict(null, SignerNow(setOf(signal)), false, firstUse = false))
        assertEquals(PinVerdict.PIN_NOW, MessageSigners.verdict(null, SignerNow(setOf(signal)), false, firstUse = true))
        assertEquals(PinVerdict.NOT_PINNED, MessageSigners.verdict(null, SignerNow(emptySet()), false, firstUse = true))
        assertEquals(PinVerdict.NOT_PINNED, MessageSigners.verdict(null, null, false, firstUse = true))
    }

    @Test fun whatIsKeptAndSaid() {
        assertEquals(AppPin(setOf(signal, other), "org.fdroid.fdroid"), MessageSigners.pinOf(SignerNow(setOf(other.uppercase(), signal, "not-hex"), "org.fdroid.fdroid")))
        assertNull(MessageSigners.pinOf(SignerNow(setOf("12ab"))))
        assertNull(MessageSigners.pinOf(SignerNow(null)))
        assertNull(MessageSigners.pinOf(SignerNow(setOf(signal), "bad installer; rm -rf"))!!.installer)
        assertEquals("Cheogram", MessageSigners.label("com.cheogram.android"))
        assertEquals("Cheogram (Play Store build)", MessageSigners.label("com.cheogram.android.playstore"))
        assertEquals("Signal", MessageSigners.label("org.thoughtcrime.securesms"))
        assertEquals("Signal changed its signing key: turn it on again to trust it", MessageSigners.problem("org.thoughtcrime.securesms", PinVerdict.CHANGED))
        assertEquals("Cheogram (Play Store build) was installed after you turned it on: turn it on again to trust it",
            MessageSigners.problem("com.cheogram.android.playstore", PinVerdict.NOT_PINNED))
        assertNull(MessageSigners.problem("org.thoughtcrime.securesms", PinVerdict.TRUST))
        // No key in its words.
        assertFalse(AppPin(setOf(signal)).toString().contains(signal))
    }

    @Test fun turningAnAppOnPinsEachPackageApart() {
        val play = "com.cheogram.android.playstore"
        val now = mapOf("com.cheogram.android" to AppPin(setOf(fdroid), "org.fdroid.fdroid"), play to AppPin(setOf(other), "com.android.vending"),
            "org.thoughtcrime.securesms" to AppPin(setOf(signal)))
        val on = MessagesFile(MessageSettings(on = true)).withApp(MessageApp.CHEOGRAM, true, now)
        assertEquals(setOf(MessageApp.CHEOGRAM), on.settings.allowlist.apps)
        // Each build its own entry; nothing for an app not turned on.
        assertEquals(setOf("com.cheogram.android", play), on.pins.keys)
        assertEquals(AppPin(setOf(other), "com.android.vending"), on.pins[play])
        // Off: its pins go, so on again trusts what is installed then.
        val off = on.withApp(MessageApp.CHEOGRAM, false)
        assertTrue(off.pins.isEmpty())
        assertTrue(off.settings.allowlist.apps.isEmpty())
        val again = off.withApp(MessageApp.CHEOGRAM, true, mapOf("com.cheogram.android" to AppPin(setOf(signal))))
        assertEquals(mapOf("com.cheogram.android" to AppPin(setOf(signal))), again.pins)
        // The setup screen's Turn on.
        val setup = MessagesFile().turnedOn(setOf(MessageApp.SIGNAL), now)
        assertTrue(setup.settings.on)
        assertEquals(setOf(MessageApp.SIGNAL), setup.settings.allowlist.apps)
        assertEquals(setOf("org.thoughtcrime.securesms"), setup.pins.keys)
        // Round trip.
        assertEquals(on, MessagesFile.fromJson(on.toJson()))
        assertTrue(on.toJson().contains("\"v\":2"))
    }

    @Test fun anAppOnBeforeThePinsIsTrustedAtItsFirstNotification() {
        // A 0.7 test build's file: version 1, Cheogram and Signal on, Molly off.
        val old = MessagesFile.fromJson("""{"v":1,"on":true,"apps":["CHEOGRAM","SIGNAL"]}""")
        assertEquals(setOf("com.cheogram.android", "com.cheogram.android.playstore", "org.thoughtcrime.securesms"), old.firstUse)
        assertTrue(old.pins.isEmpty())
        // Only the builds installed when the listener connects may be pinned at first use.
        val pruned = old.firstUseInstalled { it != "com.cheogram.android.playstore" }
        assertEquals(setOf("com.cheogram.android", "org.thoughtcrime.securesms"), pruned.firstUse)
        val pinned = pruned.pinnedAtFirstUse("org.thoughtcrime.securesms", AppPin(setOf(signal)))
        assertEquals(setOf("com.cheogram.android"), pinned.firstUse)
        assertEquals(AppPin(setOf(signal)), pinned.pins["org.thoughtcrime.securesms"])
        // Written again: version 2 keeps what waits and what is pinned, and never trusts a new app on first use.
        val back = MessagesFile.fromJson(pinned.toJson())
        assertEquals(pinned, back)
        assertEquals(setOf("com.cheogram.android"), back.withApp(MessageApp.MOLLY, true).firstUse)
        // A version-2 file's app turned on with no pin isn't trusted on first use.
        assertTrue(MessagesFile.fromJson(MessagesFile(MessageSettings(on = true, allowlist = MessageAllowlist(setOf(MessageApp.SIGNAL)))).toJson()).firstUse.isEmpty())
        // A pin or a first use for a package that isn't a messaging app's is dropped.
        val odd = MessagesFile.fromJson("""{"v":2,"pins":{"com.evil":{"sha256":["$signal"]},"org.thoughtcrime.securesms":{"sha256":["xyz"]}},"firstUse":["com.evil"]}""")
        assertTrue(odd.pins.isEmpty())
        assertTrue(odd.firstUse.isEmpty())
    }

    /** Settings, People's switch and the bridge's "Always for …" writing at once: every change is in the file after. */
    @Test fun concurrentWritersLoseNothing() {
        val dir = Files.createTempDirectory("messages").toFile()
        val file = File(dir, MessagesFile.FILE)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val n = 40
        repeat(n) { i ->
            pool.execute {
                start.await()
                when (i % 4) {
                    0 -> MessagesFile.update(file) { it.copy(noCard = it.noCard + "p$i") }
                    1 -> MessagesFile.update(file) { it.copy(replyLang = it.replyLang + ("p$i" to Lang.FR)) }
                    2 -> MessagesFile.update(file) { it.withApp(MessageApp.entries[i % MessageApp.entries.size], true) }
                    else -> MessagesFile.update(file) { it.copy(settings = it.settings.copy(on = true)) }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        val f = MessagesFile.fromJson(file.readText())
        assertEquals((0 until n).filter { it % 4 == 0 }.map { "p$it" }.toSet(), f.noCard)
        assertEquals((0 until n).filter { it % 4 == 1 }.map { "p$it" }.toSet(), f.replyLang.keys)
        assertEquals((0 until n).filter { it % 4 == 2 }.map { MessageApp.entries[it % MessageApp.entries.size] }.toSet(), f.settings.allowlist.apps)
        assertTrue(f.settings.on)
        // Written whole: no temporary file left, and the lock beside it.
        assertEquals(setOf(MessagesFile.FILE, MessagesFile.LOCK), dir.list()!!.toSet())
        dir.deleteRecursively()
    }
}
