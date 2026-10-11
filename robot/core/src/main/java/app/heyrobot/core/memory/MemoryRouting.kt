package app.heyrobot.core.memory

import java.time.ZoneId

// Pure. What the panel does with your words before the model ever sees them (design §A3.1, the router's half), what
// Robot answers in English or Québec French, and what History keeps of it. The panel (:voice, assistant/MemoryPanel.kt)
// runs the command on the vault and shows the cards; everything it decides is here, so the JVM tests cover it.

/** What the router made of a question, before the model. Null from [MemoryRouting.route]: not memory's, the model answers. */
sealed class MemoryRoute {
    /** Run [command] now; Robot's own words answer it. */
    data class Run(val command: MemoryCommand) : MemoryRoute()

    /**
     * Locked, memory not on the lock screen, and the words ask for memory: "Unlock first" (nothing is read or kept).
     * [saving]: they asked to remember something.
     */
    data class Unlock(val language: String, val saving: Boolean = false) : MemoryRoute()

    /** Memory is off and the words are a memory command: Robot says so; nothing is kept and the model never sees it. */
    data class Off(val language: String) : MemoryRoute()
}

object MemoryRouting {
    private val FRENCH_ASK = Regex("""\b(?:tu te (?:souviens|rappelles)|te (?:souviens|rappelles)-tu|je t'ai dit|sais sur moi|tu sais de moi)\b""")

    /**
     * The route for [text] with Settings › Memory [s] and the phone [locked]. A command runs when memory is on and usable
     * (unlocked, or "Use memory on the lock screen"); "forget everything" always gets its answer (Settings, never by voice).
     * A question that only asks for memory ("do you remember…") on a locked phone hears "Unlock first"; any other question
     * goes to the model, without memory.
     */
    fun route(text: String, s: MemorySettings, locked: Boolean): MemoryRoute? {
        val c = MemoryCommands.parse(text)
        if (!s.on) return c?.let { MemoryRoute.Off(it.language) }
        val usable = !locked || s.onLockScreen
        return when {
            c is MemoryCommand.ForgetEverything -> MemoryRoute.Run(c)
            c != null -> if (usable) MemoryRoute.Run(c) else MemoryRoute.Unlock(c.language, saving = c is MemoryCommand.Remember)
            MemoryAccess.whenLocked(locked, s, text) == LockedMemory.SAY_UNLOCK -> MemoryRoute.Unlock(if (FRENCH_ASK.containsMatchIn(MemoryCommands.foldAligned(text))) "fr" else "en")
            else -> null
        }
    }
}

/** What Robot answers a memory command, in English or French ([fr]). Never an item's words for a secret one. */
object MemoryReplies {
    const val PIN_FULL = " Six things are pinned already, so this one isn't used in every answer."
    const val PIN_FULL_FR = " Six choses sont déjà épinglées : celle-ci ne l'est pas."

    /** After saving [c] (its outcome [o]). */
    fun saved(c: MemoryCommand.Remember, o: AddOutcome): String {
        val fr = c.language == "fr"
        return when (o) {
            AddOutcome.SAVED -> MemoryCommands.saidBack(c)
            AddOutcome.SAVED_PIN_FULL -> MemoryCommands.saidBack(c) + if (fr) PIN_FULL_FR else PIN_FULL
            AddOutcome.ALREADY_KEPT -> if (fr) "Je m'en souviens déjà." else "I already remember that."
            AddOutcome.FULL -> if (fr) "Ma mémoire est pleine. Efface des choses dans Réglages › Mémoire d'abord." else
                "My memory is full. Delete some things in Settings › Memory first."
            AddOutcome.EMPTY -> if (fr) "Il n'y avait rien à retenir." else "There was nothing to remember."
        }
    }

    fun forgotten(n: Int, fr: Boolean): String = when {
        fr -> if (n == 1) "C'est oublié." else "C'est oublié : $n choses."
        else -> if (n == 1) "Forgotten." else "Forgotten: $n things."
    }

    fun nothingToForget(fr: Boolean): String =
        if (fr) "Il n'y a rien de cette conversation à oublier." else "There's nothing from this conversation to forget."

    fun notFound(fr: Boolean): String = if (fr) "Je ne me souviens de rien à ce sujet." else "I don't remember anything about that."

    fun which(fr: Boolean): String = if (fr) "Laquelle dois-je oublier ?" else "Which one should I forget?"

    /**
     * One item read aloud: your words turned to "you" in English, quoted in French; a preference that tells Robot what to
     * do ("Call me Sam", "Always answer briefly") quoted as you said it.
     */
    fun spoken(item: MemoryItem, fr: Boolean): String {
        val t = item.text.trimEnd('.', ' ')
        val own = Regex("""^(?:i|i'm|i've|i'd|my|we|our)\b""").containsMatchIn(MemoryCommands.foldAligned(t))
        return when {
            fr -> "« $t »"
            item.kind == MemoryKind.PREFERENCE && !own -> "“$t”"
            else -> MemoryCommands.toYou(item.text)
        }
    }

    /**
     * "What do you remember about Julie?": the [found] items (not secret: [private] counts those left out), or that none
     * matches.
     */
    fun recall(about: String, found: List<MemoryItem>, private: Int, fr: Boolean): String {
        val more = when {
            private == 0 -> ""
            fr -> " Et ${if (private == 1) "une chose privée" else "$private choses privées"}, dans Réglages › Mémoire."
            else -> " And ${if (private == 1) "1 private item" else "$private private items"}, in Settings › Memory."
        }
        if (found.isEmpty()) return (if (fr) "Je ne me souviens de rien sur $about." else "I don't remember anything about $about.") +
            if (private > 0) more else ""
        val list = found.joinToString(if (fr) " " else "; ") { spoken(it, fr) }
        return if (fr) "Voici ce que je retiens sur $about : $list.$more" else "Here's what I remember about $about: $list.$more"
    }

    /** "What do you know about me?": how many, and a few of them (the most used, never a secret one). */
    fun aboutMe(total: Int, examples: List<MemoryItem>, fr: Boolean): String {
        if (total == 0) return if (fr) "Je ne retiens rien pour l'instant. Dis « souviens-toi que … » pour garder quelque chose." else
            "I don't remember anything yet. Say “remember that …” to keep something."
        val count = if (fr) (if (total == 1) "Je retiens une chose." else "Je retiens $total choses.") else (if (total == 1) "I remember 1 thing." else "I remember $total things.")
        if (examples.isEmpty()) return count + if (fr) " Elle est privée : ouvre Réglages › Mémoire." else " It's private: open Settings › Memory."
        val list = examples.joinToString(if (fr) " " else "; ") { spoken(it, fr) }
        return if (fr) "$count Par exemple : $list." else "$count For example: $list."
    }

    fun forgetAll(fr: Boolean): String = if (fr) MemoryAccess.FORGET_ALL_BY_VOICE_FR else MemoryAccess.FORGET_ALL_BY_VOICE

    fun unlock(fr: Boolean, saving: Boolean = false): String = when {
        saving -> if (fr) "Déverrouille d'abord, puis redemande-moi de le retenir : ma mémoire reste verrouillée." else
            "Unlock first, then ask me again to remember it: my memory stays locked."
        fr -> MemoryAccess.UNLOCK_FIRST_FR
        else -> MemoryAccess.UNLOCK_FIRST
    }

    fun off(fr: Boolean): String = if (fr) "La mémoire est désactivée. Active-la dans les réglages de Robot, sous Mémoire." else
        "Memory is off. Turn it on in Robot's settings, under Memory."

    /** Memory couldn't be opened on an unlocked phone ([load] not usable): why, in words. */
    fun cantOpen(load: MemoryLoad, fr: Boolean): String = when (load) {
        MemoryLoad.KeyGone -> if (fr) "Je ne peux pas ouvrir ce que tu m'as demandé de retenir : sa clé n'existe plus. Réglages › Mémoire en dit plus." else
            "I can't open what you asked me to remember: its key is gone. Settings › Memory says more."
        MemoryLoad.Unreadable -> if (fr) "Je n'arrive pas à lire ma mémoire. Réglages › Mémoire en dit plus." else
            "I can't read my memory file. Settings › Memory says more."
        is MemoryLoad.TooNew -> if (fr) "Ma mémoire vient d'une version plus récente de Robot : je ne peux pas la changer." else
            "My memory was saved by a newer Robot, so I can't change it."
        else -> if (fr) "Je n'ai pas pu ouvrir ma mémoire. Réessaie dans un instant." else "I couldn't open my memory just now. Try again in a moment."
    }

    /** The card for an item saved by voice: its summary (History keeps it: no words of the item) and its body (the panel only). */
    fun savedCard(item: MemoryItem): Pair<String, String> =
        (if (item.kind == MemoryKind.PREFERENCE && item.pinned) "Remembered, used in every answer" else "Remembered") to MemoryHygiene.shown(item)

    const val CARD_FORGOTTEN = "Forgotten"
    const val CARD_WHICH = "Forget which one?"
    const val CARD_SAVED_DETAIL = "Undo, or change it in Memory."
    const val CARD_FORGOTTEN_DETAIL = "Undo puts it back."
}

/**
 * What History keeps of memory (design §A6.4, "History hygiene"): the notes block never (ChatTurn.memoryIds instead),
 * a question that saved a secret only as "Remember: [a code, kept in Memory]", an answer that showed a secret only as a
 * line saying so, and card summaries without an item's words.
 */
object MemoryHygiene {
    /** An item as a card shows it: its words, or for a secret what kind of secret it is ("[a code, kept in Memory]"). */
    fun shown(item: MemoryItem, fr: Boolean = false): String =
        if (item.secret) SecretDetector.placeholder(SecretDetector.find(item.text), fr) else item.text

    /** The question History keeps for "remember that …" ([said]: your words). A secret is never kept in them. */
    fun question(c: MemoryCommand.Remember, said: String): String =
        if (!c.secret) said
        else (if (c.language == "fr") "Retiens : " else "Remember: ") + SecretDetector.placeholder(SecretDetector.find(c.text), c.language == "fr")

    /** The answer History keeps for a reply that had a secret item before it: never the answer itself. */
    const val SECRET_ANSWER = "[An answer with something private from Memory: not kept in History.]"

    /** Items as a short list for a card's body, one a line, secrets as their placeholder. */
    fun list(items: List<MemoryItem>): String = items.joinToString("\n") { "• " + shown(it) }
}

/** A day and an end, as Settings › Memory and the cards show them ("Saved Oct 9 · until tonight"). */
object MemoryDates {
    private val DAY = java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH)

    fun line(item: MemoryItem, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val day = { t: Long -> DAY.format(java.time.Instant.ofEpochMilli(t).atZone(zone)) }
        return listOfNotNull(
            if (item.pinned) "Pinned" else null,
            if (item.secret) "Private" else null,
            "saved ${day(item.createdAt)}",
            item.expiresAt?.let { if (now >= it) "ended" else "until ${day(it)}" },
            if (item.uses > 0) "used in ${if (item.uses == 1) "1 answer" else "${item.uses} answers"}" else null,
        ).joinToString(" · ").replaceFirstChar { it.uppercase() }
    }
}
