package app.heyrobot.core

import java.security.MessageDigest

/**
 * 1.0 audit (ipc-1, ipc-2): which of a panel show's own arguments (the robot_* keys) count. Any app on top of the screen can
 * open the assistant with Activity.showAssist and a Bundle of its choosing, which Android hands to the session unchanged
 * with SHOW_SOURCE_APPLICATION; the panel can't tell it from Robot's own app by the keys. So:
 * - a show Robot's assistant service made itself ([From.ASSISTANT]: its in-process [ARG_VIS] token, and never
 *   SHOW_SOURCE_APPLICATION) keeps them all: the wake word, the lock screen, a headset, car mode, a routine's Run now or a
 *   Ready card (through the :voice trampoline), the camera's return;
 * - an app's show keeps at most "from Robot's app" and a conversation to continue, and only with Robot's own single-use
 *   nonce ([From.ROBOT_APP]);
 * - anything else ([From.OTHER]) opens the plain panel: no routine, no Ready card, no parked list, no headset or call
 *   session, no capture return, no spoken replies.
 */
object ShowTrust {
    const val PREFIX = "robot_"
    const val ARG_SOURCE = "robot_source"
    const val ARG_CONVERSATION = "robot_conversation"
    /** The assistant service's token (random, in the :voice process's memory only). */
    const val ARG_VIS = "robot_vis"
    /** Robot's app's single-use nonce ([NonceCheck]). */
    const val ARG_NONCE = "robot_nonce"
    const val SRC_APP = "app"

    enum class From { ASSISTANT, ROBOT_APP, OTHER }

    /**
     * Where a show came from. [fromApplication]: its flags carry SHOW_SOURCE_APPLICATION. [token]: the show's [ARG_VIS],
     * [expected] the service's own. [nonceOk] is asked only for an app's show that carries a nonce (it uses the nonce up).
     */
    fun from(fromApplication: Boolean, token: String?, expected: String, nonce: String?, nonceOk: (String) -> Boolean): From = when {
        !fromApplication && token != null && same(token, expected) -> From.ASSISTANT
        fromApplication && nonce != null && nonceOk(nonce) -> From.ROBOT_APP
        else -> From.OTHER
    }

    /** The keys of a show's [keys] the panel may read, for a show [from] there; never the token or the nonce. */
    fun keep(keys: Set<String>, from: From): Set<String> = when (from) {
        From.ASSISTANT -> keys.filter { it.startsWith(PREFIX) && it != ARG_VIS && it != ARG_NONCE }.toSet()
        From.ROBOT_APP -> keys.intersect(setOf(ARG_SOURCE, ARG_CONVERSATION))
        From.OTHER -> emptySet()
    }

    /** Robot's app's show is always "from the app" (whatever source it named). */
    fun source(from: From, given: String?): String? = when (from) {
        From.ASSISTANT -> given
        From.ROBOT_APP -> SRC_APP
        From.OTHER -> null
    }

    internal fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /**
     * Robot's app's nonce: minted by the main app just before its showAssist, kept in a private file, used up by the first
     * show that reads it, and good for [TTL_MS].
     */
    object NonceCheck {
        const val TTL_MS = 30_000L

        fun valid(stored: String?, storedAt: Long, given: String?, now: Long): Boolean =
            stored != null && given != null && stored.length >= 16 && now - storedAt in 0..TTL_MS && same(stored, given)

        fun encode(nonce: String, at: Long): String = "$nonce\n$at"

        /** The stored nonce and when it was minted, or null for anything else. */
        fun decode(text: String?): Pair<String, Long>? {
            val parts = text?.trim()?.split("\n") ?: return null
            if (parts.size != 2) return null
            val at = parts[1].toLongOrNull() ?: return null
            return parts[0].takeIf { it.isNotBlank() }?.let { it to at }
        }
    }
}
