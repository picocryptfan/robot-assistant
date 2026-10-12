package app.heyrobot.core

/**
 * 1.0 audit (native-03): what one request may stream back from another process, in bytes: each piece at most [maxPiece]
 * and all of them together at most [maxTotal]. Past either, [take] says no from then on (the request ends there), so a
 * hostile model file or a compromised isolated :llama can't push tens of megabytes of "reply" into :brain and :voice.
 */
class StreamBudget(private val maxPiece: Int, private val maxTotal: Long) {
    private var total = 0L

    /** Spent: a piece was refused, and every later one is too. */
    @Volatile var over = false
        private set

    @Synchronized
    fun take(size: Int): Boolean {
        if (over) return false
        if (size < 0 || size > maxPiece || total + size > maxTotal) {
            over = true
            return false
        }
        total += size
        return true
    }
}
