// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import app.heyrobot.core.ToolGroup
import app.heyrobot.core.ToolRouter
import app.heyrobot.core.files.FilesRouter
import app.heyrobot.core.i18n.InfoStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.MemoryStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings
import java.time.ZoneId

// Pure. What the panel does with your words before the model ever sees them, what
// Robot answers in English or Québec French, and what History keeps of it. The panel (:voice, assistant/MemoryPanel.kt)
// runs the command on the vault and shows the cards; everything it decides is here, so the JVM tests cover it.

/** What the router made of a question, before the model. Null from [MemoryRouting.route]: not memory's, the model answers. */
sealed class MemoryRoute {
    /** Run [command] now; Robot's own words answer it. */
    data class Run(val command: MemoryCommand) : MemoryRoute()

    /**
     * Locked, and the words ask for memory: "Unlock first" (nothing is read or kept). Reading needs "Use memory on the lock
     * screen"; changing it is never done there. [saving]: they asked to remember something; [changing]: to forget something.
     */
    data class Unlock(val language: String, val saving: Boolean = false, val changing: Boolean = false) : MemoryRoute()

    /** Memory is off and the words are a memory command: Robot says so; nothing is kept and the model never sees it. */
    data class Off(val language: String) : MemoryRoute()
}

object MemoryRouting {
    private val FRENCH_ASK = Regex("""\b(?:tu te (?:souviens|rappelles)|te (?:souviens|rappelles)-tu|je t'ai dit|sais sur moi|tu sais de moi)\b""")

    /**
     * The route for [text] with Settings › Memory [s] and the phone [locked]. A command that reads runs when memory is on
     * and usable (unlocked, or "Use memory on the lock screen"); one that changes it ("remember that …", "forget …") only on
     * an unlocked phone ("Unlock first to save to memory."); "forget everything" always gets its answer (Settings, never by
     * voice). A question that only asks for memory ("do you remember…") on a locked phone hears "Unlock first"; any other
     * question goes to the model, without memory. [saved]: whether a saved item answers a "where's my …" recall (fix-124-recall-b:
     * with none, the question routes as before; "where did I park" is memory's either way). Default: nothing saved.
     */
    fun route(text: String, s: MemorySettings, locked: Boolean, saved: (MemoryCommand.Recall) -> Boolean = { false }): MemoryRoute? {
        val c = MemoryCommands.parse(text)
        if (!s.on) return c?.let { MemoryRoute.Off(it.language) }
        val usable = !locked || s.onLockScreen
        return when {
            c is MemoryCommand.ForgetEverything -> MemoryRoute.Run(c)
            c is MemoryCommand.Remember && !MemoryAccess.writes(locked) -> MemoryRoute.Unlock(c.language, saving = true)
            (c is MemoryCommand.Forget || c is MemoryCommand.ForgetLast) && !MemoryAccess.writes(locked) -> MemoryRoute.Unlock(c.language, changing = true)
            c != null -> if (usable) MemoryRoute.Run(c) else MemoryRoute.Unlock(c.language)
            MemoryAccess.whenLocked(locked, s, text) == LockedMemory.SAY_UNLOCK -> MemoryRoute.Unlock(if (FRENCH_ASK.containsMatchIn(MemoryCommands.foldAligned(text))) "fr" else "en")
            // fix-124-recall: "where's my car" is Robot's own recall from memory, never the model's guess (MemoryWhere).
            usable && s.useInAnswers -> MemoryWhere.recall(text)?.takeIf { it.parking || saved(it) }?.let { MemoryRoute.Run(it) }
            else -> null
        }
    }
}

/**
 * fix-124-recall (Test Bench bug-everyday-b-1 on 1.2.3): "where's my car" after "remember I parked the car on level 3, row
 * B" got "I don't have access to your personal location" twice on the Pixel 8, though the note went with the question
 * (MemoryRecall finds it): Gemma refuses a where-question about your things. So "where is my …", "where did I park …", « où est
 * ma … », « où j'ai garé … » are memory's recall ("Here's what I remember about your car: …"), answered by
 * Robot from what you saved; with nothing saved, Robot says so. Never "where is the …" (a place), "my nearest …", or a file or drawer (Files).
 */
object MemoryWhere {
    private const val THING = """([\p{L}\p{N}' -]{1,40}?)""" // l10n: not text (pattern)
    private val EN_IS = Regex("""^where(?:'s| is| are|'re) (my|our) $THING[?.! ]*$""") // l10n: not text (words Robot hears)
    private val EN_DID = Regex("""^where (?:did|have) (?:i|we) (park|parked)(?: (my|our|the) $THING)?[?.! ]*$""") // l10n: not text (words Robot hears)
    private val FR_IS = Regex("""^o[uù] (?:est|sont|se trouve|se trouvent) (mon|ma|mes|notre|nos) $THING[?.! ]*$""") // l10n: not text (words Robot hears)
    private val FR_DID = Regex("""^o[uù] (?:est-ce que )?(?:j'ai|on a) (gar[ée]e?s?)(?: (mon|ma|mes|la|le|les|l') ?$THING)?[?.! ]*$""") // l10n: not text (words Robot hears)
    private val NOT_KEPT = setOf("nearest", "closest", "next", "local", "nearby", "current", "location", "position", "phone",
        "drawer", "drawers", "file", "files", "document", "documents", "folder", "tiroir", "tiroirs", "fichier", "fichiers", "dossier", "document") // l10n: not text (words Robot hears)
    private val YOURS = mapOf("my" to "your", "mon" to "ton", "ma" to "ta", "mes" to "tes") // l10n: not text (words Robot hears)

    /**
     * The recall [question] asks for ("your car", « ta voiture », "where you parked"), or null when it isn't one, or when the
     * files router or ToolRouter's FILES group claims its words ("where's my lease": fix-124-recall-b, Files first).
     */
    fun recall(question: String): MemoryCommand.Recall? =
        if (FilesRouter.route(question) != null || ToolGroup.FILES in ToolRouter.route(question)) null else heard(question)

    private fun heard(question: String): MemoryCommand.Recall? {
        val q = question.trim().replace('’', '\'').lowercase().replace(Regex("\\s+"), " ").removePrefix("robot, ").removePrefix("robot ")
        fun thing(own: String?, what: String?): String? {
            val w = what?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (w.substringBefore(' ') in NOT_KEPT) return null
            return (YOURS[own] ?: own)?.let { if (it.endsWith("'")) it + w else "$it $w" } ?: w
        }
        EN_IS.find(q)?.let { m -> return thing(m.groupValues[1], m.groupValues[2])?.let { MemoryCommand.Recall(it, "en") } }
        EN_DID.find(q)?.let { m ->
            val about = if (m.groupValues[3].isBlank()) "where you parked" else thing(m.groupValues[2], m.groupValues[3]) ?: return null // l10n: not text (the recall's search words)
            return MemoryCommand.Recall(about, "en", parking = true)
        }
        FR_IS.find(q)?.let { m -> return thing(m.groupValues[1], m.groupValues[2])?.let { MemoryCommand.Recall(it, "fr") } }
        FR_DID.find(q)?.let { m ->
            val about = if (m.groupValues[3].isBlank()) "l'endroit où tu t'es " + m.groupValues[1] else thing(m.groupValues[2], m.groupValues[3]) ?: return null // l10n: not text (the recall's search words)
            return MemoryCommand.Recall(about, "fr", parking = true)
        }
        return null
    }
}

/**
 * What Robot answers a memory command, in English or French ([fr]). Never an item's words for a secret one. 1.1: each
 * answer is also words without a language yet (the `…Text` functions, core i18n's [MemoryStrings]); the String ones
 * render them in English, or in French with [fr] (Robot's screens named in it too), for the callers not moved yet.
 */
object MemoryReplies {
    val PIN_FULL: String get() = " ${MemoryStrings.pinFull().en()}"
    val PIN_FULL_FR: String get() = " ${MemoryStrings.pinFull().render(Langs.of(Lang.FR))}"

    private val CALL_ME = Regex("""^(?:please )?call me (.+?)[.!]?$""", RegexOption.IGNORE_CASE)
    private val OWN = Regex("""^(?:i|i'm|i've|i'd|my|we|our)\b""")

    private fun langs(fr: Boolean): Langs = if (fr) Langs.of(Lang.FR) else Langs.EN

    /** Settings › Memory, as a sentence names it (in the menus' language). */
    private val page: UiLabel get() = UiStrings.inSettings(UiStrings.memory)

    /**
     * What Robot says back: MemoryCommands.saidBack, except that a preference telling Robot what to do is never turned to
     * "you" ("Call me Sam" is "I'll call you Sam.", "Always answer briefly" is quoted).
     */
    fun sayBack(c: MemoryCommand.Remember): String = sayBackText(c).render(langs(c.language == "fr"))

    /** [sayBack] in words without a language yet. */
    fun sayBackText(c: MemoryCommand.Remember): Phrase {
        val t = c.text.trim()
        if (c.secret || c.language == "fr" || c.kind != MemoryKind.PREFERENCE || OWN.containsMatchIn(MemoryCommands.foldAligned(t))) return MemoryCommands.saidBackText(c)
        return CALL_ME.find(t)?.let { MemoryStrings.callYou("name" to it.groupValues[1]) } ?: MemoryStrings.gotItQuoted("text" to t.trimEnd('.'))
    }

    /** After saving [c] (its outcome [o]), as words without a language yet. */
    fun savedText(c: MemoryCommand.Remember, o: AddOutcome): Phrase = when (o) {
        AddOutcome.SAVED -> sayBackText(c)
        AddOutcome.SAVED_PIN_FULL -> Phrase.join(sayBackText(c), MemoryStrings.pinFull())
        AddOutcome.ALREADY_KEPT -> MemoryStrings.already()
        AddOutcome.FULL -> MemoryStrings.full("page" to page)
        AddOutcome.EMPTY -> MemoryStrings.nothingToRemember()
    }


    fun forgottenText(n: Int): Phrase = if (n == 1) MemoryStrings.forgotten() else MemoryStrings.forgottenN(n)


    fun nothingToForgetText(): Phrase = MemoryStrings.nothingToForget()


    fun notFoundText(): Phrase = MemoryStrings.notFound()


    fun whichText(): Phrase = MemoryStrings.which()

    /**
     * One item read aloud: your words turned to "you" in English, quoted in French; a preference that tells Robot what to
     * do ("Call me Sam", "Always answer briefly") quoted as you said it.
     */
    fun spoken(item: MemoryItem, fr: Boolean): String = spokenText(item, fr).render(langs(fr))

    /** [spoken] in words without a language yet, made for [fr]'s reply (French quotes your words; English turns them to "you"). */
    fun spokenText(item: MemoryItem, fr: Boolean): Phrase {
        val t = item.text.trimEnd('.', ' ')
        val own = OWN.containsMatchIn(MemoryCommands.foldAligned(t))
        return when {
            fr || (item.kind == MemoryKind.PREFERENCE && !own) -> MemoryStrings.quoted("text" to t)
            else -> Phrase.plain(MemoryCommands.toYou(item.text))
        }
    }

    /** Items read aloud one after the other: "a; b" in English, « a » « b » in French. */
    private fun list(items: List<MemoryItem>, fr: Boolean): Phrase = Phrase.Join(items.map { spokenText(it, fr) }, if (fr) " " else "; ")

    /**
     * "What do you remember about Julie?": the [found] items (not secret: [private] counts those left out), or that none
     * matches, as words without a language yet, made for [fr]'s reply ([spokenText]).
     */
    fun recallText(about: String, found: List<MemoryItem>, private: Int, fr: Boolean, parking: Boolean = false): Phrase {
        val more = if (private == 0) null else MemoryStrings.privateMore(private, "page" to page)
        val first = if (found.isEmpty()) MemoryStrings.recallNone("about" to about) else MemoryStrings.recall("about" to about, "list" to list(found, fr))
        // fix-124-recall-b: "where did I park" with nothing saved says how to save it.
        val how = if (found.isEmpty() && private == 0 && parking) MemoryStrings.parkedHow() else null
        return Phrase.Join(listOfNotNull(first, more, how), " ")
    }

    /**
     * "What do you know about me?": how many, and a few of them (the most used, never a secret one), as words without a
     * language yet, made for [fr]'s reply ([spokenText]).
     */
    fun aboutMeText(total: Int, examples: List<MemoryItem>, fr: Boolean): Phrase {
        if (total == 0) return MemoryStrings.aboutMeNone()
        val count = MemoryStrings.aboutMeCount(total)
        if (examples.isEmpty()) return Phrase.join(count, MemoryStrings.aboutMePrivate("page" to page))
        return Phrase.join(count, MemoryStrings.aboutMeExamples("list" to list(examples, fr)))
    }

    fun forgetAllText(): Phrase = MemoryAccess.forgetAllByVoice()

    fun unlockText(saving: Boolean = false, changing: Boolean = false): Phrase = when {
        saving -> MemoryStrings.unlockToSave()
        changing -> MemoryStrings.unlockToChange()
        else -> MemoryStrings.unlockFirst()
    }

    fun offText(): Phrase = MemoryStrings.off()

    /** Memory couldn't be opened on an unlocked phone ([load] not usable): why, in words. */
    fun cantOpenText(load: MemoryLoad): Phrase = when (load) {
        MemoryLoad.KeyGone -> MemoryStrings.keyGone("page" to page)
        MemoryLoad.Unreadable -> MemoryStrings.unreadable("page" to page)
        is MemoryLoad.TooNew -> MemoryStrings.tooNew()
        else -> MemoryStrings.notNow()
    }

    /** The card for an item saved by voice: its summary (History keeps it: no words of the item), without a language yet. */
    fun savedCardText(item: MemoryItem): Phrase =
        if (item.kind == MemoryKind.PREFERENCE && item.pinned) MemoryStrings.cardSavedPinned() else MemoryStrings.cardSaved()

    fun cardForgottenText(): Phrase = MemoryStrings.cardForgotten()
    fun cardWhichText(): Phrase = MemoryStrings.cardWhich()
    fun cardSavedDetailText(): Phrase = MemoryStrings.cardSavedDetail("undo" to UiLabel(UiStrings.undo), "page" to UiLabel(UiStrings.memory))
    fun cardForgottenDetailText(): Phrase = MemoryStrings.cardForgottenDetail("undo" to UiLabel(UiStrings.undo))
}

/**
 * What History keeps of memory: the notes block never (ChatTurn.memoryIds instead),
 * a question that saved a secret only as "Remember: [a code, kept in Memory]", an answer that showed a secret only as a
 * line saying so, and card summaries without an item's words.
 */
object MemoryHygiene {
    /** An item as a card shows it: its words, or for a secret what kind of secret it is ("[a code, kept in Memory]"). */
    fun shown(item: MemoryItem, fr: Boolean = false): String =
        if (item.secret) SecretDetector.placeholder(SecretDetector.find(item.text), fr) else item.text

    /** [shown] in words without a language yet: a secret's placeholder in the card's language, your words as they are. */
    fun shownText(item: MemoryItem): Phrase =
        if (item.secret) SecretDetector.placeholderText(SecretDetector.find(item.text)) else Phrase.plain(item.text)

    /** The question History keeps for "remember that …" ([said]: your words). A secret is never kept in them. */
    fun question(c: MemoryCommand.Remember, said: String): String =
        if (!c.secret) said
        else MemoryStrings.rememberSecret("what" to SecretDetector.placeholderText(SecretDetector.find(c.text))).render(if (c.language == "fr") Langs.of(Lang.FR) else Langs.EN)

    /**
     * The question History keeps for what the memory router took ([route]; [said]: your words). 1.0 audit
     * (secrets-history-refused-remember): a "remember that …" that wasn't saved (the phone locked, Memory off) keeps a
     * placeholder, never its words; any other refused memory command loses them too if they hold something private (a
     * code, a password, a card number).
     */
    fun question(route: MemoryRoute, said: String): String {
        if (route is MemoryRoute.Run) return (route.command as? MemoryCommand.Remember)?.let { question(it, said) } ?: said
        val fr = when (route) {
            is MemoryRoute.Unlock -> route.language
            is MemoryRoute.Off -> route.language
            else -> "en"
        } == "fr"
        val remember = MemoryCommands.parse(said) is MemoryCommand.Remember || (route as? MemoryRoute.Unlock)?.saving == true
        return when {
            remember -> if (fr) NOT_SAVED_FR else NOT_SAVED
            SecretDetector.isSecret(said) -> if (fr) PRIVATE_FR else PRIVATE
            else -> said
        }
    }

    val NOT_SAVED: String get() = MemoryStrings.notSaved().en()
    val NOT_SAVED_FR: String get() = MemoryStrings.notSaved().render(Langs.of(Lang.FR))
    val PRIVATE: String get() = MemoryStrings.privateQuestion().en()
    val PRIVATE_FR: String get() = MemoryStrings.privateQuestion().render(Langs.of(Lang.FR))

    /** The answer History keeps for a reply that had a secret item before it: never the answer itself. */
    val SECRET_ANSWER: String get() = MemoryStrings.secretAnswer().en()

    /** Items as a short list for a card's body, one a line, secrets as their placeholder. */
    fun list(items: List<MemoryItem>): String = items.joinToString("\n") { "• " + shown(it) }
}

/** A day and an end, as Settings › Memory and the cards show them ("Saved Oct 9 · until tonight"). */
object MemoryDates {
    fun line(item: MemoryItem, now: Long, zone: ZoneId = ZoneId.systemDefault()): String = lineText(item, now, zone).en()

    /** [line] in words without a language yet (a screen: the menus' language). */
    fun lineText(item: MemoryItem, now: Long, zone: ZoneId = ZoneId.systemDefault()): Phrase {
        val day = { t: Long -> day(java.time.Instant.ofEpochMilli(t).atZone(zone).toLocalDate()) }
        val first = !item.pinned && !item.secret
        return Phrase.Join(listOfNotNull(
            if (item.pinned) MemoryStrings.datePinned() else null,
            if (item.secret) MemoryStrings.datePrivate() else null,
            (if (first) MemoryStrings.dateSavedFirst else MemoryStrings.dateSaved)("day" to day(item.createdAt)),
            item.expiresAt?.let { if (now >= it) MemoryStrings.dateEnded() else MemoryStrings.dateUntil("day" to day(it)) },
            if (item.uses > 0) MemoryStrings.dateUsed(item.uses) else null,
        ), " · ")
    }

    /** "Oct 9", « 9 octobre », « 1er octobre ». */
    private fun day(d: java.time.LocalDate): Phrase {
        val months = listOf(InfoStrings.jan, InfoStrings.feb, InfoStrings.mar, InfoStrings.apr, InfoStrings.may, InfoStrings.jun, InfoStrings.jul,
            InfoStrings.aug, InfoStrings.sep, InfoStrings.oct, InfoStrings.nov, InfoStrings.dec)
        val n: Any = if (d.dayOfMonth == 1) InfoStrings.firstOfMonth() else d.dayOfMonth.toString()
        return MemoryStrings.dateDay("month" to months[d.monthValue - 1](), "day" to n)
    }
}
