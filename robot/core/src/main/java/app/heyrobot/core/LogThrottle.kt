package app.heyrobot.core

/**
 * 1.0 audit (ipc-4): at most one voice-log event per [windowMs] for something any app can make happen (Clock's ALARM_ALERT
 * and ALARM_DONE reach an exported receiver), so a flood of fake broadcasts can't push the real events out of the log's
 * 300. [admit] says how many happened since the last one logged (this one included), or null to log nothing now.
 */
class LogThrottle(private val windowMs: Long = 60_000L) {
    private var lastAt: Long? = null
    private var held = 0

    @Synchronized
    fun admit(now: Long): Int? {
        val last = lastAt
        if (last != null && now - last in 0 until windowMs) {
            held++
            return null
        }
        lastAt = now
        return (held + 1).also { held = 0 }
    }
}
