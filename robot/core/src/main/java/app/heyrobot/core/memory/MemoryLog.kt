package app.heyrobot.core.memory

import app.heyrobot.core.LogCode

// Pure. Memory's codes in the voice log (VoiceEvent 180 to 189): what kind of thing happened, never an item's words, a
// question or a name. Each id is stable (old log files keep the old numbers).

/** Where a saved item came from (MemorySource). */
enum class MemOrigin(override val id: Int, override val title: String) : LogCode {
    SAID(1, "you said it"), TYPED(2, "you typed it"), SUGGESTED(3, "a suggestion you kept"), SETTINGS(4, "added in Settings"),
    CONTENT(5, "from something Robot read, after its card");

    companion object {
        fun of(s: MemorySource): MemOrigin = when (s) {
            MemorySource.SAID -> SAID
            MemorySource.TYPED -> TYPED
            MemorySource.SUGGESTED -> SUGGESTED
            MemorySource.SETTINGS -> SETTINGS
            MemorySource.CONTENT -> CONTENT
        }
    }
}

/** What kind of item: a fact, a preference, or a secret (a code, a password: kept private). */
enum class MemKindCode(override val id: Int, override val title: String) : LogCode {
    FACT(1, "a fact"), PREFERENCE(2, "a preference"), SECRET(3, "something private");

    companion object {
        fun of(item: MemoryItem): MemKindCode = when {
            item.secret -> SECRET
            item.kind == MemoryKind.PREFERENCE -> PREFERENCE
            else -> FACT
        }
    }
}

/** How items were forgotten. */
enum class MemForget(override val id: Int, override val title: String) : LogCode {
    LAST(1, "“forget that”"), SEARCH(2, "by what you said"), CARD(3, "the model's card"), SETTINGS(4, "in Settings"),
    EXPIRY(5, "they ended"), ALL(6, "Delete all"), UNDO(7, "Undo after saving"),
}

/** Why memory couldn't be read (MemoryLoad). */
enum class MemLock(override val id: Int, override val title: String) : LogCode {
    UNLOCK(1, "the phone is locked"), KEY_GONE(2, "its key is gone"), UNREADABLE(3, "the file can't be read"), TOO_NEW(4, "a newer Robot wrote it");

    companion object {
        /** The code for [load], or null when it opened (or there is nothing yet). */
        fun of(load: MemoryLoad): MemLock? = when (load) {
            MemoryLoad.Locked -> UNLOCK
            MemoryLoad.KeyGone -> KEY_GONE
            MemoryLoad.Unreadable -> UNREADABLE
            is MemoryLoad.TooNew -> TOO_NEW
            is MemoryLoad.Loaded, MemoryLoad.Empty -> null
        }
    }
}

/** A memory command heard before the model (MemoryRoute). */
enum class MemCommandCode(override val id: Int, override val title: String) : LogCode {
    REMEMBER(1, "remember"), FORGET_LAST(2, "forget that"), FORGET(3, "forget something"), FORGET_ALL(4, "forget everything (sent to Settings)"),
    RECALL(5, "what Robot remembers about something"), ABOUT_ME(6, "what Robot knows about you"), UNLOCK(7, "said to unlock first"),
    OFF(8, "said memory is off");

    companion object {
        fun of(c: MemoryCommand): MemCommandCode = when (c) {
            is MemoryCommand.Remember -> REMEMBER
            is MemoryCommand.ForgetLast -> FORGET_LAST
            is MemoryCommand.Forget -> FORGET
            is MemoryCommand.ForgetEverything -> FORGET_ALL
            is MemoryCommand.Recall -> RECALL
            is MemoryCommand.AboutMe -> ABOUT_ME
        }
    }
}
