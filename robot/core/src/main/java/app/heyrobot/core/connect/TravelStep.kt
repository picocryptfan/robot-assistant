// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.Choice
import app.heyrobot.core.ConnStepAction
import app.heyrobot.core.Executed
import app.heyrobot.core.LocationCode
import app.heyrobot.core.Prepared
import app.heyrobot.core.StepArg
import app.heyrobot.core.car.SavedPlace
import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.core.i18n.InfoStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.TurnStrings
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.SkillNumbers

// Pure. A skill's `travel_time` step (docs/plans/skills-connections-plan.md §3.4; C6): the travel time from where the phone
// is to Home or Work (Settings › Car mode's saved places), asked of the routing server you run. Decided here so the panel
// (ConnRunner, in :voice) and the tests ask the same rules.
//
// Before the card ([prepare]), as any connection step (ConnStep): the routing connection exists and Test passed; D1, D2
// and C6's rule (where you are never goes over plain http, except to loopback or Tailscale: ConnRules.locationOverHttp);
// the Network toggle; off your network or offline; its key; its limits. Then what is its own: the place is saved, and
// Robot may see where the phone is (the permission, checked without reading anything). Where the phone is, is read only
// after the card, just before the call (ConnRunner, the way the weather reads it), so a cancelled card reads nothing. The
// card says where it goes, the place, and that where you are goes there rounded to about 1 km; never coordinates, which
// History would otherwise keep. It reads and is yours (a fixed place), so a spoken yes may answer it (D14); the first
// use of the server's host is D13's tap-only card, which says what leaves the phone.
//
// After the call ([result]): the minutes and kilometres, typed, from outside the phone (outputs `minutes` and `km`), and
// the conversation holds where you are for helpers (Personal.LOCATION, ConnRan.located; only when a travel time came
// back into it). A reply without a route is
// "unreadable": Robot says nothing it didn't get.

object TravelStep {
    /** Where a step's `to` points ("home" or "work"), or null when it isn't one of them. */
    fun to(a: ConnStepAction): String? = ((a.call.args["to"] as? StepArg.Fixed)?.value as? String)?.takeIf { it == HOME || it == WORK }

    /** The saved place [to] names in [x]'s places, or why the step can't go there. */
    fun place(to: String, x: StepContext): Pair<SavedPlace?, ConnFailure?> = RoutingPreset.place(to, x.places.home, x.places.work)

    /** The place's word on the card ("home", "work"). */
    fun placeWord(to: String): Phrase = if (to == WORK) ConnStrings.travelWork() else ConnStrings.travelHome()

    /** [a] prepared with what [x] knows: ready for the gate with its reasons and card, or refused in Robot's words. */
    fun prepare(a: ConnStepAction, x: StepContext): Prepared {
        val c = x.connection?.takeIf { it.kind == ConnKind.ROUTING && (a.call.conn == null || it.id == a.call.conn) }
            ?: return ConnStep.refused(ConnFailure.GONE, "")
        val to = to(a) ?: return ConnStep.refused(ConnFailure.BAD_VALUE, c.name)
        if (c.tested == null) return ConnStep.refused(ConnFailure.NOT_TESTED, c.name)
        ConnRules.refusal(c)?.let { return ConnStep.refused(it, c.name) }
        if (!x.networkOn) return ConnStep.refused(ConnFailure.NETWORK_OFF, c.name)
        ConnAway.before(c, x.net)?.let { return ConnStep.refused(it, c.name) }
        if (c.secrets.isNotEmpty() && !x.keySaved) return ConnStep.refused(ConnFailure.NO_KEY, c.name)
        ConnLimits.connection(x.limits, c, x.now)?.let { return ConnStep.refused(it, c.name, c) }
        val (place, missing) = place(to, x)
        if (place == null) return ConnStep.refused(missing ?: ConnFailure.BAD_VALUE, c.name)
        if (!x.locationAllowed) return ConnStep.refused(ConnFailure.LOCATION_NOT_ALLOWED, c.name)
        // The request as it will go, but from the place to itself: only to check it builds and to show where the key goes.
        val shape = when (val b = RoutingPreset.request(c, place.lat, place.lon, place)) {
            is Built.Ok -> b.request
            is Built.Refused -> return ConnStep.refused(b.why, c.name)
        }
        val origin = ConnConsent.origin(c) ?: return ConnStep.refused(ConnFailure.NOWHERE, c.name)
        val effects = EFFECTS
        val why = linkedSetOf<AskWhy>()
        if (ConnConsent.asks(c)) why += AskWhy.CONNECTION_FIRST
        val word = placeWord(to)
        val host = c.address?.host.orEmpty()
        val lines = mutableListOf(ConnStrings.hostLine("host" to host, "name" to c.name), ConnStrings.travelSends("place" to word),
            ConnStrings.effectsLine("list" to Phrase.ListOf(listOf(ConnStrings.effectLocation()) + ConnStep.chips(effects, host))))
        ConnPreview.of(shape, c, x.masks).auth?.let { lines += it }
        val prepared = ConnPrepared(ConnStrings.travelCard("place" to word, "name" to c.name), Phrase.Join(lines, "\n"), effects, origin, c.name,
            read = true, spokenYes = c.spokenYes)
        val ready = a.copy(prepared = prepared, consent = null)
        val choices = if (AskWhy.CONNECTION_FIRST in why) listOf(Choice(ConnStrings.allow(), ready.copy(consent = ConsentAnswer.ALLOW)),
            Choice(InfoStrings.justThisTime(), ready.copy(consent = ConsentAnswer.ONCE))) else emptyList()
        val note: Phrase? = if (AskWhy.CONNECTION_FIRST in why) Phrase.Join(listOf(ConnStrings.firstUse("skill" to a.call.skill, "host" to host, "name" to c.name,
            "what" to ConnStrings.travelCard("place" to word, "name" to c.name)), ConnStrings.firstUseLocation())) else null
        return Prepared.Ready(ready, why = why, choices = choices, note = note?.en(), noteText = note)
    }

    /** Why a position couldn't be had, from how the phone looked for one (the weather's codes); null: a position came. */
    fun noFix(code: LocationCode): ConnFailure = when (code) {
        LocationCode.NOT_ALLOWED -> ConnFailure.LOCATION_NOT_ALLOWED
        LocationCode.OFF -> ConnFailure.LOCATION_OFF
        LocationCode.BACKGROUND -> ConnFailure.LOCATION_BACKGROUND
        else -> ConnFailure.NO_LOCATION
    }

    /**
     * What [a] (prepared) asked of routing connection [c] did, from the call's [end]: the minutes and kilometres for the
     * rest of the skill, or Robot's words for why not. [net]: CN4's network, read again at the call.
     */
    fun result(a: ConnStepAction, c: Connection, end: CallEnd, net: NetNow?): Executed {
        val n = "name" to c.name
        val kind = c.routing ?: return Executed(ActionStatus.FAILED, ConnFailure.GONE.text(c.name))
        return when (end) {
            is CallEnd.Stopped -> Executed(ActionStatus.DECLINED, a.stoppedText ?: TurnStrings.stopped()).copy(conn = ConnRan(sent = end.sent))
            is CallEnd.Failed -> Executed(ActionStatus.FAILED, ConnAway.after(c, net, end.why).text(c.name)).copy(conn = ConnRan(sent = end.sent))
            is CallEnd.Answered -> when (val read = end.read) {
                is ReplyRead.Failed -> Executed(ActionStatus.FAILED, read.why.text(c.name, read.status)).copy(conn = ConnRan(sent = true))
                is ReplyRead.Ok -> {
                    val t = RoutingPreset.read(kind, read.json)
                        ?: return Executed(ActionStatus.FAILED, ConnFailure.UNREADABLE.text(c.name)).copy(conn = ConnRan(sent = true))
                    val said = ConnStrings.travelDone(n, "minutes" to t.minutes.toString(), "km" to SkillNumbers.text(t.km))
                    Executed(ActionStatus.DONE, said).copy(conn = ConnRan(t.outputs(), content = true, sent = true, located = true))
                }
            }
        }
    }

    /** Travel time only reads (SkillEffects' TRAVEL_TIME); what makes it personal is Personal.LOCATION, not an effect. */
    val EFFECTS: Set<SkillEffect> = setOf(SkillEffect.READS_ONLINE)

    /** The longest Robot waits for where the phone is, within the step's 18 s (EST): the weather's wait with a Home to fall back on is 3 s. */
    const val FIX_MS = 5_000L

    private const val HOME = "home" // l10n: not text (travel_time's place)
    private const val WORK = "work" // l10n: not text (travel_time's place)
}
