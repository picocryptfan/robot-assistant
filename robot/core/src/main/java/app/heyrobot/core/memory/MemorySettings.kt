// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import app.heyrobot.core.enumOr
import org.json.JSONObject

// Pure. Settings › Memory (design §A4) and the rules that read them: the lock screen, helpers, content, the search
// model offer. The defaults are the design's answers to its open questions: suggestions as a quiet list (5), the search
// model offered once in Assistant setup, never downloaded by itself (6), helpers get memory only through the Include
// card, even trusted ones (7), and nothing on the lock screen.
//
// Kept in its own small file, files/robot/memory.json ([MemorySettings.toJson], like look.json), not in settings.json:
// the main app writes it (Settings › Memory), the voice process reads it again when it changes. An older or damaged file
// reads each field at the default below. The voice log's ids 180 to 189 are memory's (VoiceEvent.MEM_*, MemoryLog.kt).

/** Who may see retrieved items besides the local model (0.6.0 helpers). */
enum class HelperMemory(val title: String) {
    /** The default: an item goes to a helper only when you include it on the card. */
    ONLY_WHEN_INCLUDED("Only when I include it"),
    /** Retrieved, non-secret items of that turn go to a helper you trust without the card. */
    TRUSTED_HELPERS("With helpers I trust"),
}

/** How suggestions reach you. */
enum class SuggestionStyle(val title: String) {
    /** The default: a list in Settings › Memory › Suggestions with a dot on the Memory row; never spoken, no pop-up. */
    QUIET_LIST("A list in Settings (quiet)"),
    /** Also a chip "Remember: …?" at the end of a conversation. */
    END_CHIP("Ask me at the end of a conversation"),
    OFF("Off"),
}

/** Whether Robot still offers the search model (165 MB): offered once in Assistant setup, never downloaded by itself. */
enum class SearchModelOffer { NOT_OFFERED, OFFERED, DOWNLOADED, DECLINED }

data class MemorySettings(
    val on: Boolean = true,
    val useInAnswers: Boolean = true,
    val suggestions: SuggestionStyle = SuggestionStyle.QUIET_LIST,
    /** Search conversations you let Robot remember (only transcripts with their own switch on, off per transcript). */
    val transcripts: Boolean = true,
    /** "Use memory on the lock screen": off (anyone holding the phone could ask; the key needs unlock too). */
    val onLockScreen: Boolean = false,
    val speakSecrets: Boolean = false,
    val helpers: HelperMemory = HelperMemory.ONLY_WHEN_INCLUDED,
    /** "Remember who / what from content without a card": off. Never for a message, whatever this says. */
    val fromContentNoCard: Boolean = false,
    val searchModel: SearchModelOffer = SearchModelOffer.NOT_OFFERED,
) {
    fun toJson(): String = JSONObject()
        .put("v", VERSION)
        .put("on", on)
        .put("useInAnswers", useInAnswers)
        .put("suggestions", suggestions.name)
        .put("transcripts", transcripts)
        .put("onLockScreen", onLockScreen)
        .put("speakSecrets", speakSecrets)
        .put("helpers", helpers.name)
        .put("fromContentNoCard", fromContentNoCard)
        .put("searchModel", searchModel.name)
        .toString()

    companion object {
        const val FILE = "memory.json"
        const val VERSION = 1

        /** Missing, empty or damaged text gives the defaults; an unknown key or value, or a wrong type, reads as its default. */
        fun fromJson(text: String?): MemorySettings {
            val o = try { JSONObject(text ?: return MemorySettings()) } catch (_: Exception) { return MemorySettings() }
            val d = MemorySettings()
            fun flag(key: String, default: Boolean): Boolean = (o.opt(key) as? Boolean) ?: default
            return MemorySettings(
                on = flag("on", d.on),
                useInAnswers = flag("useInAnswers", d.useInAnswers),
                suggestions = enumOr(o.optString("suggestions"), d.suggestions),
                transcripts = flag("transcripts", d.transcripts),
                onLockScreen = flag("onLockScreen", d.onLockScreen),
                speakSecrets = flag("speakSecrets", d.speakSecrets),
                helpers = enumOr(o.optString("helpers"), d.helpers),
                fromContentNoCard = flag("fromContentNoCard", d.fromContentNoCard),
                searchModel = enumOr(o.optString("searchModel"), d.searchModel),
            )
        }
    }
}

/** What memory does on a locked phone. */
enum class LockedMemory {
    /** Unlocked (or allowed on the lock screen): memory works. */
    USE,
    /** Locked, and the question didn't ask for memory: retrieval is skipped, nothing is said about it. */
    SKIP,
    /** Locked, and the question asked for memory ("do you remember…"): "Unlock first: …". */
    SAY_UNLOCK,
}

object MemoryAccess {
    const val UNLOCK_FIRST = "Unlock first: what you asked me to remember stays locked."
    const val UNLOCK_FIRST_FR = "Déverrouille d'abord : ce que tu m'as demandé de retenir reste verrouillé."
    /** 0.7 after the review: nothing is saved to memory on the lock screen, "Use memory on the lock screen" or not. */
    const val UNLOCK_TO_SAVE = "Unlock first to save to memory."
    const val UNLOCK_TO_SAVE_FR = "Déverrouille d'abord pour l'enregistrer en mémoire."
    /** Nor removed from it ("forget …"): what you asked Robot to remember is changed only on an unlocked phone. */
    const val UNLOCK_TO_CHANGE = "Unlock first to change what I remember."
    const val UNLOCK_TO_CHANGE_FR = "Déverrouille d'abord pour changer ce que je retiens."
    const val FORGET_ALL_BY_VOICE = "Open Settings › Memory to delete everything."
    const val FORGET_ALL_BY_VOICE_FR = "Ouvre Réglages › Mémoire pour tout effacer."

    /**
     * Whether memory may be changed now (an item saved, forgotten, put back by Undo, or its use counted): only on an unlocked
     * phone. Someone holding your locked phone can't plant or remove what Robot remembers; "Use memory on the lock screen"
     * lets them hear it, never change it.
     */
    fun writes(locked: Boolean): Boolean = !locked

    /** Memory for [question] with the phone [locked]. Secret items never come up on the lock screen, even when allowed. */
    fun whenLocked(locked: Boolean, s: MemorySettings, question: String): LockedMemory = when {
        !s.on -> LockedMemory.SKIP
        !locked || s.onLockScreen -> LockedMemory.USE
        MemoryCommands.asksMemory(question) -> LockedMemory.SAY_UNLOCK
        else -> LockedMemory.SKIP
    }

    /**
     * The items a helper may get with a turn ([given]: what went to the local model). Secret items never go. Otherwise
     * only those you [included] on the card, or, with [HelperMemory.TRUSTED_HELPERS], all of them to a [trusted] helper.
     */
    fun forHelper(given: List<MemoryItem>, s: MemorySettings, trusted: Boolean, included: Set<String> = emptySet()): List<MemoryItem> =
        given.filter { !it.secret && (it.id in included || (s.helpers == HelperMemory.TRUSTED_HELPERS && trusted)) }

    /** Saving an item that came from content needs a card: always for a message, otherwise unless you turned that off. */
    fun cardForContent(fromMessage: Boolean, s: MemorySettings): Boolean = fromMessage || !s.fromContentNoCard

    /** Assistant setup offers the search model only while it was never offered, declined or downloaded. */
    fun offerSearchModel(s: MemorySettings): Boolean = s.on && s.searchModel == SearchModelOffer.NOT_OFFERED

    /** A secret item is read aloud only when you asked for it by name and "Speak secret items aloud" is on. */
    fun speak(item: MemoryItem, question: String, s: MemorySettings, locked: Boolean): Boolean =
        !item.secret || (!locked && s.speakSecrets && PromptPacking.secretNamed(question, item))
}
