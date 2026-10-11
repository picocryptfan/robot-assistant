package app.heyrobot.core.messages

import app.heyrobot.core.messages.Snapshots.ALL_APPS
import app.heyrobot.core.messages.Snapshots.MIN
import app.heyrobot.core.messages.Snapshots.T
import app.heyrobot.core.messages.Snapshots.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationParsingTest {
    @Test fun cheogramOneToOne() {
        val t = thread(Snapshots.cheogram1to1("Are you coming?", "Call me back"))
        assertEquals(MessageApp.CHEOGRAM, t.app)
        assertEquals("sam@cheogram.com#+15145550101@cheogram.com", t.conversationId)
        assertEquals("0|com.cheogram.android|1001|null|10123", t.notificationKey)
        assertEquals("Mom Martin", t.title)
        assertFalse(t.isGroup)
        assertEquals(listOf("Are you coming?", "Call me back"), t.messages.map { it.text })
        assertTrue(t.messages.all { it.senderKey == "+15145550101@cheogram.com" && it.senderName == "Mom Martin" && it.replyable && !it.fromMe })
        assertEquals(ActionRef(0, "text_reply", semantic = true, authRequired = false, showsUserInterface = false), t.reply)
        assertEquals(ReplyKind.SEMANTIC, t.replyKind)
        assertEquals(1, t.markRead!!.index)
        assertTrue(t.markRead!!.semantic)
        assertNull(t.hidden)
        assertEquals(2, t.unread.size)
        assertEquals("CHEOGRAM:sam@cheogram.com#+15145550101@cheogram.com", t.key)
    }

    @Test fun cheogramGroupWithYouAndAPhoto() {
        val t = thread(Snapshots.cheogramGroup)
        assertTrue(t.isGroup)
        assertEquals("Famille", t.title)
        assertEquals(4, t.messages.size)
        val mine = t.messages[2]
        assertTrue(mine.fromMe)
        assertNull(mine.senderName)
        assertEquals(AttachmentKind.PHOTO, t.messages[3].attachment)
        assertEquals("", t.messages[3].text)
        assertEquals(listOf("Marc", "Julie", "Julie"), t.incoming.map { it.senderName })
        assertEquals(3, t.unread.size)
        // "Include group chats" off: left alone.
        assertEquals(Parsed.Ignored(IgnoreWhy.GROUP_OFF), NotificationParsing.parse(Snapshots.cheogramGroup, ALL_APPS.copy(groups = false)))
    }

    @Test fun cheogramQuietHoursHasNoShortcut() {
        val t = thread(Snapshots.cheogramQuiet)
        assertEquals("0|com.cheogram.android|1003|null|10123", t.conversationId)
        assertEquals(listOf("Tu dors?"), t.messages.map { it.text })
    }

    @Test fun signalWithAndWithoutItsPrivacySetting() {
        val open = thread(Snapshots.signal)
        assertEquals(ActionRef(1, "extra_remote_reply", true, false, false), open.reply)
        assertEquals(0, open.markRead!!.index)
        assertEquals("Pierre", open.name)
        val nameOnly = thread(Snapshots.signalNameOnly)
        assertEquals(HiddenWhy.APP_PRIVACY, nameOnly.hidden)
        assertNull(nameOnly.reply)
        assertEquals(ReplyKind.NONE, nameOnly.replyKind)
        assertEquals("", nameOnly.messages.single().text)
        assertEquals("Pierre", nameOnly.name)
        val nothing = thread(Snapshots.signalNothing)
        assertEquals(HiddenWhy.APP_PRIVACY, nothing.hidden)
        assertEquals("Signal", nothing.name)
    }

    @Test fun grapheneOsMessagingIsFoundByItsRemoteInput() {
        val t = thread(Snapshots.messaging)
        assertEquals(MessageApp.MESSAGING, t.app)
        assertEquals(ActionRef(1, "android.intent.extra.TEXT", semantic = false, authRequired = false, showsUserInterface = true), t.reply)
        assertEquals(ReplyKind.PLAIN, t.replyKind)
        assertEquals(ActionRef(2, null, semantic = false, authRequired = false, showsUserInterface = true), t.markRead)
        assertEquals("+1 514-555-0199", t.name)
    }

    @Test fun theBestReplyActionWinsAndOthersNever() {
        val ri = listOf(RemoteInputSnapshot("k"))
        // Semantic without a screen beats semantic with one beats unlabelled.
        val actions = listOf(
            ActionSnapshot("Plain", SemanticAction.NONE, ri, showsUserInterface = false),
            ActionSnapshot("Opens", SemanticAction.REPLY, ri, showsUserInterface = true),
            ActionSnapshot("Best", SemanticAction.REPLY, ri, showsUserInterface = false),
        )
        assertEquals(2, NotificationParsing.replyAction(actions)!!.index)
        assertEquals(1, NotificationParsing.replyAction(actions.take(2))!!.index)
        // Never a system smart reply, a canned-choices input, a blank key, or an action labelled for something else.
        assertNull(NotificationParsing.replyAction(listOf(
            ActionSnapshot("Smart", SemanticAction.REPLY, ri, showsUserInterface = false, contextual = true),
            ActionSnapshot("Choices", SemanticAction.REPLY, listOf(RemoteInputSnapshot("k", allowFreeFormInput = false, choices = listOf("Yes", "No")))),
            ActionSnapshot("Blank", SemanticAction.REPLY, listOf(RemoteInputSnapshot(" "))),
            ActionSnapshot("Archive", SemanticAction.ARCHIVE, ri),
            null,
        )))
        // A WearableExtender action is never looked at.
        val wearOnly = Snapshots.cheogram1to1("hi").copy(actions = emptyList())
        assertNull(thread(wearOnly).reply)
        assertTrue(thread(Snapshots.cheogram1to1("x").copy(actions = listOf(Snapshots.cheogramActions[0].copy(authenticationRequired = true)))).reply!!.authRequired)
        // Mark as read in French; not an action with a reply field.
        assertEquals(0, NotificationParsing.markReadAction(listOf(ActionSnapshot("Marquer comme lu")))!!.index)
        assertNull(NotificationParsing.markReadAction(listOf(ActionSnapshot("Mark as read", remoteInputs = ri))))
    }

    @Test fun youAreTheUserOrNoSender() {
        assertTrue(NotificationParsing.isMe(null, Snapshots.me))
        assertTrue(NotificationParsing.isMe(PersonSnapshot("Sam", "sam@cheogram.com"), Snapshots.me))
        assertTrue(NotificationParsing.isMe(PersonSnapshot("Me", null), PersonSnapshot("Me", null)))
        assertFalse(NotificationParsing.isMe(Snapshots.mom, Snapshots.me))
        // Someone who calls themselves "Me" is not you when the keys say otherwise.
        assertFalse(NotificationParsing.isMe(PersonSnapshot("Me", "evil@example.com"), Snapshots.me))
        assertFalse(NotificationParsing.isMe(PersonSnapshot("Me", null), null))
    }

    @Test fun attachmentsInWords() {
        assertEquals(AttachmentKind.PHOTO, NotificationParsing.attachment("image/gif"))
        assertEquals(AttachmentKind.VIDEO, NotificationParsing.attachment("video/mp4"))
        assertEquals(AttachmentKind.VOICE, NotificationParsing.attachment("audio/ogg"))
        assertEquals(AttachmentKind.FILE, NotificationParsing.attachment("application/pdf"))
        assertNull(NotificationParsing.attachment(" "))
        assertNull(NotificationParsing.attachment(null))
    }

    @Test fun aRedactedCodeIsSaidToBeHidden() {
        val n = Snapshots.cheogram1to1("Sensitive notification content hidden")
        val t = thread(n)
        assertEquals(HiddenWhy.OTP_REDACTED, t.hidden)
        assertEquals("", t.messages.single().text)
    }

    @Test fun otherAppsAndOtherNotificationsAreDroppedFirst() {
        val off = MessageAllowlist(setOf(MessageApp.SIGNAL))
        assertEquals(Parsed.Ignored(IgnoreWhy.NOT_ALLOWED), NotificationParsing.parse(Snapshots.cheogram1to1("hi"), off))
        assertEquals(Parsed.Ignored(IgnoreWhy.NOT_ALLOWED), NotificationParsing.parse(Snapshots.cheogram1to1("hi"), MessageAllowlist()))
        assertEquals(Parsed.Ignored(IgnoreWhy.NOT_ALLOWED), NotificationParsing.parse(Snapshots.signal.copy(packageName = "com.evil.signal"), ALL_APPS))
        assertEquals(Parsed.Ignored(IgnoreWhy.NOT_ALLOWED), NotificationParsing.parse(Snapshots.signal.copy(packageName = null), ALL_APPS))
        assertEquals(Parsed.Ignored(IgnoreWhy.NO_KEY), NotificationParsing.parse(Snapshots.signal.copy(key = " "), ALL_APPS))
        assertEquals(Parsed.Ignored(IgnoreWhy.SUMMARY), NotificationParsing.parse(Snapshots.signal.copy(groupSummary = true), ALL_APPS))
        assertEquals(Parsed.Ignored(IgnoreWhy.ONGOING), NotificationParsing.parse(Snapshots.signal.copy(ongoing = true), ALL_APPS))
        val download = NotificationSnapshot("org.thoughtcrime.securesms", "k", T, category = "progress", title = "Backup", text = "50%")
        assertEquals(Parsed.Ignored(IgnoreWhy.NOT_A_CONVERSATION), NotificationParsing.parse(download, ALL_APPS))
        val empty = Snapshots.signal.copy(messages = listOf(MessageSnapshot(null, T, Snapshots.mom), MessageSnapshot("   ", T, Snapshots.mom)), text = null)
        assertEquals(Parsed.Ignored(IgnoreWhy.NOTHING_TO_READ), NotificationParsing.parse(empty, ALL_APPS))
    }

    @Test fun malformedDataIsTakenAsFarAsItGoes() {
        val n = Snapshots.cheogram1to1().copy(
            messages = listOf(null, MessageSnapshot("ok", -5, Snapshots.mom), null, MessageSnapshot("no time", 0, Snapshots.mom)),
            actions = listOf(null, ActionSnapshot(null, 99, listOf(RemoteInputSnapshot(null))), Snapshots.cheogramActions[0]),
            user = null,
        )
        val t = thread(n)
        assertEquals(listOf("ok", "no time"), t.messages.map { it.text })
        // A bad timestamp takes the notification's.
        assertTrue(t.messages.all { it.time == n.postTime })
        assertEquals(2, t.reply!!.index)
    }

    @Test fun onlyTheNewestTwentyFiveAndNoRepeats() {
        val texts = (1..40).map { "message $it" }.toTypedArray()
        val t = thread(Snapshots.cheogram1to1(*texts))
        assertEquals(NotificationParsing.MAX_MESSAGES, t.messages.size)
        assertEquals("message 16", t.messages.first().text)
        assertEquals("message 40", t.messages.last().text)
        // The same message as current and as earlier context counts once, as current.
        val m = MessageSnapshot("same", T, Snapshots.mom)
        val twice = thread(Snapshots.cheogram1to1().copy(messages = listOf(m), historicMessages = listOf(m, MessageSnapshot("before", T - MIN, Snapshots.mom))))
        assertEquals(listOf("before", "same"), twice.messages.map { it.text })
        assertEquals(listOf(true, false), twice.messages.map { it.historic })
        assertEquals(1, twice.unread.size)
    }

    @Test fun textIsCleanedAndCapped() {
        assertEquals("abc", NotificationParsing.cleanText("a​b‮c\u0007"))
        assertEquals("line one\n\nline two", NotificationParsing.cleanText("line one\r\n\r\n\r\n\r\nline two  "))
        assertEquals(NotificationParsing.MAX_TEXT, NotificationParsing.cleanText("y".repeat(10_000)).length)
        assertEquals("Mom Martin", NotificationParsing.cleanName("Mom\nMartin"))
        assertNull(NotificationParsing.cleanName("​ "))
        assertEquals(NotificationParsing.MAX_NAME, NotificationParsing.cleanName("n".repeat(500))!!.length)
        // An emoji at the cut is never split in half.
        val cut = NotificationParsing.cleanText("a".repeat(NotificationParsing.MAX_TEXT - 1) + "😀")
        assertFalse(Character.isHighSurrogate(cut.last()))
    }

    @Test fun idsAreStableAndHoldNoText() {
        val a = NotificationParsing.messageId(MessageApp.SIGNAL, "42", T, "signal-42", "secret words", null)
        assertEquals(a, NotificationParsing.messageId(MessageApp.SIGNAL, "42", T, "signal-42", "secret words", null))
        assertNotEquals(a, NotificationParsing.messageId(MessageApp.SIGNAL, "42", T, "signal-42", "other words", null))
        assertNotEquals(a, NotificationParsing.messageId(MessageApp.CHEOGRAM, "42", T, "signal-42", "secret words", null))
        assertEquals(16, a.length)
        assertFalse(a.contains("secret"))
        val t = thread(Snapshots.cheogram1to1("I'm at the hospital"))
        assertFalse(t.toString().contains("hospital") || t.toString().contains("Mom"))
        assertFalse(t.messages[0].toString().contains("hospital") || t.messages[0].toString().contains("Mom"))
        assertFalse(Snapshots.cheogram1to1("I'm at the hospital").toString().contains("hospital"))
    }

    @Test fun theAllowlistStartsEmpty() {
        assertTrue(MessageAllowlist().apps.isEmpty())
        assertEquals(setOf(MessageApp.CHEOGRAM), MessageAllowlist.atSetup(MessageApp.CHEOGRAM).apps)
        assertEquals(MessageApp.CHEOGRAM, MessageAllowlist.atSetup(MessageApp.CHEOGRAM).allows("com.cheogram.android.playstore"))
        assertNull(MessageAllowlist.atSetup(MessageApp.CHEOGRAM).allows("org.thoughtcrime.securesms"))
        assertEquals(setOf(MessageApp.CHEOGRAM, MessageApp.MOLLY), MessageAllowlist.atSetup(MessageApp.CHEOGRAM).with(MessageApp.MOLLY, true).apps)
        assertEquals(MessageApp.GOOGLE_MESSAGES, MessageApp.of("com.google.android.apps.messaging"))
        assertEquals(MessageApp.MESSAGING, MessageApp.of("com.android.messaging"))
        assertNull(MessageApp.of("com.whatsapp"))
        assertFalse(MessageApp.MOLLY.verified || MessageApp.GOOGLE_MESSAGES.verified)
    }
}
