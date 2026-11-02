// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.ask

import android.graphics.Bitmap
import app.heyrobot.core.ask.AskCaps
import app.heyrobot.core.ask.SharedKind
import java.security.MessageDigest
import java.util.UUID

// VOICE PROCESS. Ask Robot (docs/plans/everyday-set-plan.md §7.2, E7): what another app shared, ready for the panel, held
// in this process's memory only under a one-time id. AskRobotActivity puts it here and starts PanelLaunchActivity with
// the id alone (RobotVoiceService.ARG_SHARED): the content itself never goes into an intent's extras or a show's
// arguments. The panel takes it once (PanelController.onShow); a new share replaces one not yet opened, and one left
// waiting longer than AskCaps.WAIT_MS is dropped. Nothing here is written anywhere.

/**
 * What another app shared, as the panel uses it. [text]: shared text, or a voice message's words. [jpeg]: a photo for the
 * model, made by :files (at most 1,024 px); [thumb]: its thumbnail, from :files' pixels. [seconds]: a voice message's
 * length. [cut]: text past 20,000 characters, or a voice message past 3 minutes.
 */
class SharedItem(
    val kind: SharedKind,
    val text: String? = null,
    val jpeg: ByteArray? = null,
    val thumb: Bitmap? = null,
    val seconds: Int = 0,
    val cut: Boolean = false,
) {
    init {
        require((kind == SharedKind.PHOTO) == (jpeg != null) && (kind == SharedKind.PHOTO || !text.isNullOrBlank()))
    }

    override fun toString(): String = "SharedItem($kind)"  // l10n: not text (never the content)
}

object AskInbox {
    private class Slot(val id: String, val item: SharedItem, val at: Long)

    private var slot: Slot? = null

    /** Holds [item] (replacing one not yet opened) and gives its one-time id: letters, digits and "-" (PanelLaunchActivity's rule). */
    @Synchronized
    fun put(item: SharedItem, now: Long): String {
        val id = UUID.randomUUID().toString()
        slot = Slot(id, item, now)
        return id
    }

    /** The item [id] names, once; null for any other id, one taken already, or one that waited too long (it goes then). */
    @Synchronized
    fun take(id: String?, now: Long): SharedItem? {
        val s = slot ?: return null
        if (id == null || !MessageDigest.isEqual(id.toByteArray(), s.id.toByteArray())) return null
        slot = null
        return s.item.takeIf { now - s.at in 0..AskCaps.WAIT_MS }
    }

    /** Whatever waits goes (a phone lock started, the panel refused it). */
    @Synchronized
    fun clear() {
        slot = null
    }

    @Synchronized
    internal fun waiting(): Boolean = slot != null
}
