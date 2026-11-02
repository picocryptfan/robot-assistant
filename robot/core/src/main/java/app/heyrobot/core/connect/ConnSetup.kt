// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.RoutineStrings
import app.heyrobot.core.skills.AttentionWhy
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.SkillCheck
import app.heyrobot.core.skills.SkillLinks
import app.heyrobot.core.skills.StepKind
import java.time.Instant
import java.time.ZonedDateTime

// Pure. What Settings › Skills › Connections needs beyond the file (docs/plans/skills-connections-plan.md §6.1 to §6.3;
// C9): a new connection of each kind from what you typed (never a key: keys are pasted in the connection window, in
// :voice), each row's state ("Tested today", "Needs its key", "Signal was replaced"), the skills that use one, how many
// would need attention after Remove, Settings › Skills' summary line, and why a step needs attention in words. Every
// save still goes through ConnCheck (ConnStore.save); nothing here is logged.

/** A connection's state as its row says it. */
enum class ConnState { TESTED, NEEDS_KEY, NOT_TESTED, APP_MISSING, APP_REPLACED, READY,
    /** C10: a hand-off restored from a backup that isn't yours: its app isn't pinned here until you pick it again. */
    PICK_AGAIN }

object ConnSetup {
    /** The kinds Add a connection offers, in its order (plan §6.2). */
    val KINDS: List<ConnKind> = listOf(ConnKind.HOME_ASSISTANT, ConnKind.NTFY, ConnKind.WEBHOOK, ConnKind.ROUTING, ConnKind.HAND_OFF)

    /** The one operation an ntfy connection is made with ("publish"), its words from the `message` argument. */
    const val NTFY_OP = "publish" // l10n: not text (an operation id)
    const val MESSAGE = "message" // l10n: not text (an argument's name)

    private const val ABC = "abcdefghijklmnopqrstuvwxyz0123456789" // l10n: not text (an id's alphabet)

    /** A new connection id, "c-" and 6 lowercase letters or digits, not one of [taken]. */
    fun newId(taken: Set<String> = emptySet(), r: java.util.Random = java.security.SecureRandom()): String {
        while (true) {
            val id = "c-" + (1..6).map { ABC[r.nextInt(ABC.length)] }.joinToString("") // l10n: not text (an id)
            if (id !in taken) return id
        }
    }

    fun kindName(k: ConnKind): Phrase = when (k) {
        ConnKind.HOME_ASSISTANT -> ConnStrings.kindHa()
        ConnKind.NTFY -> ConnStrings.kindNtfy()
        ConnKind.WEBHOOK -> ConnStrings.kindWebhook()
        ConnKind.ROUTING -> ConnStrings.kindRouting()
        ConnKind.HAND_OFF -> ConnStrings.kindHandOff()
    }

    /** What the kind does and what leaves the phone (Add a connection's line, plan §6.2). */
    fun kindLine(k: ConnKind): Phrase = when (k) {
        ConnKind.HOME_ASSISTANT -> ConnStrings.kindHaLine()
        ConnKind.NTFY -> ConnStrings.kindNtfyLine()
        ConnKind.WEBHOOK -> ConnStrings.kindWebhookLine()
        ConnKind.ROUTING -> ConnStrings.kindRoutingLine()
        ConnKind.HAND_OFF -> ConnStrings.kindHandOffLine()
    }

    /**
     * A new connection [id] of [kind] called [name] at the address you typed. [auth]: how its key goes (Home Assistant's is
     * always a token; ntfy's a token or none; a routing server's none, a token or a header). [holdsSecret]: a webhook whose
     * address is itself the key: then nothing is typed here and the window takes the address (null and no problem: the
     * caller opens it). [ntfyTitle]: your title for ntfy's one operation, in the menus' language. Null with the problem
     * when it can't be saved as typed.
     */
    fun create(id: String, kind: ConnKind, name: String, address: String, auth: ConnAuth = ConnAuth.None, routing: RoutingKind? = null,
        ntfyTitle: String = "", onlyMine: Boolean = false): Pair<Connection?, ConnProblem?> {
        val n = name.trim()
        if (!ConnCheck.validLine(n, ConnCheck.MAX_NAME)) return null to ConnProblem.COULDNT_SAVE
        val url = ConnUrl.parse(address) ?: return null to ConnProblem.ADDRESS
        val c = when (kind) {
            ConnKind.HOME_ASSISTANT -> Connection(id, kind, n, url, auth = ConnAuth.Bearer)
            ConnKind.NTFY -> Connection(id, kind, n, url, auth = if (auth == ConnAuth.Bearer) ConnAuth.Bearer else ConnAuth.None,
                ops = listOf(ntfyOp(ntfyTitle, onlyMine)))
            ConnKind.WEBHOOK -> Connection(id, kind, n, url, auth = auth)
            ConnKind.ROUTING -> Connection(id, kind, n, url, auth = if (auth == ConnAuth.Basic) ConnAuth.None else auth, routing = routing ?: RoutingKind.OSRM)
            ConnKind.HAND_OFF -> return null to ConnProblem.COULDNT_SAVE
        }
        return problem(c)
    }

    /** ntfy's one operation: your title, the message from the step's `message` argument (at most 4,096 characters). */
    fun ntfyOp(title: String, onlyMine: Boolean = false): ConnOp = ConnOp(NTFY_OP, title.trim(), OpSpec.NtfyPublish("{{$MESSAGE}}", onlyMine = onlyMine),
        mapOf(MESSAGE to OpParam(ParamType.TEXT, maxLen = ConnCheck.MAX_TEXT)))

    /** [c] with its address changed to what you typed (answers for the old origin go, Test again); the caller deletes its key first. */
    fun readdress(c: Connection, typed: String): Pair<Connection?, ConnProblem?> {
        val url = ConnUrl.parse(typed) ?: return null to ConnProblem.ADDRESS
        return problem(ConnConsent.withAddress(c, url))
    }

    /** [c] if ConnCheck allows it, else its first problem. */
    fun problem(c: Connection): Pair<Connection?, ConnProblem?> = ConnCheck.problems(c).firstOrNull()?.let { null to it } ?: (c to null)

    /**
     * [c]'s state: [keySaved] (its key file holds its secrets), [app] (a hand-off's app as Android shows it now, null for
     * the other kinds). A hand-off has no Test: it is ready while its app is the one you picked.
     */
    fun state(c: Connection, keySaved: Boolean, app: AppPinState? = null): ConnState = when {
        c.kind == ConnKind.HAND_OFF -> when (app) {
            AppPinState.MISSING -> ConnState.APP_MISSING
            AppPinState.REPLACED -> ConnState.APP_REPLACED
            AppPinState.UNPINNED -> ConnState.PICK_AGAIN
            else -> if (c.handOff?.pinned == false) ConnState.PICK_AGAIN else ConnState.READY
        }
        c.secrets.isNotEmpty() && !keySaved -> ConnState.NEEDS_KEY
        c.tested == null -> ConnState.NOT_TESTED
        else -> ConnState.TESTED
    }

    /** [state] in words: "Tested today", "Needs its key", "Signal was replaced". */
    fun stateText(c: Connection, state: ConnState, now: ZonedDateTime): Phrase = when (state) {
        ConnState.TESTED -> ConnStrings.stateTested("day" to day(c.tested!!.at, now))
        ConnState.NEEDS_KEY -> ConnStrings.stateNeedsKey()
        ConnState.NOT_TESTED -> ConnStrings.stateNotTested()
        ConnState.APP_MISSING -> ConnStrings.appMissing("name" to c.name)
        ConnState.APP_REPLACED -> ConnStrings.stateReplaced("name" to c.name)
        ConnState.READY -> ConnStrings.stateReady()
        ConnState.PICK_AGAIN -> ConnStrings.statePickAgain("name" to c.name)
    }

    /** A row's line: "Home Assistant · Tested today". */
    fun row(c: Connection, state: ConnState, now: ZonedDateTime): Phrase = ConnStrings.rowLine("kind" to kindName(c.kind), "state" to stateText(c, state, now))

    /** The state is a problem a row marks (it can't be used until you act). */
    fun needsYou(state: ConnState): Boolean = state != ConnState.TESTED && state != ConnState.READY

    private fun day(at: Long, now: ZonedDateTime): Any {
        val d = Instant.ofEpochMilli(at).atZone(now.zone).toLocalDate()
        return when (d) {
            now.toLocalDate() -> RoutineStrings.today()
            now.toLocalDate().minusDays(1) -> RoutineStrings.yesterday()
            else -> d
        }
    }

    /** The skills whose steps name connection [id] (a `conn:` or `app:` step, or travel time naming it or using your only routing server). */
    fun usedBy(id: String, skills: List<Skill>, connections: List<Connection>): List<Skill> {
        val c = connections.firstOrNull { it.id == id }
        val onlyRouting = c?.kind == ConnKind.ROUTING && connections.count { it.kind == ConnKind.ROUTING } == 1
        return skills.filter { s ->
            s.steps.any { st ->
                st.conn == id || (st.kind == StepKind.TRAVEL_TIME && (st.args["conn"] == id || (st.args["conn"] == null && onlyRouting)))
            }
        }
    }

    /** How many of [skills] would need attention once connection [id] is gone, that don't now. */
    fun afterRemove(id: String, skills: List<Skill>, connections: List<Connection>, others: SkillLinks = SkillLinks.NONE): Int {
        val now = ConnLinks(connections, others)
        val after = ConnLinks(connections.filter { it.id != id }, others)
        return skills.count { SkillCheck.attention(it, now).isEmpty() && SkillCheck.attention(it, after).isNotEmpty() }
    }

    /** Remove's dialog text: the key goes, and how many skills will need attention. */
    fun removeText(n: Int): Phrase = if (n == 0) ConnStrings.removeTextNone() else ConnStrings.removeText(n)

    /**
     * Settings › Skills' Connections row (plan §6.1): "Home Assistant · ntfy · 1 needs attention" (your connections'
     * names, then how many skills need attention), or "None yet".
     */
    fun summary(connections: List<Connection>, attention: Int): Phrase {
        if (connections.isEmpty()) return ConnStrings.noneYet()
        val parts = connections.map { Phrase.plain(it.name) } + listOfNotNull(if (attention > 0) ConnStrings.attentionCount(attention) else null)
        return Phrase.Join(parts, " · ")
    }

    /** Why a step needs attention, in words: "Step 2 needs attention: its connection is gone". */
    fun attention(step: Int, why: AttentionWhy): Phrase = ConnStrings.attentionStep("n" to (step + 1).toString(), "why" to when (why) {
        AttentionWhy.CONNECTION_GONE -> ConnStrings.whyConnGone()
        AttentionWhy.APP_GONE -> ConnStrings.whyAppGone()
        AttentionWhy.SCRIPT_GONE -> ConnStrings.whyScriptGone()
        AttentionWhy.NO_ROUTING -> ConnStrings.whyNoRouting("page" to ConnStrings.page)
        AttentionWhy.DOESNT_FIT -> ConnStrings.whyDoesntFit()
    })

    /** Why a script stays untyped, in words (C8's ScriptParams, its line named). */
    fun scriptWhy(w: app.heyrobot.core.termux.ScriptParams.Why): Phrase = when (w) {
        app.heyrobot.core.termux.ScriptParams.Why.TooMany -> ConnStrings.scriptTooMany()
        is app.heyrobot.core.termux.ScriptParams.Why.Unreadable -> ConnStrings.scriptUnreadable("n" to w.line.toString())
        is app.heyrobot.core.termux.ScriptParams.Why.Repeated -> ConnStrings.scriptRepeated("n" to w.line.toString(), "name" to w.name)
    }

    /**
     * C8's warning: a number question filling a script's argument allows values outside what the script takes (the run
     * then refuses them). Null when it fits.
     */
    fun rangeWide(q: app.heyrobot.core.skills.SkillParam, min: Double?, max: Double?): Phrase? {
        val lo = q.min ?: return null
        val hi = q.max ?: return null
        if ((min == null || lo >= min) && (max == null || hi <= max)) return null
        fun t(d: Double?) = d?.let(app.heyrobot.core.skills.SkillNumbers::text) ?: "…"
        return ConnStrings.scriptRangeWide("min" to t(lo), "max" to t(hi), "smin" to t(min), "smax" to t(max))
    }
}
