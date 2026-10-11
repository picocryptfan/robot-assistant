package app.heyrobot.core.memory

// Pure. Settings › Memory (design §A4) and the rules that read them: the lock screen, helpers, content, the search
// model offer. The defaults are the design's answers to its open questions: suggestions as a quiet list (5), the search
// model offered once in Assistant setup, never downloaded by itself (6), helpers get memory only through the Include
// card, even trusted ones (7), and nothing on the lock screen.
//
// Wiring later (not on this branch, to keep it apart from the branches that change Settings.kt and VoiceLog.kt):
// - RobotSettings gains these fields under the next settings schema (memoryOn, memoryUse, memorySuggest,
//   memorySuggestChip, memoryTranscripts, memoryLocked, memorySpeakSecrets, memoryHelpers, memoryFromContentNoCard,
//   memorySearchModelOffered); an older file reads each at the default below. Until then the app builds a
//   [MemorySettings] from those fields.
// - The voice log reserves ids 180–189 for memory: 180 MEM_SAVE (a = MemorySource ordinal, b = MemoryKind ordinal,
//   c = 1 if it expires), 181 MEM_FORGET (a = count, b = how: last, search, settings, expiry, all), 182 MEM_RECALL
//   (a = items given, b = RecallBy.code, c = ms), 183 MEM_EMBED_LOAD (a = ms, b = 1 ok, c = backend), 184 MEM_SUGGEST
//   (a = made, b = kept, c = dismissed), 185 MEM_LOCKED (a = MemoryLoad kind), 186 MEM_SEARCH_TRANSCRIPTS, 187 MEM_INDEX,
//   188–189 spare. Codes and counts only: never an item's words.
// - Claude.kt's Personal gains MEMORY("what Robot remembers about you") for PackedMemory.personal.

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
)

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
    const val FORGET_ALL_BY_VOICE = "Open Settings › Memory to delete everything."
    const val FORGET_ALL_BY_VOICE_FR = "Ouvre Réglages › Mémoire pour tout effacer."

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
