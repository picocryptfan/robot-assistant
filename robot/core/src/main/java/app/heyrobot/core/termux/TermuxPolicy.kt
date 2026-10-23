// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.termux

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.TermuxStrings
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings

// Pure. Whether a Termux command or script runs now, shows its card, needs a tap, or is refused. The fixed rules, which no setting loosens:
//  - every command shows a Run / Edit / Cancel card; the only exceptions are your own scripts set to Just do it and,
//    with "Read-only commands without a card" on, commands on the parsed read-only allowlist (both off by default);
//  - with content Robot didn't write anywhere in the conversation (or a command taken from such content), every command
//    shows its card, answered by a tap (your own script named in your own words, without arguments, aside);
//  - a command with a warning, or one Robot couldn't read fully, needs a tap: a spoken or typed yes says why and waits;
//  - free-form commands never run on the lock screen;
//  - Robot's own secrets never go into a command: one of its keys by value, or (in a command it didn't take from your
//    typing) something shaped like one, refuses the command; so does a fork bomb.
// The gate's AskWhy.COMMAND / FROM_CONTENT and CardAnswer's tap words are wired from [TermuxDecision.tap] when the agent
// lands (Approval.kt and Safety.kt are shared, so not here).

/** Who wrote the command. */
enum class CommandSource {
    /** You typed it (in the panel, or by Edit on its card). */
    TYPED,
    /** Your words, transcribed. */
    SPOKEN,
    /** Gemma wrote it. */
    MODEL,
    /** A helper (Claude, another AI) wrote it. */
    HELPER,
    /** It came from content Robot didn't write: a web page, a note, screen text, a read tool's findings, earlier Termux output. */
    CONTENT,
}

enum class TermuxVerdict {
    /** Runs now (an allowlisted read, or your script set to Just do it). */
    RUN,
    /** Shows its card; a tap on Run or a spoken or typed yes runs it. */
    CARD,
    /** Shows its card; only a tap on Run runs it. */
    TAP_ONLY,
    /** Not run, and no card offers it: [TermuxDecision.say] says why. */
    REFUSE,
}

/**
 * Why a decision was made, in words for the card or for Robot to say ([text]; [en] its English, what the card and the
 * model read until the turn's cards take phrases). A Robot page named in it is in the menus' language, and so is the
 * button it says to tap ("Settings › Termux", "Run": core UiStrings).
 */
enum class TermuxReason {
    OFF, COMMANDS_OFF, SCRIPT_OFF, HELPERS_OFF, LOCKED, API_OFF, OWN_SECRET, KEY_SHAPE, FORK_BOMB, TOO_LONG, EMPTY, CANT_SEND, NOT_A_SCRIPT,
    BAD_ARGUMENTS,

    ASK_FIRST, FROM_CONTENT, WARNINGS, UNREADABLE, TAP_SETTING,

    READ_ONLY, YOUR_SCRIPT;

    val text: Phrase get() = when (this) {
        OFF -> TermuxStrings.reasonOff("path" to SETTINGS)
        COMMANDS_OFF -> TermuxStrings.reasonCommandsOff("path" to SETTINGS)
        SCRIPT_OFF -> TermuxStrings.reasonScriptOff("path" to SETTINGS)
        HELPERS_OFF -> TermuxStrings.reasonHelpersOff("path" to SETTINGS)
        LOCKED -> TermuxStrings.reasonLocked()
        API_OFF -> TermuxStrings.reasonApiOff("path" to SETTINGS)
        OWN_SECRET -> TermuxStrings.reasonOwnSecret()
        KEY_SHAPE -> TermuxStrings.reasonKeyShape()
        FORK_BOMB -> TermuxStrings.reasonForkBomb()
        TOO_LONG -> TermuxStrings.reasonTooLong("n" to CommandAnalyzer.MAX_CHARS)
        EMPTY -> TermuxStrings.reasonEmpty()
        CANT_SEND -> TermuxStrings.reasonCantSend()
        NOT_A_SCRIPT -> TermuxStrings.reasonNotAScript()
        BAD_ARGUMENTS -> TermuxStrings.reasonBadArguments()
        ASK_FIRST -> TermuxStrings.reasonAskFirst()
        FROM_CONTENT -> TermuxStrings.reasonFromContent("run" to RUN)
        WARNINGS -> TermuxStrings.reasonWarnings("run" to RUN)
        UNREADABLE -> TermuxStrings.reasonUnreadable("run" to RUN)
        TAP_SETTING -> TermuxStrings.reasonTapSetting("path" to SETTINGS)
        READ_ONLY -> TermuxStrings.reasonReadOnly()
        YOUR_SCRIPT -> TermuxStrings.reasonYourScript("level" to ScriptLevel.AUTO.text)
    }

    val en: String get() = text.en()

    fun text(fr: Boolean): String = text.render(Langs.of(if (fr) Lang.FR else Lang.EN))

    companion object {
        /** Settings › Termux, as a sentence names it. */
        val SETTINGS: UiLabel get() = UiStrings.inSettings(UiStrings.termux)

        /** The card's Run button, as a sentence names it. */
        val RUN: UiLabel get() = UiLabel(UiStrings.run)
    }
}

/** What a tap-only card's tap is for (the gate's AskWhy.COMMAND and AskWhy.FROM_CONTENT when wired). */
enum class TermuxTap { COMMAND, FROM_CONTENT }

data class TermuxDecision(val verdict: TermuxVerdict, val reasons: List<TermuxReason>, val warnings: Set<CommandWarning> = emptySet()) {
    /** Why a tap is needed, or null when a yes may answer the card (or there is no card). */
    val tap: TermuxTap? get() = when {
        verdict != TermuxVerdict.TAP_ONLY -> null
        TermuxReason.FROM_CONTENT in reasons -> TermuxTap.FROM_CONTENT
        else -> TermuxTap.COMMAND
    }

    /**
     * What Robot says: why it refuses, or why a spoken or typed yes can't run this card ("This command deletes files or
     * empties them: check the card, then tap Run."); null otherwise.
     */
    fun sayText(): Phrase? = when (verdict) {
        TermuxVerdict.REFUSE -> reasons.firstOrNull()?.text
        TermuxVerdict.TAP_ONLY -> tapWords()
        else -> null
    }

    /** What Robot says when the card appears and replies are spoken (B5.3); "no" always works. */
    fun promptText(): Phrase? = when (verdict) {
        TermuxVerdict.CARD -> TermuxStrings.prompt()
        TermuxVerdict.TAP_ONLY -> TermuxStrings.orSayNo("text" to tapWords())
        else -> null
    }

    /** [sayText] in English ([fr]: in French): the card's and the model's words until the turn's cards take phrases. */
    fun say(fr: Boolean = false): String? = sayText()?.render(langs(fr))

    /** [promptText] in English ([fr]: in French). */
    fun prompt(fr: Boolean = false): String? = promptText()?.render(langs(fr))

    private fun langs(fr: Boolean) = Langs.of(if (fr) Lang.FR else Lang.EN)

    private fun tapWords(): Phrase {
        if (TermuxReason.FROM_CONTENT in reasons) return TermuxReason.FROM_CONTENT.text
        val w = warnings.firstOrNull { it != CommandWarning.UNREADABLE }
        if (w?.what != null) return TermuxStrings.commandWarns("what" to w.what(), "run" to TermuxReason.RUN)
        if (CommandWarning.UNREADABLE in warnings) return TermuxReason.UNREADABLE.text
        return TermuxReason.TAP_SETTING.text
    }
}

object TermuxPolicy {
    /**
     * A command written in full. [untrustedConversation]: the conversation holds content Robot didn't write (screen
     * text, a photo, a read tool's findings, web pages, a helper's words, earlier Termux output). [ownSecrets]: Robot's own
     * keys, by value (checked in :voice, never logged).
     */
    fun command(analysis: CommandAnalysis, source: CommandSource, settings: TermuxSettings, locked: Boolean, untrustedConversation: Boolean,
        ownSecrets: Collection<String> = emptyList()): TermuxDecision {
        val text = analysis.command
        val w = analysis.warnings
        fun refuse(r: TermuxReason) = TermuxDecision(TermuxVerdict.REFUSE, listOf(r), w)
        when {
            !settings.enabled -> return refuse(TermuxReason.OFF)
            settings.commands == CommandLevel.OFF -> return refuse(TermuxReason.COMMANDS_OFF)
            source == CommandSource.HELPER && !settings.helpers -> return refuse(TermuxReason.HELPERS_OFF)
            locked -> return refuse(TermuxReason.LOCKED)
            text.isBlank() -> return refuse(TermuxReason.EMPTY)
            text.length > CommandAnalyzer.MAX_CHARS -> return refuse(TermuxReason.TOO_LONG)
            '\u0000' in text -> return refuse(TermuxReason.CANT_SEND)
            SecretMask.hasOwn(text, ownSecrets) -> return refuse(TermuxReason.OWN_SECRET)
            source != CommandSource.TYPED && SecretMask.hasRobotKey(text) -> return refuse(TermuxReason.KEY_SHAPE)
            CommandWarning.FORK_BOMB in w -> return refuse(TermuxReason.FORK_BOMB)
            analysis.api.isNotEmpty() && settings.api == ApiCommands.NOT_ALLOWED -> return refuse(TermuxReason.API_OFF)
        }
        val fromContent = untrustedConversation || source == CommandSource.CONTENT
        val trustedSource = source == CommandSource.TYPED || source == CommandSource.SPOKEN || source == CommandSource.MODEL
        if (!fromContent && trustedSource && settings.readOnlyWithoutCard && analysis.readOnly && w.isEmpty() && !analysis.complex) {
            return TermuxDecision(TermuxVerdict.RUN, listOf(TermuxReason.READ_ONLY), w)
        }
        val reasons = ArrayList<TermuxReason>()
        if (fromContent) reasons += TermuxReason.FROM_CONTENT
        if (analysis.complex) reasons += TermuxReason.UNREADABLE
        if (w.any { it != CommandWarning.UNREADABLE }) reasons += TermuxReason.WARNINGS
        if (settings.spokenYes == SpokenYes.NEVER) reasons += TermuxReason.TAP_SETTING
        if (reasons.isEmpty()) return TermuxDecision(TermuxVerdict.CARD, listOf(TermuxReason.ASK_FIRST), w)
        return TermuxDecision(TermuxVerdict.TAP_ONLY, reasons, w)
    }

    /**
     * One of your scripts in ~/robot, with [args] as you (or Gemma) wrote them: split by Robot's own lexer into argv,
     * never read by a shell. [namedInOwnWords]: your own words this turn named it (ScriptRegistry matched them), which
     * with no arguments is rule 3's exception. [known]: the scripts Robot listed in the folder, when known.
     */
    fun script(name: String, args: String, source: CommandSource, settings: TermuxSettings, locked: Boolean, untrustedConversation: Boolean,
        namedInOwnWords: Boolean, ownSecrets: Collection<String> = emptyList(), known: Set<String>? = null): TermuxDecision {
        fun refuse(r: TermuxReason) = TermuxDecision(TermuxVerdict.REFUSE, listOf(r))
        if (!settings.enabled) return refuse(TermuxReason.OFF)
        if (!TermuxScripts.validName(name) || (known != null && name !in known)) return refuse(TermuxReason.NOT_A_SCRIPT)
        val level = settings.script(name)
        if (level == ScriptLevel.OFF) return refuse(TermuxReason.SCRIPT_OFF)
        if (source == CommandSource.HELPER && !settings.helpers) return refuse(TermuxReason.HELPERS_OFF)
        if (locked && name !in settings.lockScreenScripts) return refuse(TermuxReason.LOCKED)
        val argv = ShellLexer.argv(args) ?: return refuse(TermuxReason.BAD_ARGUMENTS)
        if (SecretMask.hasOwn(args, ownSecrets)) return refuse(TermuxReason.OWN_SECRET)
        if (source != CommandSource.TYPED && SecretMask.hasRobotKey(args)) return refuse(TermuxReason.KEY_SHAPE)
        val warnings = LinkedHashSet<CommandWarning>()
        if (CommandAnalyzer.hidden(args)) warnings += CommandWarning.HIDDEN_CHARACTERS
        if (SecretMask.find(args).isNotEmpty()) warnings += CommandWarning.SECRET
        val own = source != CommandSource.CONTENT && source != CommandSource.HELPER
        val fromContent = source == CommandSource.CONTENT || (untrustedConversation && !(own && namedInOwnWords && argv.isEmpty()))
        if (!fromContent && own && level == ScriptLevel.AUTO && warnings.isEmpty()) {
            return TermuxDecision(TermuxVerdict.RUN, listOf(TermuxReason.YOUR_SCRIPT), warnings)
        }
        val reasons = ArrayList<TermuxReason>()
        if (fromContent) reasons += TermuxReason.FROM_CONTENT
        if (warnings.isNotEmpty()) reasons += TermuxReason.WARNINGS
        if (settings.spokenYes == SpokenYes.NEVER) reasons += TermuxReason.TAP_SETTING
        if (reasons.isEmpty()) return TermuxDecision(TermuxVerdict.CARD, listOf(TermuxReason.ASK_FIRST), warnings)
        return TermuxDecision(TermuxVerdict.TAP_ONLY, reasons, warnings)
    }
}
