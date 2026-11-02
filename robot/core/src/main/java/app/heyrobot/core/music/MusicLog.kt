// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.music

import app.heyrobot.core.LogCode

// Pure. The music's voice-log codes (docs/plans/everyday-set-plan.md §2.5, D22; job E1): the small stable numbers events
// 320 to 323 carry. Codes and counts only: never a song, an artist, what you asked for, or your music app's package or
// name. Never renumber an entry.

/** MUSIC_PLAY's b: what play by name came to. */
enum class MusicPlayCode(override val id: Int, override val title: String) : LogCode {
    PLAYING(1, "playing"), NO_SERVICE(2, "no media service"), REFUSED(3, "the app refused Robot"), // l10n: not text (voice log codes)
    NO_SEARCH(4, "no play from search"), NO_ANSWER(5, "no answer"), CLOSED_BY_LOCK(6, "closed during a phone lock"), // l10n: not text (voice log codes)
    REPLACED(7, "signed by someone else"), NOT_PICKED(8, "no music app picked"), // l10n: not text (voice log codes)
    ;
}

/** MUSIC_APP's a: your music app picked, changed or refused. */
enum class MusicAppCode(override val id: Int, override val title: String) : LogCode {
    PICKED(1, "picked"), CHANGED(2, "changed"), REPLACED(3, "refused: signed by someone else"), GONE(4, "no longer installed"), // l10n: not text (voice log codes)
    CANCELLED(5, "not picked"), NONE_FOUND(6, "none on this phone"), LOCKED(7, "refused: locked"), PHONE_LOCK(8, "refused: phone lock"), // l10n: not text (voice log codes)
    FORGOTTEN(9, "forgotten"), // l10n: not text (voice log codes)
    ;
}

/** MUSIC_NOW's a: "what's playing" answered or not. */
enum class MusicNowCode(override val id: Int, override val title: String) : LogCode {
    FOUND(1, "found"), NO_APP(2, "no music app picked"), NOT_REACHED(3, "the app's media service didn't answer"), // l10n: not text (voice log codes)
    NOTHING(4, "nothing playing"), REPLACED(5, "signed by someone else"), // l10n: not text (voice log codes)
    ;
}
