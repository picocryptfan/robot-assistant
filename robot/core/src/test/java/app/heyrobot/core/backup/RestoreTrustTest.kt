// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.RobotSettings
import app.heyrobot.core.messages.AppPin
import app.heyrobot.core.messages.MessageAllowlist
import app.heyrobot.core.messages.MessageApp
import app.heyrobot.core.messages.MessageSettings
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.termux.TermuxSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design §6.3, §7.6: trust is confirmed again on the new phone, whatever the tick. Messages' signer pins and first-use
 * list, the trusted Termux, Cheogram's signer, the one-time answers before going online, the model terms and the
 * consent reminder, the voiceprint and the updater's token are never restored; most can't even be in a backup.
 */
class RestoreTrustTest {
    private val pinned = MessagesFile(
        settings = MessageSettings(on = true, allowlist = MessageAllowlist(setOf(MessageApp.CHEOGRAM, MessageApp.SIGNAL))),
        pins = mapOf("com.cheogram.android" to AppPin(setOf("AA".repeat(32)))),
        firstUse = setOf("org.thoughtcrime.securesms"),
    )

    @Test fun messagesAreConfirmedAgain() {
        for (mine in listOf(false, true)) {
            val m = RestoreSafety.messages(MessagesFile(), pinned, mine).value
            assertTrue(m.pins.isEmpty() && m.firstUse.isEmpty())
            // Off, with your apps still ticked: turning it on pins the apps installed now (MessagesFile.withApp).
            assertFalse(m.settings.on)
            assertEquals(setOf(MessageApp.CHEOGRAM, MessageApp.SIGNAL), m.settings.allowlist.apps)
            // As staged for the main app.
            val staged = RestoreMerge.settings(SettingsNow(), mapOf(MessagesFile.FILE to pinned.toJson()), mine, people = { it }).files[MessagesFile.FILE]!!
            assertFalse(staged.contains("AAAA"))
            assertTrue(MessagesFile.fromJson(staged).pins.isEmpty())
        }
    }

    @Test fun termuxIsTrustedAgain() {
        val trusted = TermuxSettings(enabled = true, trustedSigner = "BB".repeat(32), trustedVersionCode = 1022, trustedUpdatedAt = 5, trustedVersion = "0.119.0", testKeyRisk = true)
        for (mine in listOf(false, true)) {
            val t = RestoreSafety.termux(TermuxSettings(), trusted, mine).value
            assertFalse(t.enabled)
            assertNull(t.trustedSigner)
            assertEquals(0L, t.trustedVersionCode)
            assertNull(t.trusted())
            assertFalse(t.testKeyRisk)
            val staged = RestoreMerge.settings(SettingsNow(), mapOf("termux.json" to trusted.toJson().toString()), mine, people = { it }).files["termux.json"]!!
            assertFalse(JSONObject(staged).has("trustedSigner"))
        }
    }

    @Test fun thisPhonesOwnValuesStay() {
        val file = RobotSettings(eventCalendar = 42, meVoiceprint = true, convReminderAt = 99, safetyNotice = true, confirmedTerms = mapOf("gemma" to 1L))
        val phone = RobotSettings(eventCalendar = 7, confirmedTerms = mapOf("other" to 2L))
        for (mine in listOf(false, true)) {
            val s = RestoreSafety.settings(phone, file, mine).value
            assertEquals(7L, s.eventCalendar)
            assertFalse(s.meVoiceprint)
            assertEquals(0L, s.convReminderAt)
            assertFalse(s.safetyNotice)
            assertEquals(mapOf("other" to 2L), s.confirmedTerms)
        }
        // On a new phone: Robot picks the calendar, no voiceprint, no terms confirmed.
        val fresh = RestoreSafety.settings(RobotSettings(), file, true).value
        assertEquals(app.heyrobot.core.CalendarPick.AUTO, fresh.eventCalendar)
        assertTrue(fresh.confirmedTerms.isEmpty())
    }

    /** What isn't in a backup can't be named in one: no entry name reaches these files, in any category. */
    @Test fun trustFilesAreNeverEntries() {
        val never = listOf(
            "cheogram.signer", "people/cheogram.signer", "web_answer.json", "nearest_answer.json", "conv_consent.json", "github_token.bin", "github_token.json",
            "me.voiceprint", "claude_key.bin", "claude_key.json", "web/search_brave.bin", "helpers/claude/key.bin", "update_state.json", "setup_guide.json",
            "memory.enc", "index.enc", "voice_log.txt", "speaker_offer.json", "termux_status.json", "show_nonce", "park.json", "state.json", "ready.json",
        )
        // (A note or a conversation may have any id: "park.json" there is a note called "park", written to notes/.)
        val anyId = Regex("[A-Za-z0-9-]+\\.json")
        for (c in BackupCategory.entries) for (n in never) {
            if ((c == BackupCategory.NOTES || c == BackupCategory.HISTORY) && anyId.matches(n)) continue
            assertFalse("${c.id}/$n", c.validName(n))
        }
        // And the categories' rules list every trust rule's field as never restored.
        val trust = RestoreSafety.RULES.flatMap { (cls, r) -> r.filterValues { it == FieldRule.TRUST }.keys.map { cls.simpleName + "." + it } }.toSet()
        assertEquals(setOf("MessagesFile.pins", "MessagesFile.firstUse", "MessageSettings.on", "TermuxSettings.enabled", "TermuxSettings.trustedSigner",
            "TermuxSettings.trustedVersionCode", "TermuxSettings.trustedUpdatedAt", "TermuxSettings.trustedVersion", "TermuxSettings.testKeyRisk"), trust)
    }
}
