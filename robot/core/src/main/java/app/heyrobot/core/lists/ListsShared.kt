// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

// Pure. 1.5, shared lists (lists-plan.md §9): what a list and an item keep about being shared, in the keys §9 reserved, so a
// file written here loses nothing on a Robot that doesn't know them. Words stay in the items; these are ids and counters.
//
//   list  "shared": ["<pairing>"]   who has this list now (one person in this release; a list, so more can come)
//         "origin": "<pairing>"     a list you joined: whose Robot it came from. Kept after you leave, so an offer of the
//                                   same list from the same Robot can bring it back and no other Robot can reach it.
//         "peerFull": true          a peer's changes didn't fit here: the list's page says so until you dismiss it
//   item  "peer": "<pairing>"       the Robot that last changed it
//         "req": "<32 hex>"         the request that carried its current counter (LinkOrder.wins needs it on a tie)
//         "sent": n                 the counter the other Robot has; "sent" != rev means this phone owes it the change
//         "notJustPeer": true       you touched it before a peer's change: outlasts the history, for ListShare's notice and Put back
//         "peerNew": true           a joined peer added it with its changes, not in an offer: it counts toward ListShare.PEER_ITEMS
object ListsShared {
    const val SHARED = "shared" // l10n: not text (a JSON key)
    const val ORIGIN = "origin" // l10n: not text (a JSON key)
    const val PEER = "peer" // l10n: not text (a JSON key)
    const val REQ = "req" // l10n: not text (a JSON key)
    const val SENT = "sent" // l10n: not text (a JSON key)
    const val WHO = "who" // l10n: not text (a JSON key)
    const val WAS = "was" // l10n: not text (a JSON key)
    const val NOT_JUST_PEER = "notJustPeer" // l10n: not text (a JSON key)
    const val PEER_NEW = "peerNew" // l10n: not text (a JSON key)
    const val PEER_FULL = "peerFull" // l10n: not text (a JSON key)

    /** The pairings this list is shared with now. */
    fun with(l: ListsList): List<String> = (l.extra[SHARED] as? List<*>).orEmpty().filterIsInstance<String>().filter { it.isNotEmpty() }

    /** The Robot a list you joined came from; null for a list of yours. */
    fun origin(l: ListsList): String? = l.extra[ORIGIN] as? String

    /** Words from another person's Robot may be in it, now or from before you left: read as peer text everywhere. */
    fun peerText(l: ListsList): Boolean = with(l).isNotEmpty() || l.from == ListFrom.PEER || l.items.any { it.by == ItemBy.PEER }

    /**
     * Whether add_to_list may go without its card. Never on a shared list: what lands there goes to another person, so the
     * model never sends anything to them without your tap, whoever's words it says they are.
     */
    fun modelNeedsNoCard(there: ListsList?, untrusted: Boolean, yourWords: Boolean): Boolean =
        !untrusted && there != null && yourWords && with(there).isEmpty() && there.from != ListFrom.PEER

    /** On the lock screen a list with another person's words isn't read or changed, whatever Lists' switch says (§4.4). */
    fun forLockScreen(file: ListsFile): ListsFile =
        if (file.lists.none(::peerText)) file else file.copy(lists = file.lists.filter { !peerText(it) })

    /** The Robots whose words [l] may hold, for the conversation's taint (core LinkTaint). */
    fun peers(l: ListsList): Set<String> = buildSet {
        addAll(with(l))
        origin(l)?.let(::add)
        for (i in l.items) {
            (i.extra[PEER] as? String)?.let(::add)
            for (c in i.hist) (c.extra[WHO] as? String)?.let(::add)
        }
    }
}
