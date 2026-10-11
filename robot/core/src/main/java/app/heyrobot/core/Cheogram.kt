package app.heyrobot.core

import java.util.Locale

// Pure. What Robot reads from Cheogram (docs/people.md, research in research-contacts.md): the person picked in its
// contact list, the "<account>#<jid>" id of a chat's shortcut, and the sender of a 1:1 chat's notification. Cheogram is
// a fork of Conversations; its activities keep the eu.siacs.conversations package in both builds.

/** Who was picked in Cheogram's contact list: the name Cheogram shows, their bare XMPP address, and your account. */
data class CheogramPick(val name: String, val jid: String, val account: String?)

/** Someone a Cheogram notification shows, to learn: the name Cheogram shows, their bare address, your account. */
data class CheogramSender(val name: String, val jid: String, val account: String)

object CheogramLinks {
    /** Exported for ACTION_CREATE_SHORTCUT: Cheogram's searchable contact list, started for a result. */
    const val SHORTCUT_ACTIVITY = "eu.siacs.conversations.ui.ShortcutActivity"
    /** Exported for VIEW: an xmpp: link, with the "account" extra naming the account the chat is in. */
    const val START_CONVERSATION = "eu.siacs.conversations.ui.StartConversationActivity"
    /** StartConversationActivity's extra (XmppActivity.EXTRA_ACCOUNT): the account's bare address. */
    const val EXTRA_ACCOUNT = "account"

    /**
     * Cheogram's answer to the pick (EXTRA_SHORTCUT_NAME, and EXTRA_SHORTCUT_INTENT's data and "account" extra), or null
     * when it holds no usable address. No name: the address's local part.
     */
    fun pick(name: String?, uri: String?, account: String?): CheogramPick? {
        val jid = XmppLink.jid(uri) ?: return null
        val acct = account?.trim()?.lowercase(Locale.ROOT)?.takeIf { Addresses.isAddress(it) }
        val n = name?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: jid.substringBeforeLast('@')
        return CheogramPick(ToolCalls.safeTake(n, People.MAX_NAME), jid, acct)
    }

    /** A chat shortcut's id, "<account>#<contact>" (ShortcutService.getShortcutId), as (account, contact); null otherwise. */
    fun shortcut(id: String?): Pair<String, String>? {
        val parts = id?.split('#', limit = 2) ?: return null
        if (parts.size != 2) return null
        val (account, contact) = parts.map { it.trim() }
        return if (Addresses.isAddress(account) && Addresses.isAddress(contact)) account to contact else null
    }
}

object CheogramNotices {
    /**
     * The person a Cheogram message notification shows, or null. Only a 1:1 chat counts: not a group conversation, with
     * the chat's shortcut id ("<account>#<contact>", set only for 1:1 chats on Cheogram's messages channel), and a message
     * whose sender's Person key is that contact's address. [senders]: each message's sender (Person name, key), oldest
     * first: the newest name wins. A name that is only the address or a number teaches nothing. Nothing else of the
     * notification is ever read: no message text.
     */
    fun sender(shortcutId: String?, group: Boolean, senders: List<Pair<String?, String?>>): CheogramSender? {
        if (group) return null
        val (account, contact) = CheogramLinks.shortcut(shortcutId) ?: return null
        if (account.equals(contact, ignoreCase = true)) return null
        val name = senders.lastOrNull { (_, key) -> key?.trim()?.equals(contact, ignoreCase = true) == true }?.first
            ?.replace(Regex("\\s+"), " ")?.trim() ?: return null
        if (name.isEmpty() || name.equals(contact, ignoreCase = true) || name.equals(contact.substringBeforeLast('@'), ignoreCase = true)) return null
        if (Addresses.isPhone(name) || Addresses.isAddress(name)) return null
        if (!Addresses.isJid(contact)) return null
        return CheogramSender(ToolCalls.safeTake(name, People.MAX_NAME), contact, account.lowercase(Locale.ROOT))
    }
}
