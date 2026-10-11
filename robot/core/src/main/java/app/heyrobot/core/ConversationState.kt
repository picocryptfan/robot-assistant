package app.heyrobot.core

import org.json.JSONObject
import java.util.Locale

// Pure. A conversation session's settings as each face uses them, and its heartbeat file for crash recovery
// (docs/conversation-mode.md 1.6): filesDir/transcripts/.session.json says a session is running, written atomically
// every 30 s. When :voice starts and finds one with no service holding it, the transcript is closed as interrupted and
// Robot offers Resume; it never starts recording again by itself, and after your own "Stop" in Active apps it doesn't ask.

/** A transcript's state (meta.json). */
enum class SessionStatus { RUNNING, ENDED, INTERRUPTED }

/** Conversation mode's settings for one face of a session. */
object ConversationSettings {
    /** The Interpreter tells two people apart, whatever the setting says. */
    const val INTERPRETER_SPEAKERS = 2
    val SPEAKER_CHOICES = 2..6

    /** The Interpreter always translates; the transcript does when "Translate" is on. */
    fun translate(s: RobotSettings, mode: ConvMode): Boolean = mode == ConvMode.INTERPRET || s.convTranslate

    /** Speak translations: as set, else on in the Interpreter and off in the transcript. */
    fun speak(s: RobotSettings, mode: ConvMode): Boolean = s.convSpeak ?: (mode == ConvMode.INTERPRET)

    fun maxSpeakers(s: RobotSettings, mode: ConvMode): Int = if (mode == ConvMode.INTERPRET) INTERPRETER_SPEAKERS else s.convMaxSpeakers

    /** The open segment's words while people talk: as set, screen on only, and off once the phone is warm. */
    fun partials(s: RobotSettings, screenOn: Boolean, warm: Boolean): Boolean = s.convPartials && screenOn && !warm

    /** The language of Robot's summaries: as set, else yours. */
    fun summaryLang(s: RobotSettings, locale: Locale = Locale.getDefault()): String = s.convSummaryLang ?: LangLabel.owner(locale)
}

/** The running session's heartbeat (filesDir/transcripts/.session.json). Times are wall clock, ms. */
data class SessionHeartbeat(val id: String, val mode: ConvMode, val startedAt: Long, val beatAt: Long, val lastLineAt: Long = 0, val lines: Int = 0) {
    fun toJson(): String = JSONObject().put("id", id).put("mode", mode.name).put("startedAt", startedAt).put("beatAt", beatAt)
        .put("lastLineAt", lastLineAt).put("lines", lines).toString()

    companion object {
        const val FILE = ".session.json"

        fun fromJson(text: String?): SessionHeartbeat? = try {
            val o = JSONObject(text ?: return null)
            SessionHeartbeat(o.getString("id").also { require(TranscriptIds.valid(it)) }, enumOr(o.optString("mode"), ConvMode.TRANSCRIBE),
                o.optLong("startedAt"), o.optLong("beatAt"), o.optLong("lastLineAt"), o.optInt("lines").coerceAtLeast(0))
        } catch (_: Exception) { null }
    }
}

object SessionRecovery {
    const val BEAT_MS = 30_000L

    enum class Action {
        /** Nothing to do: no heartbeat, or the service holds the session. */
        NONE,
        /** Close it as interrupted and post "Transcription stopped unexpectedly. Saved up to 14:32. Resume". */
        OFFER_RESUME,
        /** Close it as ended, without asking: you stopped Robot yourself (Active apps). */
        CLOSE,
    }

    /** At :voice start: [beat] from the file (null: none), whether a service holds a session, and whether you stopped Robot. */
    fun decide(beat: SessionHeartbeat?, serviceRunning: Boolean, stoppedByUser: Boolean): Action = when {
        beat == null || serviceRunning -> Action.NONE
        stoppedByUser -> Action.CLOSE
        else -> Action.OFFER_RESUME
    }

    /** When the transcript is saved up to: its last line, else the last heartbeat. */
    fun savedUpTo(beat: SessionHeartbeat): Long = if (beat.lastLineAt > 0) beat.lastLineAt else beat.beatAt
}
