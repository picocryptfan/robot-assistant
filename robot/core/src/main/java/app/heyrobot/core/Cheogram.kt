package app.heyrobot.core

// Pure. What Robot reads from Cheogram (docs/people.md, research in research-contacts.md): the person picked in its
// contact list. Cheogram is a fork of Conversations; its activities keep the eu.siacs.conversations package in both
// builds.

/** Who was picked in Cheogram's contact list: the name Cheogram shows, their bare XMPP address, and your account. */
data class CheogramPick(val name: String, val jid: String, val account: String?)

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
        val acct = account?.trim()?.takeIf { Addresses.isAddress(it) }
        val n = name?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: jid.substringBeforeLast('@')
        return CheogramPick(ToolCalls.safeTake(n, People.MAX_NAME), jid, acct)
    }
}
