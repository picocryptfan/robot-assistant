// SPDX-License-Identifier: GPL-3.0-only
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
     * Exported for SEND text/plain: Cheogram's share screen ("Choose in Cheogram"), launchMode singleTop in Cheogram's own
     * task. Started by name, in a task of its own (CheogramIntents.FRESH): sent as a plain SEND to the package, Android
     * found Cheogram's task rooted at an earlier share (the same intent but for its text) and only brought it to the
     * front, on whatever chat that share had opened.
     */
    const val SHARE_ACTIVITY = "eu.siacs.conversations.ui.ShareWithActivity"

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

    /**
     * What came of the pick, for the voice log and what Robot says: [resultOk] (RESULT_OK), whether Cheogram's answer
     * could be read at all ([readable]), and the [pick] in it. Backing out of the list can't be told from an answer lost
     * on the way (too large to deliver): both arrive as a cancel with nothing, so [PickOutcome.CANCELLED] says either.
     */
    fun outcome(resultOk: Boolean, readable: Boolean, pick: CheogramPick?): PickOutcome = when {
        !resultOk -> PickOutcome.CANCELLED
        !readable -> PickOutcome.UNREADABLE
        pick == null || !Addresses.isJid(pick.jid) -> PickOutcome.NO_ADDRESS
        else -> PickOutcome.PICKED
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

/**
 * Whose notifications may teach People: only the Cheogram build Robot hands texts to, posting for itself, signed as it
 * was the first time Robot saw it after you turned learning on (its signing certificate's SHA-256 is kept in
 * files/people/cheogram.signer, one "<package> <hex>" a line). Another app that takes the package name, signed by
 * someone else, teaches nothing until you turn learning off and on again.
 */
object CheogramSigners {
    const val FILE = "cheogram.signer"

    enum class Verdict { TRUST, PIN, REFUSE }

    /** The notification's [pkg] is the Cheogram build [installed] here, and posted it for itself ([opPkg], when known). */
    fun fromCheogram(pkg: String?, opPkg: String?, installed: String?): Boolean =
        !pkg.isNullOrEmpty() && pkg == installed && (opPkg.isNullOrEmpty() || opPkg == pkg)

    /**
     * [pinned]: the digest kept for the package, if any; [current]: its signer's digest now (null: unknown, or several
     * signers); [pinnedStillSigns]: the system says [pinned] is in the package's signing lineage.
     */
    fun check(pinned: String?, current: String?, pinnedStillSigns: Boolean): Verdict = when {
        pinned == null -> if (current != null) Verdict.PIN else Verdict.REFUSE
        pinned == current || pinnedStillSigns -> Verdict.TRUST
        else -> Verdict.REFUSE
    }

    fun parse(text: String?): Map<String, String> = text.orEmpty().lines().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size == 2 && parts[0].isNotEmpty() && unhex(parts[1])?.size == 32) parts[0] to parts[1].lowercase(Locale.ROOT) else null
    }.toMap()

    fun write(pins: Map<String, String>): String = pins.entries.sortedBy { it.key }.joinToString("") { "${it.key} ${it.value}\n" }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun unhex(s: String): ByteArray? {
        if (s.length % 2 != 0 || !s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
