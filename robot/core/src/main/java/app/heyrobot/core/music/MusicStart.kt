package app.heyrobot.core.music

/** What an app's media session shows at a moment: whether it's playing, what, and where in it. */
data class SessionNow(val playing: Boolean, val title: String?, val artist: String?, val positionMs: Long)

object MusicStart {
    /** Going back further than this is a song started again, not a report's jitter. EST. */
    private const val BACK_MS = 2_000L

    /**
     * Whether the app took a play request, seen from what it showed just before ([before]) and now. E10: an app that was
     * already playing has only started something if what it plays changed, or went back to its start.
     */
    fun started(before: SessionNow?, now: SessionNow): Boolean = when {
        !now.playing -> false
        before == null || !before.playing -> true
        else -> now.title != before.title || now.artist != before.artist || now.positionMs + BACK_MS < before.positionMs
    }
}
