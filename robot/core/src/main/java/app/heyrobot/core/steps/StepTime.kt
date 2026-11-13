// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.steps

import java.text.Normalizer
import java.util.Locale

// The first time a step names ("bake 25 minutes", « cuire 1 heure 30 »), in seconds, for the timer offer; a range
// ("20 to 25 minutes") offers its first number. Null when it names none, or more than a day.
object StepTime {
    private const val UNIT = """(hours?|hrs?|heures?|h|minutes?|mins?|min|seconds?|secs?|secondes?)""" // l10n: not text (words on a page)
    private val TIME = Regex("""(?<![\d.,])(\d{1,3})(?:\s*(?:-|–|to|a|or|ou)\s*\d{1,3})?\s*$UNIT\b(?:\s*(?:and\s+|et\s+)?(\d{1,2})\s*(minutes?|mins?|min)?\b)?""") // l10n: not text (words on a page)

    fun seconds(step: String): Long? {
        val t = Normalizer.normalize(step, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
        val m = TIME.find(t) ?: return null
        val n = m.groupValues[1].toLong()
        val unit = m.groupValues[2]
        val per = when {
            unit.startsWith("h") -> 3600L
            unit.startsWith("min") -> 60L
            else -> 1L
        }
        // "1 hour 30 minutes", « 1 heure 30 », "1 h 15": the minutes after an hour count too.
        val extra = if (per == 3600L && m.groupValues[3].isNotEmpty()) m.groupValues[3].toLong() * 60 else 0L
        val s = n * per + extra
        return s.takeIf { it in 1..86_400 }
    }
}
