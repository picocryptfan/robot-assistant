// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import app.heyrobot.core.feedback.ReportCard
import app.heyrobot.core.feedback.ReportTurn

/**
 * fix-bugcard-drop (Pixel 8 bc7): the turn the open bug card shows. The page keeps it in memory after a send, but never past
 * its 10 minutes: once stale, the card forgets it, with the screenshot, whether or not a report went. The card checks with
 * [stale] when its timer ends and again each time it starts or resumes, since the timer's clock stops while the phone sleeps.
 */
class CardTurnHeld {
    private var held: ReportTurn? = null

    /** The turn shown for [json] (null after a send) at [now]: its own while fresh, else the one held, only while fresh too. */
    fun show(json: String?, now: Long): ReportTurn? =
        (ReportTurn.fromJson(ReportCard.saved(json, now)) ?: held?.takeIf { it.fresh(now) }).also { held = it }

    /** Whether [json] or the turn held has gone stale at [now]: the card then forgets both ([forget]) and the screenshot. */
    fun stale(json: String?, now: Long): Boolean =
        (json != null && ReportTurn.fromJson(json)?.fresh(now) != true) || held?.fresh(now) == false

    fun forget() { held = null }
}
