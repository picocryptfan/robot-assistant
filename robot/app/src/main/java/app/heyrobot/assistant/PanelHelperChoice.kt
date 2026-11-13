// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.ChatTurn
import app.heyrobot.core.Escalation
import app.heyrobot.core.HelperName
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.helpers.HelperChoice
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.QuestionKind
import app.heyrobot.core.i18n.HelperStrings
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.memory.MemoryChoice
import app.heyrobot.core.memory.MemoryReplies

// VOICE PROCESS. "Always ask the helper for these", "use Robot for these", "ask the helper instead", "ask Robot instead"
// (docs/ask-claude.md "Who answers"): what send's router decided and the last question they act on. What the words mean is core
// HelperChoice's, what memory keeps MemoryPanel's, the "try it here or ask" card PanelHelperTurn's, and the re-send itself
// runs through PanelHelperTurn.run, or Robot's model in PanelController, which refuse what they always refused.

/** A choice to remember. [helper], [name]: the helper's id and name; null: the one Robot checks with. [tapOnly]: a yes won't do (L16). */
internal class KeepChoice(val kind: QuestionKind, val robot: Boolean, val helper: String? = null, val name: String? = null, val tapOnly: Boolean = false) {
    private val who: Any get() = name ?: HelperStrings.choiceYourHelper()

    /** The offer: "Always ask Jarvis for code questions?" */
    val offer: Phrase get() = if (robot) HelperStrings.choiceKeepRobot("kind" to kind.text) else HelperStrings.choiceKeepHelper("name" to who, "kind" to kind.text)

    /** The memory item's words. */
    val item: Phrase get() = kind.item(robot, name)

    val memory get() = MemoryChoice(kind.name, robot, helper)

    /** What's said once it is saved. [off]: "When Robot needs help" is off, so it does nothing by itself for now. */
    fun saved(off: Boolean): Phrase = when {
        robot -> HelperStrings.choiceSavedRobot("kind" to kind.text)
        off -> HelperStrings.choiceSavedOff("name" to who)
        else -> HelperStrings.choiceSavedHelper("name" to who, "kind" to kind.text)
    }
}

internal class PanelHelperChoice(private val port: Port) {
    /** What it needs of PanelController and MemoryPanel. */
    interface Port {
        val settings: RobotSettings
        val turnLangs: Langs
        fun locked(): Boolean
        /** The helper "ask the helper …" would reach now, if any. */
        fun askTarget(): HelperConfig?
        /** Saved in Memory; background thread. */
        suspend fun keepInMemory(c: MemoryChoice, text: String, spoken: Boolean): MemoryPanel.ChoiceSaved
        /** Your choice for [kind] forgotten; true when there was one; background thread. */
        suspend fun dropFromMemory(kind: String): MemoryPanel.ChoiceSaved
    }

    sealed class Route {
        class Command(val c: HelperChoice.Command) : Route()
        class Instead(val i: HelperChoice.Instead, val wrong: Boolean = false) : Route()
    }

    /** What a re-send comes to: words, the question sent ([to]), an offer of that ([ask]), or Robot's model. */
    sealed class Resend {
        class Say(val text: Phrase) : Resend()
        class To(val question: String, val h: HelperConfig?, val named: String?) : Resend()
        class Ask(val question: String, val h: HelperConfig) : Resend()
        class Robot(val question: String) : Resend()
        /** Nothing saved: [text] said, and only a tap on [k]'s offer keeps it. */
        class Keep(val text: Phrase, val k: KeepChoice) : Resend()
    }

    /** The last question, as it was asked; null when the last turn asked none. */
    var last: HelperChoice.Asked? = null
        private set

    /** Words this class answers, or null. */
    fun route(q: String, names: List<HelperName>): Route? = HelperChoice.command(q, names)?.let { Route.Command(it) }
        ?: HelperChoice.instead(q, names)?.let { Route.Instead(it, HelperChoice.wrong(q)) }

    /** This turn asked no question of a model (a timer, a card, a command): nothing to re-send. */
    fun forget() {
        last = null
    }

    fun asked(a: HelperChoice.Asked) {
        last = a
    }

    /** RP3: "that's wrong" said of the last answer: the Report it line for the reply it corrects, whatever the re-send comes to; else null. */
    fun reportNotice(route: Route, l: Langs): Notice? =
        last?.replyId?.takeIf { (route as? Route.Instead)?.wrong == true }?.let { Notice(PanelStrings.reportWrong().render(l), NoticeAction.REPORT, it) }

    /**
     * L16: what a turn of these words comes to. A route changes only from your own words in a conversation that holds nothing
     * Robot didn't write ([untrusted]): the Step.GO it opens sends questions online with no question asked, so otherwise a saved
     * one takes your tap, and one to forget stays. [personal]: the conversation holds anything personal, for a re-send.
     */
    suspend fun turn(route: Route, acted: Boolean, untrusted: Boolean, personal: Boolean, spoken: Boolean): Resend = when (route) {
        is Route.Instead -> resend(route.i, acted, untrusted || personal)
        is Route.Command -> {
            val c = route.c
            val kind = c.kind ?: last?.let { HelperChoice.kindOf(it.question) }
            when {
                !untrusted -> Resend.Say(run(c, spoken))
                kind == null -> Resend.Say(HelperStrings.choiceNoKind())
                c.drop -> Resend.Say(HelperStrings.choiceDropHeld())
                else -> Resend.Keep(HelperStrings.choiceKeepTap(), KeepChoice(kind, c.robot, c.helper, c.helper?.let { port.settings.helper(it)?.name }, tapOnly = true))
            }
        }
    }

    /** "Always ask the helper for these" and its undoing: Robot's answer. [spoken]: said, not typed. */
    suspend fun run(c: HelperChoice.Command, spoken: Boolean): Phrase {
        val kind = c.kind ?: last?.let { HelperChoice.kindOf(it.question) } ?: return HelperStrings.choiceNoKind()
        if (c.drop) return when (port.dropFromMemory(kind.name)) {
            MemoryPanel.ChoiceSaved.SAVED -> HelperStrings.choiceDropped("kind" to kind.text)
            MemoryPanel.ChoiceSaved.NONE -> HelperStrings.choiceNone("kind" to kind.text)
            else -> problem(MemoryPanel.ChoiceSaved.FAILED)
        }
        val name = c.helper?.let { port.settings.helper(it)?.name }
        return save(KeepChoice(kind, c.robot, c.helper, name), spoken)
    }

    /** An offer's yes or Remember, or a command: into Memory, and what Robot says of it. */
    suspend fun save(k: KeepChoice, spoken: Boolean): Phrase = when (val r = port.keepInMemory(k.memory, k.item.render(port.turnLangs), spoken)) {
        MemoryPanel.ChoiceSaved.SAVED -> k.saved(off = port.settings.help == Escalation.OFF)
        else -> problem(r)
    }

    private fun problem(r: MemoryPanel.ChoiceSaved): Phrase = when (r) {
        MemoryPanel.ChoiceSaved.OFF -> PanelStrings.memoryOff()
        MemoryPanel.ChoiceSaved.UNLOCK -> MemoryReplies.unlockText(saving = true)
        MemoryPanel.ChoiceSaved.FULL -> HelperStrings.choiceFull()
        else -> PanelStrings.memoryOff()
    }

    /**
     * "Ask the helper instead": may the last question go again, and where. Checked here, now, against what it was: [acted],
     * its reply holds a card; [tainted], the conversation holds what Robot didn't write or anything personal.
     */
    fun resend(i: HelperChoice.Instead, acted: Boolean, tainted: Boolean): Resend {
        val s = port.settings
        val named = i.helper?.let { s.helper(it) }
        val to = named ?: if (i.robot) null else port.askTarget()
        val asked = last
        val gate = HelperChoice.gate(i, asked, acted, port.locked(), tainted, to?.id)
        return when (gate) {
            HelperChoice.Gate.NOTHING -> Resend.Say(HelperStrings.insteadNothing())
            HelperChoice.Gate.ATTACHED -> Resend.Say(HelperStrings.insteadAttached())
            HelperChoice.Gate.ACTED -> Resend.Say(HelperStrings.insteadActed())
            HelperChoice.Gate.UNLOCK -> Resend.Say(HelperStrings.insteadUnlock())
            HelperChoice.Gate.SAME -> Resend.Say(if (i.robot) HelperStrings.insteadSameRobot() else HelperStrings.insteadSameHelper("name" to (to?.name ?: "")))
            HelperChoice.Gate.GO -> if (i.robot) Resend.Robot(asked!!.question) else Resend.To(asked!!.question, to, i.helper)
            // No helper to offer: PanelHelperTurn.run says what's missing.
            HelperChoice.Gate.ASK_FIRST -> if (to != null && to.on) Resend.Ask(asked!!.question, to) else Resend.To(asked!!.question, to, i.helper)
        }
    }
}
