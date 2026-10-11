package app.heyrobot.assistant.conversation

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.heyrobot.core.ConvEntry
import app.heyrobot.core.ConvStop
import app.heyrobot.core.RLog
import app.heyrobot.sys.Transcripts

// VOICE PROCESS. The Quick Settings tile "Transcribe" (docs/conversation-mode.md 5.4): a tap starts a session (through
// the transcript screen, which Android lets start a microphone service, and which asks the one-time consent question
// first) or stops the one running; the tile shows which. Only the system binds it (BIND_QUICK_SETTINGS_TILE).

class ConversationTileService : TileService() {
    override fun onTileAdded() {
        super.onTileAdded()
        ConversationSession.init(this)
        update()
    }

    override fun onStartListening() {
        super.onStartListening()
        ConversationSession.init(this)
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        val ui = ConversationSession.state.value
        t.state = if (ui.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Transcribe"
        t.subtitle = when {
            ui.status == ConversationSession.Status.PAUSED -> "Paused"
            ui.active -> "On"
            else -> null
        }
        try { t.updateTile() } catch (_: Throwable) {}
    }

    override fun onClick() {
        super.onClick()
        ConversationSession.init(this)
        if (ConversationSession.running) {
            ConversationSession.stop(ConvStop.TILE)
            update()
            return
        }
        val open = {
            try {
                val i = Intent(Transcripts.ACTION_START).setClassName(packageName, Transcripts.ACTIVITY)
                    .putExtra(Transcripts.EXTRA_ENTRY, ConvEntry.TILE.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivityAndCollapse(PendingIntent.getActivity(this, 5_030, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } catch (t: Throwable) { RLog.w("conv_tile", t) }
        }
        // The screen itself shows over the lock screen when "Start from the lock screen" is on; otherwise unlock first.
        if (isLocked && !app.heyrobot.sys.SettingsStore.load(this).convStartLocked) unlockAndRun { open() } else open()
    }
}
