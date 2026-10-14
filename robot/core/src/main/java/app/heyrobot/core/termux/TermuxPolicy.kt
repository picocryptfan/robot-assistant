package app.heyrobot.core.termux

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

/** Why a decision was made, in words for the card or for Robot to say. */
enum class TermuxReason(val en: String, val fr: String) {
    OFF("Termux is off in Settings › Termux.", "Termux est désactivé dans Réglages › Termux."),
    COMMANDS_OFF("Termux commands are off in Settings › Termux.", "Les commandes Termux sont désactivées dans Réglages › Termux."),
    SCRIPT_OFF("That script is off in Settings › Termux.", "Ce script est désactivé dans Réglages › Termux."),
    HELPERS_OFF("Helpers may not ask for Termux commands (Settings › Termux).", "Les assistants ne peuvent pas demander de commandes Termux (Réglages › Termux)."),
    LOCKED("Unlock your phone to run this in Termux.", "Déverrouillez votre téléphone pour lancer ceci dans Termux."),
    API_OFF("Termux:API commands are off in Settings › Termux.", "Les commandes Termux:API sont désactivées dans Réglages › Termux."),
    OWN_SECRET("This holds one of Robot's own keys. Robot never puts them in a command.", "Ceci contient une des clés de Robot. Robot ne les met jamais dans une commande."),
    KEY_SHAPE("This holds what looks like an API key, and you didn't type it. Robot won't run it.",
        "Ceci contient ce qui ressemble à une clé d'API, et vous ne l'avez pas tapée. Robot ne la lancera pas."),
    FORK_BOMB("This would start copies of itself until the phone stalls. Robot won't run it.",
        "Ceci se recopierait sans fin jusqu'à bloquer le téléphone. Robot ne le lancera pas."),
    TOO_LONG("This command is too long for Robot to check (at most 4,096 characters).", "Cette commande est trop longue pour que Robot la vérifie (4 096 caractères au plus)."),
    EMPTY("There is no command to run.", "Il n'y a pas de commande à lancer."),
    CANT_SEND("This command holds a character Termux can't receive.", "Cette commande contient un caractère que Termux ne peut pas recevoir."),
    NOT_A_SCRIPT("That isn't one of your scripts in ~/robot.", "Ce n'est pas un de vos scripts dans ~/robot."),
    BAD_ARGUMENTS("Robot couldn't split the arguments: check the quotes; no line breaks.", "Robot n'a pas pu séparer les arguments : vérifiez les guillemets ; pas de retour à la ligne."),

    ASK_FIRST("Every command shows its card first.", "Chaque commande montre d'abord sa carte."),
    FROM_CONTENT("Robot read this from content it didn't write: check the card, then tap Run.",
        "Robot a lu ceci dans un contenu qu'il n'a pas écrit : vérifiez la carte, puis touchez Exécuter."),
    WARNINGS("This command has warnings: check the card, then tap Run.", "Cette commande a des avertissements : vérifiez la carte, puis touchez Exécuter."),
    UNREADABLE("Robot couldn't read this command fully: check the card, then tap Run.",
        "Robot n'a pas pu lire cette commande en entier : vérifiez la carte, puis touchez Exécuter."),
    TAP_SETTING("Commands run only with a tap (Settings › Termux).", "Les commandes ne se lancent qu'en touchant (Réglages › Termux)."),

    READ_ONLY("A read-only command on Robot's list.", "Une commande de lecture seule de la liste de Robot."),
    YOUR_SCRIPT("Your script, set to Just do it.", "Votre script, réglé sur Sans demander.");

    fun text(fr: Boolean): String = if (fr) this.fr else en
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
    fun say(fr: Boolean = false): String? = when (verdict) {
        TermuxVerdict.REFUSE -> reasons.firstOrNull()?.text(fr)
        TermuxVerdict.TAP_ONLY -> tapWords(fr)
        else -> null
    }

    /** What Robot says when the card appears and replies are spoken (B5.3); "no" always works. */
    fun prompt(fr: Boolean = false): String? = when (verdict) {
        TermuxVerdict.CARD -> if (fr) "Lancer ceci dans Termux ? Dites oui ou non." else "Run this in Termux? Say yes or no."
        TermuxVerdict.TAP_ONLY -> tapWords(fr) + if (fr) " Ou dites non." else " Or say no."
        else -> null
    }

    private fun tapWords(fr: Boolean): String {
        if (TermuxReason.FROM_CONTENT in reasons) return TermuxReason.FROM_CONTENT.text(fr)
        val w = warnings.firstOrNull { it != CommandWarning.UNREADABLE }
        if (w != null) {
            val what = w.text(fr).substringBefore(':').substringBefore('.').trim()
            val lower = what.replaceFirstChar { it.lowercaseChar() }
            return if (fr) "Cette commande $lower : vérifiez la carte, puis touchez Exécuter." else "This command $lower: check the card, then tap Run."
        }
        if (CommandWarning.UNREADABLE in warnings) return TermuxReason.UNREADABLE.text(fr)
        return TermuxReason.TAP_SETTING.text(fr)
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
