// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.StepKind
import org.json.JSONObject

/** auto-open-plan §2: whether an app's name is in your own words this turn (AppMatcher's normalizing: no accents, no filler). */
object AppWords {
    /**
     * [label]'s words, all of them in order, are in [said] (with an open ask or one of [LEADS]); or, when your words asked to open something ([openAsked],
     * ToolRouter), one word of it of three letters or more ("open maps" for Organic Maps). Never a part of a word ("warden").
     * review-auto-open 2 and -b L4: a name counts only with an open ask or right after "pull up", "bring up" or "switch to" at
     * the start of your words, whatever its length; "what's on my Google Calendar?" that the model made open_app keeps its card.
     */
    fun named(said: String, label: String, openAsked: Boolean): Boolean {
        val words = AppMatcher.normalize(said).split(' ').filter { it.isNotEmpty() }
        val name = AppMatcher.normalize(label).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || name.isEmpty()) return false
        val at = words.windowed(name.size).indexOfFirst { it == name }
        if (at >= 0) return openAsked || LEADS.any { l -> at >= l.size && words.subList(at - l.size, at) == l && words.subList(0, at - l.size).all { it in START } }
        return openAsked && name.any { it.length >= 3 && it in words }
    }

    private val LEADS = listOf(listOf("pull", "up"), listOf("bring", "up"), listOf("switch", "to"))

    /** review-auto-open-b L2: all that may come before one of [LEADS]: a wake word or "please" ("should I switch to Signal?" keeps its card);
     *  review-auto-open-c L2: "can you", "could you" and "let's", as before "open" (Groups' OPEN_LEAD). */
    private val START = setOf("hey", "ok", "okay", "robot", "please", "just", "now", "can", "could", "would", "you", "lets",
        "let", "s") // "let's": normalize splits it at the apostrophe (lows-14)

    /**
     * review-auto-open 3: the installed apps that a word of [label] in your words fits as closely ("open maps" with Organic
     * Maps and Google Maps), when your words don't hold the whole label; two or more mean the pick card, whatever name the
     * model sent. Empty otherwise.
     */
    fun crowded(said: String, label: String, apps: List<AppEntry>): List<AppEntry> {
        val words = AppMatcher.normalize(said).split(' ').filter { it.isNotEmpty() }
        val name = AppMatcher.normalize(label).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || name.isEmpty() || words.windowed(name.size).any { it == name }) return emptyList()
        for (w in name) if (w.length >= 3 && w in words) AppMatcher.rivals(w, apps).let { if (it.size >= 2) return it }
        return emptyList()
    }
}

/**
 * files/robot/open_apps.json, `{"v":1,"named":true}` (settings.json stays at schema 13): "Open apps you name without a card",
 * on by default, also for an install that had none. A file Robot can't read turns it off (fail closed).
 */
data class OpenAppsFile(val named: Boolean = true) {
    companion object {
        const val NAME = "open_apps.json"
        const val VERSION = 1

        /** The file's "v", or null when it isn't JSON or has none. */
        fun version(text: String?): Int? = try {
            JSONObject(text ?: return null).let { o -> if (o.has("v")) o.getInt("v") else null }
        } catch (_: Exception) { null }

        fun write(f: OpenAppsFile): String = "{\"v\":1,\"named\":${f.named}}"

        fun read(text: String?): OpenAppsFile = if (text == null) OpenAppsFile() else try {
            val o = JSONObject(text)
            OpenAppsFile(named = o.optInt("v") == 1 && o.optBoolean("named", false))
        } catch (_: Exception) {
            OpenAppsFile(named = false)
        }
    }
}

/** auto-open-plan §1: an app a save card offers to open without asking: its label and its package. */
data class OpenTick(val label: String, val pkg: String)

/**
 * auto-open-plan §1: the save card's unticked box per app, "Open Organic Maps without asking when this runs". A tick saves the
 * package in the routine's or skill's `opens` (routines.json, skills.json); none is ticked for you. Whatever it says, Open apps
 * at Ask first or Off in Settings › Actions keeps the card, and the run opens only the ticked package it finds then.
 */
object OpenTicks {
    /**
     * The boxes for [names] (each open step's app, as saved) among [apps], one per package in step order: only a match that
     * isn't loose with no close rival (never a guess, AppMatcher.rivals). None for a draft made from content.
     */
    fun of(names: List<String>, apps: List<AppEntry>, fromContent: Boolean): List<OpenTick> {
        if (fromContent) return emptyList()
        return names.mapNotNull { n ->
            val m = AppMatcher.match(n, apps)?.takeIf { !it.loose } ?: return@mapNotNull null
            if (AppMatcher.rivals(n, apps).size >= 2) null else OpenTick(m.app.label, m.app.key.substringBefore('/'))
        }.distinctBy { it.pkg }
    }

    /** A skill's open steps with their app as fixed text (an app asked each time is a question: no box). */
    fun names(s: Skill): List<String> = s.steps.filter { it.kind == StepKind.OPEN_APP }
        .mapNotNull { (it.args["name"] as? String)?.takeIf { n -> n.isNotBlank() && "{{" !in n } }

    /** A routine's open steps. */
    fun names(r: Routine): List<String> = r.steps.mapNotNull { ((it as? RoutineStep.Tool)?.action as? Action.OpenApp)?.name }

    /** The box as a chip: "Open Organic Maps without asking when this runs · not ticked". */
    fun chip(t: OpenTick, on: Boolean): Phrase =
        SkillStrings.valueChip("value" to SkillStrings.openTick("name" to t.label), "state" to if (on) SkillStrings.ticked() else SkillStrings.notTicked())

    fun toggle(opens: Set<String>, t: OpenTick): Set<String> = if (t.pkg in opens) opens - t.pkg else opens + t.pkg

    /** [opens] less what the card no longer offers (an app made a question after it was ticked). */
    fun kept(opens: Set<String>, ticks: List<OpenTick>): Set<String> = opens.filterTo(LinkedHashSet()) { p -> ticks.any { it.pkg == p } }
}
