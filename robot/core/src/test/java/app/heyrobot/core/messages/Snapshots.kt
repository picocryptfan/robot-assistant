// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

/**
 * Notification snapshots in the shapes the apps post: what the main process's
 * listener will copy out of a StatusBarNotification. Names, addresses and words are made up.
 */
object Snapshots {
    const val T = 1_760_191_320_000L // 2025-10-11 14:02 UTC
    const val MIN = 60_000L
    const val STYLE = "android.app.Notification\$MessagingStyle"

    val ALL_APPS = MessageAllowlist(MessageApp.entries.toSet())

    val me = PersonSnapshot("Me", "sam@cheogram.com")
    val mom = PersonSnapshot("Mom Martin", "+15145550101@cheogram.com")
    val marc = PersonSnapshot("Marc", "marc@jabber.example")
    val julie = PersonSnapshot("Julie", "julie@cheogram.com")

    /** Cheogram's reply: SEMANTIC_ACTION_REPLY, no UI, RemoteInput "text_reply"; then mark-as-read; then snooze. */
    val cheogramActions = listOf(
        ActionSnapshot("Reply", SemanticAction.REPLY, listOf(RemoteInputSnapshot("text_reply", "Reply")), showsUserInterface = false),
        ActionSnapshot("Mark as read", SemanticAction.MARK_AS_READ, showsUserInterface = false),
        ActionSnapshot("Snooze", SemanticAction.NONE),
    )

    /** Its WearableExtender reply: dismisses and marks read locally, never Robot's. */
    val cheogramWear = listOf(ActionSnapshot("Reply", SemanticAction.REPLY, listOf(RemoteInputSnapshot("text_reply")), showsUserInterface = false))

    fun cheogram1to1(vararg texts: String, start: Long = T) = NotificationSnapshot(
        packageName = "com.cheogram.android", key = "0|com.cheogram.android|1001|null|10123", postTime = start + texts.size * MIN,
        category = "msg", template = STYLE, shortcutId = "sam@cheogram.com#+15145550101@cheogram.com", channelId = "messages",
        title = "Mom Martin", text = texts.lastOrNull(), isGroupConversation = false, user = me,
        messages = texts.mapIndexed { i, s -> MessageSnapshot(s, start + i * MIN, mom) },
        actions = cheogramActions, wearActions = cheogramWear,
    )

    val cheogramGroup = NotificationSnapshot(
        packageName = "com.cheogram.android", key = "0|com.cheogram.android|1002|null|10123", postTime = T + 3 * MIN,
        category = "msg", template = STYLE, shortcutId = "sam@cheogram.com#famille@muc.example", channelId = "messages",
        title = "Famille", conversationTitle = "Famille", isGroupConversation = true, user = me,
        messages = listOf(
            MessageSnapshot("Souper dimanche à 18 h chez maman", T, marc),
            MessageSnapshot("J'apporte le dessert", T + MIN, julie),
            MessageSnapshot("Parfait", T + 2 * MIN, null),
            MessageSnapshot(null, T + 3 * MIN, julie, "image/jpeg"),
        ),
        actions = cheogramActions,
    )

    /** Quiet hours: Cheogram's silent channel, no shortcut. */
    val cheogramQuiet = cheogram1to1("Tu dors?").copy(shortcutId = null, channelId = "silent_messages", key = "0|com.cheogram.android|1003|null|10123")

    /** Signal with its privacy on "Name and message": a reply under EXTRA_REMOTE_REPLY. */
    val signal = NotificationSnapshot(
        packageName = "org.thoughtcrime.securesms", key = "0|org.thoughtcrime.securesms|50001|null|10123", postTime = T + 5 * MIN,
        category = "msg", template = STYLE, shortcutId = "42", title = "Pierre", isGroupConversation = false,
        user = PersonSnapshot("You", "signal-self"),
        messages = listOf(MessageSnapshot("On se voit demain?", T + 5 * MIN, PersonSnapshot("Pierre", "signal-42"))),
        actions = listOf(
            ActionSnapshot("Mark read", SemanticAction.MARK_AS_READ, showsUserInterface = false),
            ActionSnapshot("Reply", SemanticAction.REPLY, listOf(RemoteInputSnapshot("extra_remote_reply", "Reply")), showsUserInterface = false),
        ),
    )

    /** Signal, privacy "Name only": "New message", and no reply action. */
    val signalNameOnly = signal.copy(
        key = "0|org.thoughtcrime.securesms|50002|null|10123", shortcutId = null,
        messages = listOf(MessageSnapshot("New message", T + 6 * MIN, PersonSnapshot("Pierre", null))),
        actions = listOf(ActionSnapshot("Mark read", SemanticAction.MARK_AS_READ, showsUserInterface = false)),
    )

    /** Signal, "No name or message": not even MessagingStyle. */
    val signalNothing = NotificationSnapshot(
        packageName = "org.thoughtcrime.securesms", key = "0|org.thoughtcrime.securesms|50003|null|10123", postTime = T + 7 * MIN,
        category = "msg", title = "Signal", text = "New message",
    )

    /** GrapheneOS Messaging: no semantic labels; the reply found by its RemoteInput (Intent.EXTRA_TEXT). */
    val messaging = NotificationSnapshot(
        packageName = "com.android.messaging", key = "0|com.android.messaging|7|null|10123", postTime = T + 8 * MIN,
        category = "msg", template = STYLE, shortcutId = "conv-17", title = "+1 514-555-0199", isGroupConversation = false,
        user = PersonSnapshot("Me", null),
        messages = listOf(MessageSnapshot("Your table for 4 is confirmed for 7 pm", T + 8 * MIN, PersonSnapshot("+1 514-555-0199", null))),
        actions = listOf(
            ActionSnapshot("Call", SemanticAction.NONE),
            ActionSnapshot("Reply", SemanticAction.NONE, listOf(RemoteInputSnapshot("android.intent.extra.TEXT", "Reply"))),
            ActionSnapshot("Mark as read", SemanticAction.NONE),
        ),
    )

    fun thread(n: NotificationSnapshot, allow: MessageAllowlist = ALL_APPS): MessageThread =
        (NotificationParsing.parse(n, allow) as? Parsed.Thread)?.thread ?: throw AssertionError("not a thread: ${NotificationParsing.parse(n, allow)}")
}
